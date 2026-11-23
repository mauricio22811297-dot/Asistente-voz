package ar.com.ferreteriadnjr.morsis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Escucha "Morsis" TODO EL TIEMPO, aunque la pantalla esté apagada (servicio en primer plano, con el aviso fijo
 * "Morsis escuchando"). Usa Vosk, un reconocedor que funciona sin internet y no hace pitidos. Al oír la palabra,
 * prende la pantalla, abre Morsis y le avisa a la página para que tome el pedido.
 */
class ServicioPalabra : Service(), RecognitionListener {

    companion object {
        @Volatile var instancia: ServicioPalabra? = null
        private const val CANAL = "morsis_escucha"
        private const val CANAL_DESPERTAR = "morsis_despertar"
        private const val ID_FIJA = 1
        private const val ID_DESPERTAR = 2
        private val MODELOS = listOf(
            "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip",
            "https://huggingface.co/localstack/vosk-models/resolve/main/vosk-model-small-es-0.42.zip"
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var modelo: Model? = null
    private var reconocedor: Recognizer? = null
    private var escucha: SpeechService? = null
    private var usos = 0               // dictado o voz en curso: el micrófono lo usa otro
    private var pausaHasta = 0L        // después de despertar, hasta que la página tome el pedido
    private var pausadoPorUsuario = false
    private var palabra = Palabra("Morsis", emptyList())
    private var estadoTexto = "Preparando…"
    private var ultimoParcial = ""
    private var ultimoDespertar = 0L
    private var frases: List<String> = emptyList()   // modo estricto: solo estas frases (vacío = modo aproximado)
    // Modo "tu voz" (MFCC + DTW): con 3 o más muestras grabadas se usa este en vez de Vosk.
    private var muestras: List<Array<DoubleArray>> = emptyList()
    private var umbralVoz = 0.0
    private var grabandoHasta = 0L
    private var detectorVoz: DetectorVozPropia? = null
    private fun usarVozPropia() = muestras.size >= 3
    private fun grabando() = System.currentTimeMillis() < grabandoHasta

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        crearCanales()
        palabra = Palabra.leer(this)
        recargarMuestras()
        val n = notificacion("Preparando el detector…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_FIJA, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(ID_FIJA, n)
        thread { prepararModelo() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "pausar" -> { pausadoPorUsuario = true; aplicar() }
            "seguir" -> { pausadoPorUsuario = false; aplicar() }
            "detener" -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instancia = null
        detenerEscucha()
        try { modelo?.close() } catch (_: Exception) { }
        super.onDestroy()
    }

    /** Cambió la palabra o la sensibilidad: se rearma el reconocedor con la gramática nueva. */
    fun recargarPalabra() { main.post { palabra = Palabra.leer(this); recargarMuestras(); detenerEscucha(); aplicar() } }

    fun cuantasMuestras() = muestras.size

    private fun recargarMuestras() {
        muestras = VozPropia.leer(filesDir)
        umbralVoz = VozPropia.umbral(muestras, palabra.sensibilidad)
    }

    /** La próxima vez que hables (en 15 s) se guarda como muestra de tu palabra. */
    fun grabarMuestra() {
        main.post {
            grabandoHasta = System.currentTimeMillis() + 15_000
            detenerEscucha(); aplicar()
            avisarMuestra(false, "Decí «" + palabraVisible() + "» ahora…", grabando = true)
            main.postDelayed({ if (grabandoHasta != 0L && !grabando()) { grabandoHasta = 0L; avisarMuestra(false, "No te escuché. Probá de nuevo, más cerca."); detenerEscucha(); aplicar() } }, 15_200)
        }
    }

    fun borrarMuestras() { main.post { VozPropia.borrar(filesDir); recargarMuestras(); detenerEscucha(); aplicar() } }

    private fun avisarMuestra(ok: Boolean, texto: String, grabando: Boolean = false) {
        MainActivity.actual?.puente?.emitir(JSONObject().put("tipo", "muestra").put("ok", ok).put("texto", texto)
            .put("total", muestras.size).put("grabando", grabando))
    }

    /** Pedazo de voz del modo "tu voz" (llega desde el hilo del micrófono). */
    private fun alPedazo(audio: ShortArray) {
        val limpio = VozPropia.recortar(audio)
        val seg = VozPropia.duracion(limpio)
        if (grabando()) {
            if (seg < 0.3 || seg > 1.8) {
                avisarMuestra(false, if (seg < 0.3) "Muy corto: decí la palabra completa." else "Muy largo: decí solo «" + palabraVisible() + "», sin nada más.", grabando = true)
                return
            }
            VozPropia.guardar(filesDir, VozPropia.mfcc(limpio))
            main.post {
                grabandoHasta = 0L
                recargarMuestras()
                val faltan = maxOf(0, 5 - muestras.size)
                avisarMuestra(true, if (faltan > 0) "Grabada (" + muestras.size + "). " + (if (muestras.size >= 3) "Ya funciona; con $faltan más anda mejor." else "Faltan $faltan.") else "¡Listo! Ya reconoce tu voz.")
                detenerEscucha(); aplicar()
            }
            return
        }
        if (!usarVozPropia() || seg < 0.25) return
        val huella = VozPropia.mfcc(limpio, restarMedia = false)
        var mejor = Double.MAX_VALUE
        for (m in muestras) mejor = minOf(mejor, VozPropia.dtw(m, huella, prefijo = true))
        val parecido = if (mejor == Double.MAX_VALUE) 0 else Math.round(umbralVoz / mejor * 100).toInt()
        oido("🔊 parecido $parecido% (se activa desde 100%)", false)
        if (mejor <= umbralVoz) main.post { despertar() }
    }


    /** Dictado o voz en curso (true) o terminado (false): mientras tanto se suelta el micrófono. */
    fun enUso(si: Boolean) {
        main.post {
            usos = if (si) usos + 1 else maxOf(0, usos - 1)
            if (!si) pausaHasta = 0L
            aplicar()
        }
    }

    // ------------------------------------------------------------------ modelo (se baja una sola vez)
    private fun prepararModelo() {
        val dir = File(filesDir, "modelo-es")
        try {
            if (!File(dir, "listo").exists()) {
                var error: Exception? = null
                var ok = false
                for (u in MODELOS) {
                    try {
                        dir.deleteRecursively(); dir.mkdirs()
                        bajar(u, dir)
                        ok = true; break
                    } catch (e: Exception) { error = e }
                }
                if (!ok) throw error ?: Exception("no se pudo bajar")
                File(dir, "listo").writeText("1")
            }
            avisar("Cargando el detector…")
            val m = Model(dir.absolutePath)
            main.post { modelo = m; aplicar() }
        } catch (e: Exception) {
            avisar("No pude preparar el detector (" + (e.message ?: "error") + "). Reintento en un minuto.")
            main.postDelayed({ thread { prepararModelo() } }, 60_000)
        }
    }

    private fun bajar(url: String, dir: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
        val total = c.contentLengthLong
        var leido = 0L
        var ultimoAviso = -1
        val contando = object : FilterInputStream(BufferedInputStream(c.inputStream)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    leido += n
                    val pct = if (total > 0) (leido * 100 / total).toInt() else -1
                    if (pct != ultimoAviso && (pct % 5 == 0 || pct < 0)) {
                        ultimoAviso = pct
                        avisar(if (pct >= 0) "Bajando el detector: $pct% (una sola vez)" else "Bajando el detector… " + (leido / 1_000_000) + " MB")
                    }
                }
                return n
            }
        }
        val raiz = dir.canonicalPath
        ZipInputStream(contando as InputStream).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val nombre = e.name.substringAfter('/', "")   // "vosk-model-small-es-0.42/am/final.mdl" → "am/final.mdl"
                if (nombre.isNotEmpty()) {
                    val f = File(dir, nombre)
                    if (!f.canonicalPath.startsWith(raiz)) throw SecurityException("ruta inválida en el zip")
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                }
                e = z.nextEntry
            }
        }
        if (!File(dir, "am").exists() && !File(dir, "conf").exists()) throw Exception("el archivo bajado no es el modelo")
    }

    // ------------------------------------------------------------------ escucha
    private fun permiso() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun aplicar() {
        val ahora = System.currentTimeMillis()
        val debe = (modelo != null || usarVozPropia() || grabando()) && usos == 0 && !pausadoPorUsuario && ahora >= pausaHasta && permiso()
        if (debe) iniciarEscucha() else detenerEscucha()
        when {
            grabando() -> {}
            debe && usarVozPropia() -> avisar("Escuchando «" + palabraVisible() + "» con tu voz (" + muestras.size + " muestras)")
            modelo == null -> {}
            !permiso() -> avisar("Falta el permiso del micrófono: abrí Morsis.")
            pausadoPorUsuario -> avisar("En pausa. Tocá «Seguir» para que vuelva a escuchar.")
            debe && escucha != null -> avisar(if (frases.isNotEmpty()) "Escuchando «" + palabraVisible() + "» (modo estricto)"
                else "Escuchando «" + palabraVisible() + "» (modo aproximado: el detector no conoce esa palabra, elegí otra en Ajustes)")
        }
        if (ahora < pausaHasta) main.postDelayed({ aplicar() }, pausaHasta - ahora + 50)
    }

    /** Palabras que conoce el detector (graph/words.txt del modelo). null si no se puede leer: modo aproximado. */
    private var vocabularioCache: Set<String>? = null
    private fun vocabulario(): Set<String>? {
        vocabularioCache?.let { return it }
        return try {
            val f = File(File(filesDir, "modelo-es"), "graph/words.txt")
            if (!f.exists()) return null
            val set = HashSet<String>(60000)
            f.forEachLine { linea -> val w = linea.substringBefore(' ').trim(); if (w.isNotEmpty()) set.add(w) }
            vocabularioCache = set
            set
        } catch (_: Exception) { null }
    }

    private fun palabraVisible() = getSharedPreferences("morsis", MODE_PRIVATE).getString("palabra", "Morsis") ?: "Morsis"

    private fun iniciarEscucha() {
        if (usarVozPropia() || grabando()) {
            if (escucha != null) detenerVosk()
            if (detectorVoz == null) {
                val d = DetectorVozPropia({ alPedazo(it) }, { e -> avisar("No pude escuchar: $e. Reintento…"); main.postDelayed({ detenerEscucha(); aplicar() }, 5000) })
                detectorVoz = d
                d.iniciar()
            }
            return
        }
        if (escucha != null) return
        val m = modelo ?: return
        try {
            // Modo estricto: el detector solo puede "oír" la palabra elegida (o "[unk]" para todo lo demás).
            // Así casi no se equivoca con otras palabras y la reconoce mejor. Si la palabra no está en su
            // diccionario, se usa el modo aproximado (escucha todo y compara).
            val voc = vocabulario()
            frases = if (voc == null) emptyList() else palabra.frasesEstrictas { w -> voc.contains(w) }
            val r = if (frases.isNotEmpty()) Recognizer(m, 16000.0f, JSONArray(frases + "[unk]").toString()) else Recognizer(m, 16000.0f)
            r.setWords(true)
            val s = SpeechService(r, 16000.0f)
            s.startListening(this)
            reconocedor = r; escucha = s
        } catch (e: Exception) {
            avisar("No pude abrir el micrófono. Reintento…")
            main.postDelayed({ aplicar() }, 5000)
        }
    }

    private fun detenerEscucha() {
        detectorVoz?.let { d -> detectorVoz = null; d.detener() }
        detenerVosk()
    }

    private fun detenerVosk() {
        val s = escucha ?: return
        escucha = null
        try { s.stop(); s.shutdown() } catch (_: Exception) { }
        try { reconocedor?.close() } catch (_: Exception) { }
        reconocedor = null
        ultimoParcial = ""
    }

    private fun texto(json: String?, campo: String): String = try { JSONObject(json ?: "{}").optString(campo, "") } catch (_: Exception) { "" }

    override fun onPartialResult(hypothesis: String?) {
        val t = texto(hypothesis, "partial")
        if (t.isEmpty() || t == ultimoParcial) return
        ultimoParcial = t
        oido(t, false)
        // En modo estricto se espera el resultado final (trae la confianza); en el aproximado, se reacciona ya.
        if (frases.isEmpty() && palabra.detecta(t)) despertar()
    }

    override fun onResult(hypothesis: String?) {
        val t = texto(hypothesis, "text"); ultimoParcial = ""
        if (t.isEmpty() || t == "[unk]") return
        oido(t, true)
        if (frases.isEmpty()) { if (palabra.detecta(t)) despertar(); return }
        if (!palabra.esFrase(t, frases)) return
        // Confianza de las palabras de la frase: si es baja, era otra cosa parecida.
        val conf = confianza(hypothesis)
        if (conf < 0 || conf >= palabra.confianzaMinima) despertar()
        else oido("$t (no estoy seguro: " + Math.round(conf * 100) + "%)", false)
    }

    /** Confianza promedio de las palabras que no son "[unk]" (-1 si no viene). */
    private fun confianza(json: String?): Double {
        return try {
            val a = JSONObject(json ?: "{}").optJSONArray("result") ?: return -1.0
            var suma = 0.0; var n = 0
            for (i in 0 until a.length()) {
                val w = a.getJSONObject(i)
                if (w.optString("word") == "[unk]") continue
                suma += w.optDouble("conf", 1.0); n++
            }
            if (n == 0) -1.0 else suma / n
        } catch (_: Exception) { -1.0 }
    }

    override fun onFinalResult(hypothesis: String?) { onResult(hypothesis) }
    override fun onError(exception: Exception?) { main.post { detenerEscucha(); main.postDelayed({ aplicar() }, 2000) } }
    override fun onTimeout() { main.post { detenerEscucha(); aplicar() } }

    /** Lo que va oyendo se muestra en la página ("Escuché: …") y sirve para "Enseñarle cómo digo Morsis". */
    private fun oido(t: String, final: Boolean) {
        MainActivity.actual?.puente?.emitir(JSONObject().put("tipo", "oido").put("texto", t).put("final", final))
    }

    private fun despertar() {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoDespertar < 2500) return
        ultimoDespertar = ahora
        // Se suelta el micrófono para el dictado; si la página no lo toma en 15 s, se vuelve a escuchar.
        pausaHasta = ahora + 15_000
        main.post { aplicar() }
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "morsis:despertar")
                .acquire(10_000)
        } catch (_: Exception) { }
        val act = MainActivity.actual
        if (act != null) act.puente?.emitir(JSONObject().put("tipo", "despertar"))
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (act == null) i.putExtra("despertar", true)
        // Con la pantalla apagada Android solo deja abrir la app con un aviso de "pantalla completa" (como una llamada).
        val pi = PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CANAL_DESPERTAR)
            .setSmallIcon(R.drawable.ic_mic).setContentTitle("Morsis").setContentText("Te escucho…")
            .setCategory(Notification.CATEGORY_CALL).setFullScreenIntent(pi, true).setAutoCancel(true)
            .setTimeoutAfter(8000).build()
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_DESPERTAR, n) } catch (_: Exception) { }
        try { startActivity(i) } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ avisos
    fun avisarEstado() { avisar(estadoTexto) }

    private fun avisar(t: String) {
        estadoTexto = t
        main.post {
            try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_FIJA, notificacion(t)) } catch (_: Exception) { }
            MainActivity.actual?.puente?.emitir(JSONObject().put("tipo", "estado").put("texto", t))
        }
    }

    private fun crearCanales() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CANAL, "Morsis escuchando", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CANAL_DESPERTAR, "Morsis te escucha", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val accion = if (pausadoPorUsuario) "seguir" else "pausar"
        val pi = PendingIntent.getService(this, 1, Intent(this, ServicioPalabra::class.java).setAction(accion),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_mic).setContentTitle("Morsis").setContentText(texto)
            .setOngoing(true).setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, if (pausadoPorUsuario) "Seguir" else "Pausar", pi).build())
            .build()
    }
}
