package ar.com.ferreteriadnjr.morsis

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Reconoce la palabra por cómo SUENA en tu voz (no por diccionario): se graban 5 muestras de "Morsis" y lo que
 * el micrófono escucha se compara con ellas.
 *  - MFCC: convierte el audio en una "huella" de 13 números cada 10 ms (cómo se reparte la voz en frecuencias).
 *  - DTW: compara dos huellas aunque una se haya dicho más rápido o más lento que la otra.
 * Todo es matemática pura (sin Android) para poder probarlo en la compu.
 */
object VozPropia {
    const val TASA = 16000
    private const val VENTANA = 400      // 25 ms
    private const val PASO = 160         // 10 ms
    private const val NFFT = 512
    private const val FILTROS = 26
    private const val COEF = 13

    private val hamming = DoubleArray(VENTANA) { 0.54 - 0.46 * cos(2 * PI * it / (VENTANA - 1)) }
    private val bancoMel: Array<DoubleArray> by lazy { crearBancoMel() }

    private fun hzAMel(f: Double) = 2595.0 * log10(1 + f / 700.0)
    private fun melAHz(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1)

    private fun crearBancoMel(): Array<DoubleArray> {
        val bajo = hzAMel(60.0); val alto = hzAMel(TASA / 2.0)
        val puntos = DoubleArray(FILTROS + 2) { melAHz(bajo + (alto - bajo) * it / (FILTROS + 1)) }
        val bins = IntArray(FILTROS + 2) { ((NFFT + 1) * puntos[it] / TASA).toInt() }
        return Array(FILTROS) { m ->
            val f = DoubleArray(NFFT / 2 + 1)
            val a = bins[m]; val b = bins[m + 1]; val c = bins[m + 2]
            for (k in a until b) if (b > a) f[k] = (k - a).toDouble() / (b - a)
            for (k in b until c) if (c > b) f[k] = (c - k).toDouble() / (c - b)
            f
        }
    }

    /** FFT en el lugar (re, im de largo potencia de 2). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var largo = 2
        while (largo <= n) {
            val ang = -2 * PI / largo
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until largo / 2) {
                    val ar = re[i + k + largo / 2] * cr - im[i + k + largo / 2] * ci
                    val ai = re[i + k + largo / 2] * ci + im[i + k + largo / 2] * cr
                    re[i + k + largo / 2] = re[i + k] - ar; im[i + k + largo / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += largo
            }
            largo = largo shl 1
        }
    }

    /** Huella MFCC (cuadros × 13), con la media restada (así no importa tanto el volumen ni el micrófono). */
    fun mfcc(audio: ShortArray, restarMedia: Boolean = true): Array<DoubleArray> {
        if (audio.size < VENTANA) return emptyArray()
        val cuadros = 1 + (audio.size - VENTANA) / PASO
        val salida = Array(cuadros) { DoubleArray(COEF) }
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        val energias = DoubleArray(FILTROS)
        for (c in 0 until cuadros) {
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            val ini = c * PASO
            var previo = if (ini > 0) audio[ini - 1].toDouble() else 0.0
            for (i in 0 until VENTANA) {
                val x = audio[ini + i].toDouble()
                re[i] = (x - 0.97 * previo) * hamming[i]   // pre-énfasis + ventana
                previo = x
            }
            fft(re, im)
            for (m in 0 until FILTROS) {
                var s = 0.0
                val filtro = bancoMel[m]
                for (k in 0..NFFT / 2) if (filtro[k] > 0) s += filtro[k] * (re[k] * re[k] + im[k] * im[k]) / NFFT
                energias[m] = ln(max(s, 1e-10))
            }
            for (k in 0 until COEF) {
                var s = 0.0
                for (m in 0 until FILTROS) s += energias[m] * cos(PI * k * (m + 0.5) / FILTROS)
                salida[c][k] = s
            }
        }
        if (restarMedia) restarMedia(salida, cuadros)
        return salida
    }

    private fun restarMedia(h: Array<DoubleArray>, cuadros: Int) {
        if (cuadros <= 0) return
        for (k in 0 until h[0].size) {
            var media = 0.0
            for (c in 0 until cuadros) media += h[c][k]
            media /= cuadros
            for (c in 0 until cuadros) h[c][k] -= media
        }
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (k in 1 until a.size) { val d = a[k] - b[k]; s += d * d }   // sin c0 (volumen)
        return sqrt(s)
    }

