package ar.com.ferreteriadnjr.morsis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject

/**
 * Dictado con el reconocedor de voz de Android (el mismo de Google), porque dentro de una app el navegador no lo trae.
 * La página lo usa como si fuera el SpeechRecognition de Chrome (ver nativo.js). Mientras dicta, el detector de
 * "Morsis" suelta el micrófono para no pisarse.
 */
class Dictado(private val ctx: Context, private val puente: Puente) {
    private var rec: SpeechRecognizer? = null
    private var id = ""

    fun empezar(nuevo: String, lang: String) {
        if (rec != null) cancelar()
        id = nuevo
        ServicioPalabra.instancia?.enUso(true)
        // Un instante para que el detector termine de soltar el micrófono.
        puente.main.postDelayed({ if (id == nuevo) arrancar(nuevo, lang) }, 300)
    }

    private fun arrancar(esteId: String, lang: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            evento(esteId, "error", error = "service-not-allowed"); terminar(esteId); return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { evento(esteId, "start") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (t.isNotBlank()) evento(esteId, "result", texto = t, final = false)
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (t.isNotBlank()) evento(esteId, "result", texto = t, final = true)
                terminar(esteId)
            }
            override fun onError(error: Int) {
                val e = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "network"
                    SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
                    else -> "aborted"
                }
                evento(esteId, "error", error = e)
                terminar(esteId)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try { r.startListening(i) } catch (e: Exception) { evento(esteId, "error", error = "audio-capture"); terminar(esteId) }
    }

    private fun terminar(esteId: String) {
        if (esteId != id) return
        evento(esteId, "end")
        try { rec?.destroy() } catch (_: Exception) { }
        rec = null
        id = ""
        ServicioPalabra.instancia?.enUso(false)
    }

    fun parar() { try { rec?.stopListening() } catch (_: Exception) { } }

    fun cancelar() {
        val esteId = id
        if (esteId.isEmpty()) return
        try { rec?.cancel() } catch (_: Exception) { }
        evento(esteId, "error", error = "aborted")
        terminar(esteId)
    }

    private fun evento(esteId: String, ev: String, texto: String = "", final: Boolean = false, error: String = "") {
        puente.emitir(JSONObject().put("tipo", "rec").put("id", esteId).put("ev", ev)
            .put("texto", texto).put("final", final).put("error", error))
    }
}
