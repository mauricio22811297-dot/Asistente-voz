package ar.com.ferreteriadnjr.morsis

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * Radios por internet. Suena desde la app (no desde la página), así sigue con la pantalla apagada.
 * Mientras Morsis escucha o habla, la radio baja el volumen ("bajar") y después vuelve.
 */
object Radio {
    private val main = Handler(Looper.getMainLooper())
    private var jugador: MediaPlayer? = null
    @Volatile var nombre: String = ""; private set
    @Volatile var sonando = false; private set
    private var bajada = false
    private val volver = Runnable { if (bajada) { bajada = false; jugador?.setVolume(1f, 1f) } }

    /** al: se llama con "ok" cuando arranca o con el error. */
    fun poner(url: String, quien: String, al: (String) -> Unit) {
        main.post {
            parar()
            nombre = quien
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                mp.setDataSource(url)
                mp.setOnPreparedListener { it.start(); sonando = true; al("ok") }
                mp.setOnErrorListener { _, q, e -> sonando = false; al("error $q/$e"); parar(); true }
                mp.setOnCompletionListener { sonando = false }
                jugador = mp
                mp.prepareAsync()
                main.postDelayed({ if (jugador === mp && !sonando) { al("no arrancó"); parar() } }, 20_000)
            } catch (e: Exception) { al("error " + (e.message ?: "")); parar() }
        }
    }

    fun parar() {
        main.post {
            try { jugador?.stop() } catch (_: Exception) { }
            try { jugador?.release() } catch (_: Exception) { }
            jugador = null; sonando = false; bajada = false
        }
    }

    /** true: baja el volumen (Morsis está escuchando o hablando). false: vuelve en 2 segundos. */
    fun bajar(si: Boolean) {
        main.post {
            main.removeCallbacks(volver)
            if (si) { if (jugador != null) { bajada = true; jugador?.setVolume(0.15f, 0.15f) } }
            else main.postDelayed(volver, 2000)
        }
    }
}
