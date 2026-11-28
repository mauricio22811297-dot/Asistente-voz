package ar.com.ferreteriadnjr.morsis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactosTest {
    @Test fun numerosArgentinosParaWhatsapp() {
        assertEquals("5491155551234", Contactos.internacional("011 15 5555-1234"))
        assertEquals("5491155551234", Contactos.internacional("+54 9 11 5555-1234"))
        assertEquals("5491155551234", Contactos.internacional("11 5555 1234"))
        assertEquals("5491155551234", Contactos.internacional("15-5555-1234"))
        assertEquals("5492215551234", Contactos.internacional("0221 15 555-1234"))
        assertEquals("5493514551234", Contactos.internacional("0351 4551234"))
        assertNull(Contactos.internacional("123"))
    }

    @Test fun parecidoDeNombres() {
        assertTrue(Contactos.puntaje("manzorro", "distribuidora manzorro") >= 0.9)
        assertTrue(Contactos.puntaje("juan perez", "juan perez") == 1.0)
        assertTrue(Contactos.puntaje("manzoro", "manzorro") >= 0.8)
        assertTrue(Contactos.puntaje("manzorro", "maria gomez") < 0.6)
    }
}
