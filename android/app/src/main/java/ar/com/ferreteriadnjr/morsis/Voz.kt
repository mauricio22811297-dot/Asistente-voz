package ar.com.ferreteriadnjr.morsis

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.util.Locale

/** Voz de las respuestas con el lector de Android (TextToSpeech). Mientras habla, el detector no escucha. */
class Voz(ctx: Context, private val puente: Puente) : TextToSpeech.OnInitListener {
    private var estado = 0 // 0 = preparando, 1 = lista, -1 = no hay voz
    private val cola = mutableListOf<Triple<String, String, String>>()
    private val hablando = mutableSetOf<String>()
    private val tts = TextToSpeech(ctx.applicationContext, this)

    override fun onInit(status: Int) {
        puente.main.post {
            estado = if (status == TextToSpeech.SUCCESS) 1 else -1
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { puente.main.post { fin(utteranceId) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { puente.main.post { fin(utteranceId) } }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { puente.main.post { fin(utteranceId) } }
            })
            val pendientes = cola.toList(); cola.clear()
            pendientes.forEach { hablar(it.first, it.second, it.third) }
        }
    }

    fun hablar(id: String, texto: String, lang: String) {
        if (estado == 0) { cola.add(Triple(id, texto, lang)); return }
        if (estado < 0 || texto.isBlank()) { avisarFin(id); return }
        hablando.add(id)
        ServicioPalabra.instancia?.enUso(true)
        try { tts.language = Locale.forLanguageTag(lang.ifBlank { "es-AR" }) } catch (_: Exception) { }
        if (tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) fin(id)
    }

    fun callar() {
        cola.clear()
        try { tts.stop() } catch (_: Exception) { }
        hablando.toList().forEach { fin(it) }
    }

    private fun fin(id: String?) {
        if (id == null || !hablando.remove(id)) return
        ServicioPalabra.instancia?.enUso(false)
        avisarFin(id)
    }

    private fun avisarFin(id: String) { puente.emitir(JSONObject().put("tipo", "tts").put("id", id).put("ev", "end")) }

    fun cerrar() { try { tts.shutdown() } catch (_: Exception) { } }
}
