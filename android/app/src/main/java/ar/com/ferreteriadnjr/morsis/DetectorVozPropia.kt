package ar.com.ferreteriadnjr.morsis

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Escucha el micrófono y corta "pedazos" de voz (cuando el sonido sube por encima del ruido del local y vuelve a
 * bajar). Cada pedazo se entrega para comparar con las muestras (o para guardarlo como muestra nueva).
 */
class DetectorVozPropia(private val alPedazo: (ShortArray) -> Unit, private val alError: (String) -> Unit) {
    @Volatile private var corriendo = false
    private var hilo: Thread? = null

    @SuppressLint("MissingPermission")
    fun iniciar() {
        if (corriendo) return
        corriendo = true
        hilo = thread(name = "morsis-voz") {
            val min = AudioRecord.getMinBufferSize(VozPropia.TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, VozPropia.TASA, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(min, VozPropia.TASA))
            } catch (e: Exception) { alError("no pude abrir el micrófono"); corriendo = false; return@thread }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { alError("el micrófono está ocupado"); rec.release(); corriendo = false; return@thread }
            rec.startRecording()
            val paso = ShortArray(160)                 // 10 ms
            val previo = ArrayDeque<ShortArray>()      // 300 ms antes de que empiece la voz
            val pedazo = ArrayList<ShortArray>()
            var piso = 200.0                           // ruido de fondo (se va ajustando)
            var hablando = false
            var silencio = 0
            var voz = 0
            try {
                while (corriendo) {
                    var leidos = 0
                    while (leidos < paso.size && corriendo) { val n = rec.read(paso, leidos, paso.size - leidos); if (n <= 0) break; leidos += n }
                    if (!corriendo) break
                    var s = 0.0
                    for (x in paso) s += x.toDouble() * x
                    val rms = sqrt(s / paso.size)
                    val copia = paso.copyOf()
                    if (!hablando) {
                        previo.addLast(copia); if (previo.size > 30) previo.removeFirst()
                        if (rms > max(piso * 2.5, 180.0)) {   // más sensible: también cuando hablás a un par de metros
                            voz++
                            if (voz >= 5) {                // 50 ms seguidos de voz: empieza un pedazo
                                hablando = true; silencio = 0
                                pedazo.clear(); pedazo.addAll(previo); previo.clear()
                            }
                        } else {
                            voz = 0
                            piso = piso * 0.97 + rms * 0.03
                        }
                    } else {
                        pedazo.add(copia)
                        silencio = if (rms < max(piso * 1.6, 130.0)) silencio + 1 else 0
                        // Termina con 0,3 s de silencio, o a los 2 s (si siguió hablando: "Morsis, qué hora es…").
                        if (silencio >= 30 || pedazo.size >= 200) {
                            val total = ShortArray(pedazo.sumOf { it.size })
                            var i = 0
                            for (p in pedazo) { System.arraycopy(p, 0, total, i, p.size); i += p.size }
                            pedazo.clear(); hablando = false; voz = 0
                            alPedazo(total)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) { }
                rec.release()
            }
        }
    }

    fun detener() {
        corriendo = false
        try { hilo?.join(500) } catch (_: Exception) { }
        hilo = null
    }

    val activo get() = corriendo
}
