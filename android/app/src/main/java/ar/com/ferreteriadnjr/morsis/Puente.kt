package ar.com.ferreteriadnjr.morsis

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * Lo que la página de Morsis puede pedirle a la app (window.MorsisNativo) y cómo la app le avisa cosas a la página
 * (window.__morsisNativo(evento)). Solo la página de Morsis se carga en la app (ver MainActivity).
 */
class Puente(private val act: MainActivity, private val web: WebView) {
    val main = Handler(Looper.getMainLooper())
    val dictado = Dictado(act, this)
    val voz = Voz(act, this)
    @Volatile var paginaLista = false
    private val pendientes = mutableListOf<String>()

    fun emitir(o: JSONObject) {
        val t = o.toString()
        main.post {
            if (!paginaLista) { pendientes.add(t); return@post }
            web.evaluateJavascript("window.__morsisNativo&&window.__morsisNativo($t)", null)
        }
    }

    fun paginaEmpezo() { paginaLista = false }

    @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface fun listo() {
        main.post {
            paginaLista = true
            val lista = pendientes.toList(); pendientes.clear()
            lista.forEach { web.evaluateJavascript("window.__morsisNativo&&window.__morsisNativo($it)", null) }
            ServicioPalabra.instancia?.avisarEstado()
        }
    }

    @JavascriptInterface fun dictar(id: String, lang: String) { main.post { dictado.empezar(id, lang) } }
    @JavascriptInterface fun pararDictado() { main.post { dictado.parar() } }
    @JavascriptInterface fun cancelarDictado() { main.post { dictado.cancelar() } }
    @JavascriptInterface fun hablar(id: String, texto: String, lang: String) { main.post { voz.hablar(id, texto, lang) } }
    @JavascriptInterface fun callar() { main.post { voz.callar() } }
    @JavascriptInterface fun escucharPalabra(activo: Boolean) { main.post { act.escucharPalabra(activo) } }
    @JavascriptInterface fun configurar(json: String) { Palabra.guardar(act, json); ServicioPalabra.instancia?.recargarPalabra() }
    @JavascriptInterface fun pantallaEncendida(si: Boolean) { main.post { act.pantallaEncendida(si) } }
    @JavascriptInterface fun grabarMuestra() {
        val sv = ServicioPalabra.instancia
        if (sv == null) emitir(JSONObject().put("tipo", "muestra").put("ok", false).put("texto", "Primero activá «Manos libres».").put("total", 0))
        else sv.grabarMuestra()
    }
    @JavascriptInterface fun borrarMuestras() { ServicioPalabra.instancia?.borrarMuestras() ?: VozPropia.borrar(act.filesDir) }
    @JavascriptInterface fun cuantasMuestras(): Int = ServicioPalabra.instancia?.cuantasMuestras() ?: VozPropia.leer(act.filesDir).size
    @JavascriptInterface fun bateria() { main.post { act.pedirBateria(true) } }
}
