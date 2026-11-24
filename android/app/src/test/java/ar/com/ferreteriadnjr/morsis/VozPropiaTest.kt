package ar.com.ferreteriadnjr.morsis

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Controles del detector por voz propia con "palabras" sintéticas: secuencias de sonidos (vocales simuladas con
 * dos formantes). La misma palabra dicha más rápido, más lento, más bajo o con ruido tiene que parecerse más
 * que una palabra distinta.
 */
class VozPropiaTest {
    private val r = Random(7)

    /** Cada sílaba: (formante 1, formante 2) en Hz; velocidad < 1 = más rápido. */
    private fun palabra(silabas: List<Pair<Double, Double>>, velocidad: Double = 1.0, volumen: Double = 1.0, ruido: Double = 0.0): ShortArray {
        val porSilaba = (0.16 * velocidad * VozPropia.TASA).toInt()
        val total = porSilaba * silabas.size + 4000
        val a = ShortArray(total)
        var fase = 0.0
        for (i in 0 until total) {
            val t = (i - 2000).toDouble()
            var x = 0.0
            if (t >= 0 && t < porSilaba * silabas.size) {
                val (f1, f2) = silabas[(t / porSilaba).toInt()]
                fase += 2 * PI * 130 / VozPropia.TASA                       // tono de voz
                val pulso = if (sin(fase) > 0.9) 1.0 else 0.0
                x = 6000 * volumen * (sin(2 * PI * f1 * i / VozPropia.TASA) * 0.7 + sin(2 * PI * f2 * i / VozPropia.TASA) * 0.3) * (0.6 + 0.4 * pulso)
            }
            x += ruido * (r.nextDouble() - 0.5) * 2
            a[i] = x.coerceIn(-32000.0, 32000.0).toInt().toShort()
        }
        return a
    }

    private val morsis = listOf(300.0 to 870.0, 500.0 to 1300.0, 270.0 to 2300.0, 350.0 to 2600.0)   // mo-r-si-s
    private val tornillo = listOf(500.0 to 900.0, 700.0 to 1200.0, 280.0 to 2250.0, 450.0 to 850.0)
    private val hola = listOf(450.0 to 800.0, 750.0 to 1300.0)

    private fun huella(a: ShortArray) = VozPropia.mfcc(VozPropia.recortar(a))
    private fun oido(a: ShortArray) = VozPropia.mfcc(VozPropia.recortar(a), restarMedia = false)

    @Test fun laMismaPalabraSeParecePorEncimaDeOtra() {
        val muestras = listOf(palabra(morsis), palabra(morsis, 0.9), palabra(morsis, 1.1, 0.8), palabra(morsis, 1.0, 1.2, 300.0), palabra(morsis, 0.95)).map { huella(it) }
        val umbral = VozPropia.umbral(muestras, "media")
        val mejor = { a: ShortArray -> val h = oido(a); muestras.minOf { VozPropia.dtw(it, h, prefijo = true) } }
        val propia = mejor(palabra(morsis, 1.05, 0.7, 400.0))
        val otra = mejor(palabra(tornillo))
        val corta = mejor(palabra(hola))
        println("umbral=$umbral propia=$propia otra=$otra corta=$corta baja=" + VozPropia.umbral(muestras, "baja"))
        assertTrue("la misma palabra activa", propia <= umbral)
        assertTrue("otra palabra no activa", otra > umbral)
        assertTrue("una palabra corta no activa", corta > umbral)
        assertTrue("se distinguen con margen", otra > propia * 1.3)
        // Votación: la palabra propia se parece a por lo menos 2 muestras; la otra a ninguna.
        val h = oido(palabra(morsis, 1.05, 0.7, 400.0))
        val dh = muestras.map { VozPropia.dtw(it, h, prefijo = true) }
        println("votación propia=" + dh.sorted())
        assertTrue("la palabra pasa la votación", VozPropia.esLaPalabra(dh, umbral))
        assertTrue("otra no", !VozPropia.esLaPalabra(muestras.map { VozPropia.dtw(it, oido(palabra(tornillo)), prefijo = true) }, umbral))
        assertTrue("un ruido parecido a una sola muestra no", !VozPropia.esLaPalabra(listOf(umbral * 0.9, umbral * 1.6, umbral * 1.8), umbral))
    }

    @Test fun conAlgoDichoDespuesTambienActiva() {
        val muestras = listOf(palabra(morsis), palabra(morsis, 0.9), palabra(morsis, 1.1)).map { huella(it) }
        val umbral = VozPropia.umbral(muestras, "media")
        val seguido = palabra(morsis + hola + tornillo)   // "Morsis, hola tornillo…" sin pausa
        val h = oido(seguido)
        val d = muestras.minOf { VozPropia.dtw(it, h, prefijo = true) }
        println("prefijo: umbral=$umbral seguido=$d")
        assertTrue("prefijo", d <= umbral)
    }

    @Test fun recortaElSilencio() {
        val a = palabra(morsis)
        val b = VozPropia.recortar(a)
        assertTrue(b.size < a.size && VozPropia.duracion(b) > 0.5)
    }
}
