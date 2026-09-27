package app.orionmd.btvoicetype

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

private const val TAG = "VoskDictation"
private const val SAMPLE_RATE = 16000.0f

/**
 * Loads the bundled Vosk model exactly once per process and hands back the shared instance.
 * The model lives in app assets under "model-en-us-small" and is unpacked to internal storage
 * on first use (StorageService.unpack) — after that, loading is instant.
 */
object VoskEngine {
    @Volatile private var model: Model? = null
    @Volatile private var loading = false
    private val pendingReady = mutableListOf<(Model) -> Unit>()
    private val pendingError = mutableListOf<(String) -> Unit>()

    @Synchronized
    fun ensureModelLoaded(context: Context, onReady: (Model) -> Unit, onError: (String) -> Unit) {
        val existing = model
        if (existing != null) {
            onReady(existing)
            return
        }
        pendingReady.add(onReady)
        pendingError.add(onError)
        if (loading) return
        loading = true

        StorageService.unpack(
            context.applicationContext,
            "model-en-us-small",
            "model",
            { loadedModel: Model ->
                synchronized(this) {
                    model = loadedModel
                    loading = false
                    pendingReady.forEach { it(loadedModel) }
                    pendingReady.clear()
                    pendingError.clear()
                }
            },
            { exception: IOException ->
                synchronized(this) {
                    loading = false
                    Log.e(TAG, "Failed to unpack speech model", exception)
                    val message = "Couldn't load the speech model — ${exception.message ?: exception.javaClass.simpleName}"
                    pendingError.forEach { it(message) }
                    pendingReady.clear()
                    pendingError.clear()
                }
            }
        )
    }
}

/**
 * One dictation attempt: takes a loaded Vosk model, listens on the mic (whatever AudioRecord's
 * VOICE_RECOGNITION source currently routes to — the Bluetooth SCO link, once connected), and
 * reports partial/final text or an error. Entirely on-device: no network, no system speech
 * service required.
 */
class VoskDictationSession(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var delivered = false

    fun start() {
        VoskEngine.ensureModelLoaded(
            context,
            onReady = { model -> startWithModel(model) },
            onError = { message -> onError(message) }
        )
    }

    private fun startWithModel(model: Model) {
        try {
            val rec = Recognizer(model, SAMPLE_RATE)
            recognizer = rec
            val service = SpeechService(rec, SAMPLE_RATE)
            speechService = service
            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = extractField(hypothesis, "partial")
                    if (!text.isNullOrBlank()) onPartial(text)
                }

                override fun onResult(hypothesis: String?) {
                    val text = extractField(hypothesis, "text")
                    if (!text.isNullOrBlank() && !delivered) {
                        delivered = true
                        onFinalText(text)
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    val text = extractField(hypothesis, "text")
                    if (!text.isNullOrBlank() && !delivered) {
                        delivered = true
                        onFinalText(text)
                    }
                }

                override fun onError(exception: Exception?) {
                    onError("Mic error — ${exception?.message ?: exception?.javaClass?.simpleName ?: "unknown"}")
                }

                override fun onTimeout() {
                    if (!delivered) onError("Didn't catch that — tap the mic and try again")
                }
            })
        } catch (e: IOException) {
            onError("Couldn't start the speech engine — ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            onError("Mic error — ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) {
        }
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        speechService = null
        recognizer = null
    }

    private fun extractField(json: String?, field: String): String? {
        if (json.isNullOrBlank()) return null
        return try {
            JSONObject(json).optString(field, "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
