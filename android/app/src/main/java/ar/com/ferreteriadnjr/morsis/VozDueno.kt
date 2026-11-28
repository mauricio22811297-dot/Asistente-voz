package ar.com.ferreteriadnjr.morsis

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Solo mi voz": decide si quien dijo «Morsis» es el dueño.
 *
 * Vosk (con su modelo de voces, vosk-model-spk) convierte un pedazo de voz en una "huella de la persona": 128
 * números que dependen de CÓMO suena la voz (garganta, tono), no de qué palabra se dijo. Dos huellas de la misma
 * persona apuntan para el mismo lado (coseno alto); de personas distintas, no tanto.
 *
 * Con las muestras grabadas se arma el promedio del dueño y se mide qué tan parecidas son entre sí: el umbral sale
 * de ahí (cada voz y cada micrófono son distintos), un poco más bajo según la sensibilidad.
 * Todo es matemática pura (sin Android) para poder probarlo en la compu.
 */
object VozDueno {

    fun coseno(a: DoubleArray, b: DoubleArray): Double {
        var p = 0.0; var na = 0.0; var nb = 0.0
        for (i in 0 until min(a.size, b.size)) { p += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else p / sqrt(na * nb)
    }

    fun promedio(v: List<DoubleArray>): DoubleArray {
        val n = v.firstOrNull()?.size ?: 0
        val r = DoubleArray(n)
        v.forEach { x -> val l = sqrt(x.sumOf { it * it }).takeIf { it > 0 } ?: 1.0; for (i in 0 until n) r[i] += x[i] / l }
        return r
    }

    /**
     * Umbral: cada muestra comparada con el promedio de LAS OTRAS (así no se compara consigo misma); se toma la
     * menos parecida y se le resta un margen según la sensibilidad. Nunca menos de 0,2 (eso ya es "cualquiera").
     */
    fun umbral(v: List<DoubleArray>, sensibilidad: String): Double {
        if (v.size < 2) return 0.0
        val propias = v.indices.map { i -> coseno(v[i], promedio(v.filterIndexed { j, _ -> j != i })) }
        val margen = when (sensibilidad) { "alta" -> 0.15; "baja" -> 0.05; else -> 0.10 }
        return max(0.2, propias.minOrNull()!! - margen)
    }

    /** Parecido (coseno) de lo oído con el dueño. */
    fun parecido(oido: DoubleArray, v: List<DoubleArray>): Double = if (v.isEmpty()) 0.0 else coseno(oido, promedio(v))

    /** Lee el "spk" del resultado de Vosk: {"spk":[…], "spk_frames":N, "text":"…"} → null si no vino. */
    fun deJson(json: String?): DoubleArray? = try {
        val o = org.json.JSONObject(json ?: "{}")
        val a = o.optJSONArray("spk")
        if (a == null || a.length() == 0) null else DoubleArray(a.length()) { a.getDouble(it) }
    } catch (_: Exception) { null }
}