    /**
     * Distancia DTW normalizada entre la muestra (completa) y el comienzo de lo oído.
     * prefijo = true: lo oído puede seguir después ("Morsis, qué hora es"): se busca el mejor final.
     */
    fun dtw(muestra: Array<DoubleArray>, oido: Array<DoubleArray>, prefijo: Boolean): Double {
        val n = muestra.size
        if (n == 0 || oido.isEmpty()) return Double.MAX_VALUE
        val m0 = if (prefijo) min(oido.size, (n * 1.7).toInt()) else oido.size
        if (m0 < n * 0.5) return Double.MAX_VALUE
        // Con prefijo, lo oído viene SIN la media restada (puede seguir con otras palabras): se resta la media
        // solo del tramo que se compara, del largo de la muestra, para que lo que viene después no la corra.
        val oido = if (prefijo) {
            val tramo = min(oido.size, n)
            val copia = Array(m0) { oido[it].copyOf() }
            for (k in 0 until copia[0].size) {
                var media = 0.0
                for (c in 0 until tramo) media += oido[c][k]
                media /= tramo
                for (c in 0 until m0) copia[c][k] -= media
            }
            copia
        } else oido
        val m = m0
        val inf = Double.MAX_VALUE / 4
        var ant = DoubleArray(m + 1) { inf }; ant[0] = 0.0
        var act = DoubleArray(m + 1)
        for (i in 1..n) {
            act[0] = inf
            for (j in 1..m) {
                act[j] = dist(muestra[i - 1], oido[j - 1]) + min(ant[j - 1], min(ant[j], act[j - 1]))
            }
            val t = ant; ant = act; act = t
        }
        if (!prefijo) return ant[m] / (n + m)
        var mejor = Double.MAX_VALUE
        for (j in max(1, (n * 0.6).toInt())..m) mejor = min(mejor, ant[j] / (n + j))
        return mejor
    }

    /** Umbral a partir de las muestras: qué tan distintas son entre sí (promedio) × sensibilidad. */
    fun umbral(muestras: List<Array<DoubleArray>>, sensibilidad: String): Double {
        if (muestras.size < 2) return 0.0
        var suma = 0.0; var n = 0
        for (i in muestras.indices) for (j in i + 1 until muestras.size) { suma += dtw(muestras[i], muestras[j], false); n++ }
        val factor = when (sensibilidad) { "alta" -> 2.6; "baja" -> 1.8; else -> 2.2 }
        return suma / n * factor
    }

    /**
     * ¿Es la palabra? La más parecida tiene que pasar el umbral y la segunda más parecida tiene que andar cerca
     * (hasta 20 % más): tu «Morsis» se parece a varias muestras; un ruido suele parecerse a una sola.
     */
    fun esLaPalabra(distancias: List<Double>, umbral: Double): Boolean {
        val d = distancias.sorted()
        if (d.isEmpty() || d[0] > umbral) return false
        return d.size < 2 || d[1] <= umbral * 1.2
    }

    /** Saca el silencio del principio y del final (lo que está muy por debajo de lo más fuerte). */
    fun recortar(audio: ShortArray): ShortArray {
        val n = audio.size / PASO
        if (n < 3) return audio
        val rms = DoubleArray(n) { c -> var s = 0.0; for (i in c * PASO until (c + 1) * PASO) s += audio[i].toDouble() * audio[i]; sqrt(s / PASO) }
        val umbral = max(rms.maxOrNull() ?: 0.0, 1.0) * 0.12
        val ini = max(0, rms.indexOfFirst { it > umbral } - 3)
        val fin = min(n, rms.indexOfLast { it > umbral } + 4)
        if (fin <= ini) return audio
        return audio.copyOfRange(ini * PASO, fin * PASO)
    }

    /** Segundos de audio. */
    fun duracion(audio: ShortArray) = audio.size.toDouble() / TASA

    // ------------------------------------------------------------------ muestras en el celu
    fun carpeta(dir: File) = File(dir, "muestras").apply { mkdirs() }

    fun guardar(dir: File, huella: Array<DoubleArray>) {
        val f = File(carpeta(dir), "m_" + System.currentTimeMillis() + ".mfcc")
        DataOutputStream(f.outputStream().buffered()).use { o ->
            o.writeInt(huella.size); o.writeInt(if (huella.isEmpty()) 0 else huella[0].size)
            huella.forEach { fila -> fila.forEach { o.writeFloat(it.toFloat()) } }
        }
        // Se guardan las 8 más nuevas.
        carpeta(dir).listFiles()?.sortedByDescending { it.name }?.drop(8)?.forEach { it.delete() }
    }

    fun leer(dir: File): List<Array<DoubleArray>> = (carpeta(dir).listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".mfcc") }.sortedBy { it.name }.mapNotNull { f ->
            try {
                DataInputStream(f.inputStream().buffered()).use { i ->
                    val c = i.readInt(); val k = i.readInt()
                    Array(c) { DoubleArray(k) { i.readFloat().toDouble() } }
                }
            } catch (_: Exception) { null }
        }

    fun borrar(dir: File) { carpeta(dir).listFiles()?.forEach { it.delete() } }
}
