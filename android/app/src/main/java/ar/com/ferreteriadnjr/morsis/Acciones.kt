package ar.com.ferreteriadnjr.morsis

import android.Manifest
import android.app.SearchManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Cosas del celular que Morsis puede hacer por voz: música (Spotify / YouTube Music), llamar, WhatsApp y calendario.
 * Todo devuelve JSON (texto) para la página. Si falta un permiso, se pide en pantalla y se devuelve
 * {"error":"permiso"}: la página le dice al usuario que lo acepte y lo pida de nuevo.
 * Las llamadas y los WhatsApp los confirma la página antes (Morsis pregunta "¿Llamo a …?").
 */
class Acciones(private val act: MainActivity) {
    private val main = Handler(Looper.getMainLooper())

    private fun permiso(p: String): Boolean {
        if (act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return true
        main.post { act.requestPermissions(arrayOf(p), 2) }
        return false
    }
    private fun faltaPermiso(que: String) = JSONObject().put("error", "permiso").put("que", que).toString()
    private fun instalada(pkg: String) = try { act.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
    private fun abrir(i: Intent): Boolean = try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); main.post { act.startActivity(i) }; true } catch (_: Exception) { false }

    // ------------------------------------------------------------------ música
    fun musica(que: String): String {
        val apps = listOf("com.spotify.music" to "Spotify", "com.google.android.apps.youtube.music" to "YouTube Music")
        val app = apps.firstOrNull { instalada(it.first) }
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, que)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (app != null) i.setPackage(app.first)
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(i)
            // Algunas apps (Spotify sobre todo) abren la búsqueda pero no arrancan solas: si a los 3 s no suena nada,
            // se aprieta "play"; a los 7 s se avisa a la página si sonó o no.
            val am = act.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
            main.postDelayed({
                if (!am.isMusicActive) {
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                }
            }, 3000)
            main.postDelayed({
                act.puente?.emitir(JSONObject().put("tipo", "musica").put("sonando", am.isMusicActive).put("app", app?.second ?: ""))
            }, 7000)
            JSONObject().put("ok", true).put("app", app?.second ?: "la app de música").toString()
        } catch (_: Exception) {
            JSONObject().put("error", "sin_app").toString()
        }
    }

    /** pausar, seguir, siguiente, anterior, subir, bajar */
    fun media(accion: String): String {
        val am = act.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        fun tecla(k: Int) { am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k)) }
        when (accion) {
            "pausar" -> tecla(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "seguir" -> tecla(KeyEvent.KEYCODE_MEDIA_PLAY)
            "siguiente" -> tecla(KeyEvent.KEYCODE_MEDIA_NEXT)
            "anterior" -> tecla(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "subir" -> repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "bajar" -> repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            else -> return JSONObject().put("error", "accion").toString()
        }
        return JSONObject().put("ok", true).put("musica_sonando", am.isMusicActive).toString()
    }

    // ------------------------------------------------------------------ contactos y llamadas
    /** Los contactos del celular que más se parecen al nombre (hasta 3). */
    fun contactos(nombre: String): String {
        if (!permiso(Manifest.permission.READ_CONTACTS)) return faltaPermiso("contactos")
        val q = Palabra.normal(nombre)
        if (q.isBlank()) return JSONArray().toString()
        val todos = mutableListOf<Pair<String, String>>()
        act.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext()) todos.add((c.getString(0) ?: "") to (c.getString(1) ?: ""))
        }
        val r = todos.filter { it.second.isNotBlank() }
            .map { it to Contactos.puntaje(q, Palabra.normal(it.first)) }
            .filter { it.second >= 0.6 }
            .sortedByDescending { it.second }
            .distinctBy { it.first.second.filter(Char::isDigit).takeLast(8) }
            .take(3)
        return JSONArray(r.map { JSONObject().put("nombre", it.first.first).put("numero", it.first.second) }).toString()
    }

    fun llamar(numero: String): String {
        if (!permiso(Manifest.permission.CALL_PHONE)) return faltaPermiso("llamadas")
        val n = numero.filter { it.isDigit() || it == '+' }
        if (n.length < 6) return JSONObject().put("error", "numero").toString()
        return JSONObject().put("ok", abrir(Intent(Intent.ACTION_CALL, Uri.parse("tel:$n")))).toString()
    }

    /** Abre WhatsApp con el mensaje escrito: el envío lo confirma el usuario tocando "enviar". */
    fun whatsapp(numero: String, texto: String): String {
        val n = Contactos.internacional(numero) ?: return JSONObject().put("error", "numero").toString()
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { instalada(it) } ?: return JSONObject().put("error", "sin_whatsapp").toString()
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=" + Uri.encode(texto))).setPackage(pkg)
        return JSONObject().put("ok", abrir(i)).toString()
    }

    // ------------------------------------------------------------------ calendario
    fun eventos(desde: Long, hasta: Long): String {
        if (!permiso(Manifest.permission.READ_CALENDAR)) return faltaPermiso("calendario")
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, desde); android.content.ContentUris.appendId(it, hasta) }.build()
        val a = JSONArray()
        act.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
            null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && a.length() < 15) a.put(JSONObject().put("titulo", c.getString(0) ?: "(sin título)").put("inicio", c.getLong(1)).put("fin", c.getLong(2)).put("todo_el_dia", c.getInt(3) == 1))
        }
        return a.toString()
    }

    fun crearEvento(titulo: String, inicio: Long, fin: Long): String {
        if (!permiso(Manifest.permission.WRITE_CALENDAR)) return faltaPermiso("calendario")
        if (!permiso(Manifest.permission.READ_CALENDAR)) return faltaPermiso("calendario")
        var cal = -1L
        act.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            CalendarContract.Calendars.VISIBLE + " = 1 AND " + CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= " + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR, null, null)?.use { c ->
            while (c.moveToNext()) { if (cal < 0 || c.getInt(1) == 1) cal = c.getLong(0) }
        }
        if (cal < 0) return JSONObject().put("error", "sin_calendario").toString()
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal); put(CalendarContract.Events.TITLE, titulo)
            put(CalendarContract.Events.DTSTART, inicio); put(CalendarContract.Events.DTEND, maxOf(fin, inicio + 15 * 60_000))
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = act.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return JSONObject().put("error", "no_se_pudo").toString()
        try {
            act.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, uri.lastPathSegment?.toLong()); put(CalendarContract.Reminders.MINUTES, 30)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        } catch (_: Exception) { }
        return JSONObject().put("ok", true).toString()
    }
}

