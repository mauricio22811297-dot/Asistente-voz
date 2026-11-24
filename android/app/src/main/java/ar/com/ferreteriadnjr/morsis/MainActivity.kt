package ar.com.ferreteriadnjr.morsis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Pantalla de Morsis: la misma página de siempre (GitHub Pages) dentro de la app, más lo que el navegador no deja
 * hacer (escuchar con la pantalla apagada, dictado y voz del celu). Ver Puente.kt y nativo.js.
 */
class MainActivity : Activity() {

    companion object {
        const val DIRECCION = "https://mauricio22811297-dot.github.io/Asistente-voz/?app=1"
        const val HOST = "mauricio22811297-dot.github.io"
        @Volatile var actual: MainActivity? = null
    }

    private lateinit var web: WebView
    var puente: Puente? = null
    private var quiereEscuchar = false
    private var archivoCallback: ValueCallback<Array<Uri>>? = null
    private var fotoUri: Uri? = null
    private var fotoArchivo: java.io.File? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        web = WebView(this)
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setGeolocationEnabled(true)
        s.userAgentString = s.userAgentString + " MorsisApp/" + BuildConfig.VERSION_NAME
        val p = Puente(this, web)
        puente = p
        web.addJavascriptInterface(p, "MorsisNativo")
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { runOnUiThread { request.grant(request.resources) } }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, true, false) }
            // Botón 📷 de la página: abre la cámara (o la galería) y le devuelve la foto.
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                archivoCallback?.onReceiveValue(null)
                archivoCallback = callback
                return abrirCamaraOGaleria(params)
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == HOST) return false
                // Mapas, links del sistema, etc.: se abren afuera (Google Maps, Chrome).
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { }
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { puente?.paginaEmpezo() }
        }
        actual = this
        pedirPermisos()
        web.loadUrl(DIRECCION)
        if (intent?.getBooleanExtra("despertar", false) == true) p.emitir(JSONObject().put("tipo", "despertar"))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        quitarAvisoDespertar()
        if (intent?.getBooleanExtra("despertar", false) == true) puente?.emitir(JSONObject().put("tipo", "despertar"))
    }

    override fun onResume() {
        super.onResume()
        quitarAvisoDespertar()
        // Si el permiso del micrófono se dio recién, se arranca la escucha que la página había pedido.
        if (quiereEscuchar) escucharPalabra(true)
    }

    override fun onDestroy() {
        if (actual === this) actual = null
        puente?.voz?.cerrar()
        web.destroy()
        super.onDestroy()
    }

    private fun abrirCamaraOGaleria(params: WebChromeClient.FileChooserParams): Boolean {
        val intents = mutableListOf<Intent>()
        try {
            val dir = java.io.File(cacheDir, "fotos").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }   // solo la última foto
            val f = java.io.File(dir, "foto_" + System.currentTimeMillis() + ".jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fotos", f)
            fotoArchivo = f; fotoUri = uri
            intents.add(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { fotoUri = null; fotoArchivo = null }
        val galeria = try { params.createIntent() } catch (_: Exception) { Intent(Intent.ACTION_GET_CONTENT).setType("image/*") }
        val elegir = Intent.createChooser(galeria, "Foto para Morsis").putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
        return try { @Suppress("DEPRECATION") startActivityForResult(elegir, 7); true }
        catch (_: Exception) { archivoCallback?.onReceiveValue(null); archivoCallback = null; false }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7) return
        val cb = archivoCallback ?: return
        archivoCallback = null
        var resultado: Array<Uri>? = null
        if (resultCode == RESULT_OK) {
            val elegida = data?.data
            resultado = when {
                elegida != null -> arrayOf(elegida)                                         // de la galería
                fotoArchivo?.let { it.exists() && it.length() > 0 } == true -> arrayOf(fotoUri!!)   // de la cámara
                else -> null
            }
        }
        cb.onReceiveValue(resultado)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true) // no se cierra: sigue escuchando
    }

    private fun quitarAvisoDespertar() {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(2) } catch (_: Exception) { }
    }

    private fun pedirPermisos() {
        val faltan = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) faltan.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) faltan.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            faltan.add(Manifest.permission.ACCESS_FINE_LOCATION); faltan.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (faltan.isNotEmpty()) requestPermissions(faltan.toTypedArray(), 1)
        else pedirBateria(false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (quiereEscuchar) escucharPalabra(true)
        pedirBateria(false)
    }

    /** Samsung "duerme" las apps para ahorrar batería y cortaría la escucha: se pide dejar a Morsis sin restricción. */
    fun pedirBateria(siempre: Boolean) {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!siempre && pm.isIgnoringBatteryOptimizations(packageName)) return
            if (!siempre && getSharedPreferences("morsis", MODE_PRIVATE).getBoolean("bateriaPedida", false)) return
            getSharedPreferences("morsis", MODE_PRIVATE).edit().putBoolean("bateriaPedida", true).apply()
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (_: Exception) { }
    }

    /** La página pide escuchar "Morsis" (manos libres activado) o dejar de hacerlo. */
    fun escucharPalabra(activo: Boolean) {
        quiereEscuchar = activo
        val i = Intent(this, ServicioPalabra::class.java)
        if (!activo) { stopService(i); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (ServicioPalabra.instancia != null) { startService(i.setAction("seguir")); return }
        try { startForegroundService(i) } catch (_: Exception) { }
    }

    fun pantallaEncendida(si: Boolean) {
        if (si) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
