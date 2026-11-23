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
class Palabra(private val palabra: String, private val aprendidas: List<String>) {

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
            return Palabra(p.getString("palabra", "Morsis") ?: "Morsis", lista)
        }
    }
}
