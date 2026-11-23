package ar.com.ferreteriadnjr.morsis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Reconoce "Morsis" en lo que escribe el detector offline (que no conoce esa palabra y escribe la más parecida:
 * "moisés", "mercedes", "si muchas"…). Es la misma lógica que detectarActivacion() de nucleo.js, más las frases
 * que Morsis aprendió ("Enseñarle cómo digo Morsis"), que la página le pasa a la app con configurar().
 */
class Palabra(val palabra: String, private val aprendidas: List<String>, val sensibilidad: String = "media") {

    /** Confianza mínima (0 a 1) que tiene que tener la palabra oída en el modo estricto. */
    val confianzaMinima: Double get() = when (sensibilidad) { "alta" -> 0.55; "baja" -> 0.85; else -> 0.7 }

    /**
     * Frases para el modo estricto (gramática de Vosk): solo se reconocen ESTAS frases, lo demás sale como "[unk]".
     * Tienen que estar en el diccionario del detector (con tildes, en minúscula). Si la palabra elegida no está,
     * devuelve una lista vacía y se usa el modo aproximado.
     */
    fun frasesEstrictas(existe: (String) -> Boolean): List<String> {
        val propia = palabra.lowercase().trim().replace(Regex("[^a-záéíóúüñ ]"), " ").replace(Regex("\\s+"), " ").trim()
        val candidatas = mutableListOf(propia)
        if (normal(palabra) == "morsis") candidatas.addAll(listOf("moisés", "mercedes"))
        return candidatas.filter { f -> f.isNotBlank() && f.split(" ").all { existe(it) } }.distinct()
    }

    /** En modo estricto: lo oído es exactamente una de las frases (normalizadas). */
    fun esFrase(texto: String, frases: List<String>): Boolean {
        val n = " " + normal(texto) + " "
        return frases.any { n.contains(" " + normal(it) + " ") }
    }

    fun detecta(texto: String): Boolean {
        val clave = normal(palabra).replace(" ", "")
        if (clave.isEmpty()) return false
        val n = " " + normal(texto) + " "
        if (aprendidas.any { it.isNotBlank() && n.contains(" $it ") }) return true
        val palabras = normal(texto).split(" ").filter { it.isNotEmpty() }
        val tolerancia = if (clave.length >= 6) 2 else 1
        val alias = if (clave == "morsis") ALIAS else emptyList()
        fun parecida(w: String): Boolean = w.length >= 3 && (w == clave || alias.contains(w)
            || (clave == "morsis" && w.length >= 4 && (w.startsWith("mor") || w.startsWith("mois")))
            || (w[0] == clave[0] && distancia(w, clave) <= tolerancia))
        for (i in palabras.indices) {
            if (parecida(palabras[i])) return true
            if (i + 1 < palabras.size && parecida(palabras[i] + palabras[i + 1])) return true
        }
        return false
    }

    companion object {
        private val ALIAS = listOf("moises", "morsi", "morci", "morcis", "morris", "mores", "morfis", "marcis", "norsis", "mercedes", "merced")

        fun normal(t: String): String = Normalizer.normalize(t, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase()
            .replace(Regex("[^a-z0-9ñ ]"), " ").replace(Regex("\\s+"), " ").trim()

        fun distancia(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            return d[a.length][b.length]
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("morsis", Context.MODE_PRIVATE)

        /** json: {"palabra":"Morsis","aprendidas":["si muchas", …]} (lo manda la página). */
        fun guardar(ctx: Context, json: String) {
            try {
                val o = JSONObject(json)
                prefs(ctx).edit()
                    .putString("palabra", o.optString("palabra", "Morsis").ifBlank { "Morsis" })
                    .putString("sensibilidad", o.optString("sensibilidad", "media").ifBlank { "media" })
                    .putString("aprendidas", (o.optJSONArray("aprendidas") ?: JSONArray()).toString())
                    .apply()
            } catch (_: Exception) { }
        }

        fun leer(ctx: Context): Palabra {
            val p = prefs(ctx)
            val lista = mutableListOf<String>()
            try {
                val a = JSONArray(p.getString("aprendidas", "[]"))
                for (i in 0 until a.length()) lista.add(normal(a.optString(i)))
            } catch (_: Exception) { }
            return Palabra(p.getString("palabra", "Morsis") ?: "Morsis", lista, p.getString("sensibilidad", "media") ?: "media")
        }
    }
}