/** Matemática de contactos (sin Android, para probarla en la compu). */
object Contactos {
    /** 0 a 1: qué tanto se parece lo pedido a un nombre de contacto (por palabra, tolerando errores). */
    fun puntaje(pedido: String, nombre: String): Double {
        if (nombre.isBlank()) return 0.0
        if (nombre == pedido) return 1.0
        if (nombre.contains(pedido) && pedido.length >= 3) return 0.95
        val ps = pedido.split(' ').filter { it.length >= 2 }
        val ns = nombre.split(' ').filter { it.isNotEmpty() }
        if (ps.isEmpty() || ns.isEmpty()) return 0.0
        val porPalabra = ps.map { p -> ns.maxOf { n ->
            val d = Palabra.distancia(p, n)
            if (n.startsWith(p) && p.length >= 3) 0.9 else 1.0 - d.toDouble() / maxOf(p.length, n.length)
        } }
        return porPalabra.average()
    }

    /** Número argentino a formato de WhatsApp (549 + característica + número, sin 0 ni 15). null si no se entiende. */
    fun internacional(numero: String): String? {
        var d = numero.filter { it.isDigit() }
        if (numero.trim().startsWith("+") || d.startsWith("54")) {
            if (!d.startsWith("54")) return if (d.length >= 8) d else null
            d = d.removePrefix("54").removePrefix("9")
        }
        d = d.removePrefix("0")
        if (d.length == 12) {          // con 15: característica de 2, 3 o 4 cifras + 15 + número
            for (k in 2..4) if (d.substring(k, k + 2) == "15") { d = d.substring(0, k) + d.substring(k + 2); break }
        }
        if (d.length == 10 && d.startsWith("15")) d = "11" + d.substring(2)    // celular sin característica: se toma AMBA
        if (d.length == 8) d = "11$d"                                           // fijo/celular de 8 cifras sin característica
        return if (d.length == 10) "549$d" else null
    }
}
