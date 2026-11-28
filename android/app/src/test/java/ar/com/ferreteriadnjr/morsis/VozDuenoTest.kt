package ar.com.ferreteriadnjr.morsis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VozDuenoTest {
    private val r = Random(7)
    /** Huellas falsas: una "voz" es una dirección fija más un poco de ruido. */
    private fun voz(): DoubleArray = DoubleArray(128) { r.nextDouble(-1.0, 1.0) }
    private fun dicho(v: DoubleArray, ruido: Double) = DoubleArray(v.size) { v[it] + r.nextDouble(-ruido, ruido) }

    @Test fun mismaPersonaPasaYOtraNo() {
        val yo = voz(); val otro = voz()
        val muestras = List(5) { dicho(yo, 0.6) }
        val umbral = VozDueno.umbral(muestras, "media")
        var aciertos = 0; var colados = 0
        repeat(50) {
            if (VozDueno.parecido(dicho(yo, 0.6), muestras) >= umbral) aciertos++
            if (VozDueno.parecido(dicho(otro, 0.6), muestras) >= umbral) colados++
        }
        assertTrue("reconoce al dueño: $aciertos/50", aciertos >= 45)
        assertEquals("no deja pasar a otro", 0, colados)
    }

    @Test fun umbralSegunSensibilidad() {
        val yo = voz()
        val muestras = List(5) { dicho(yo, 0.8) }
        assertTrue(VozDueno.umbral(muestras, "alta") < VozDueno.umbral(muestras, "media"))
        assertTrue(VozDueno.umbral(muestras, "media") < VozDueno.umbral(muestras, "baja"))
        assertTrue(VozDueno.umbral(muestras, "alta") >= 0.2)
        assertEquals(0.0, VozDueno.umbral(muestras.take(1), "media"), 0.0)
    }

    @Test fun coseno() {
        assertEquals(1.0, VozDueno.coseno(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0)), 1e-9)
        assertEquals(0.0, VozDueno.coseno(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 3.0)), 1e-9)
        assertEquals(0.0, VozDueno.coseno(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 3.0)), 1e-9)
    }
}
