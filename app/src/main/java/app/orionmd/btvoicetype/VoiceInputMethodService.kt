package app.orionmd.btvoicetype

import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class VoiceInputMethodService : InputMethodService() {

    private lateinit var btnMic: ImageButton
    private lateinit var tvStatus: TextView
    private var dictationSession: VoskDictationSession? = null
    private lateinit var scoHelper: BluetoothScoHelper
    private var listening = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var statusResetRunnable: Runnable? = null
    private var maxDurationRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        ActivationGate.ensureFirstLaunchRecorded(this)
        scoHelper = BluetoothScoHelper(this)
        // Kick off the (one-time) model unpack in the background as soon as the keyboard
        // exists, so the first real dictation attempt doesn't have to wait for it.
        VoskEngine.ensureModelLoaded(this, onReady = {}, onError = {})
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        btnMic = view.findViewById(R.id.btnMic)
        tvStatus = view.findViewById(R.id.tvStatus)

        btnMic.setOnClickListener { onMicTapped() }
        view.findViewById<View>(R.id.btnBackspace).setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        view.findViewById<View>(R.id.btnSpace).setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }
        view.findViewById<View>(R.id.btnEnter).setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        view.findViewById<View>(R.id.btnSwitchIme).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showInputMethodPicker()
        }
        return view
    }

    private fun onMicTapped() {
        try {
            onMicTappedInternal()
        } catch (e: Exception) {
            // Never let an unexpected device/OEM audio quirk crash the keyboard — the host
            // app the user is typing into would go down with it.
            listening = false
            if (::btnMic.isInitialized) {
                btnMic.setBackgroundResource(R.drawable.mic_button_bg)
            }
            try { scoHelper.disconnect() } catch (_: Exception) {}
            showStatusThenReset("Mic error — ${e.javaClass.simpleName}, tap to try again")
        }
    }

    private fun onMicTappedInternal() {
        if (listening) {
            stopListening()
            return
        }

        if (!ActivationGate.isUsageAllowed(this)) {
            tvStatus.text = ActivationGate.statusLabel(this)
            Toast.makeText(this, "Open ${getString(R.string.app_name)} to activate", Toast.LENGTH_LONG).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            tvStatus.text = "Microphone permission needed — opening app…"
            val i = Intent(this, PermissionRelayActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            return
        }

        btnMic.setBackgroundResource(R.drawable.mic_button_bg_connecting)
        tvStatus.text = "Connecting to Bluetooth mic…"

        scoHelper.connect { connected ->
            runOnUiIfAlive {
                try {
                    startDictation(if (connected) null else "No BT mic — using device audio. ")
                } catch (e: Exception) {
                    stopListening(resetStatus = false)
                    showStatusThenReset("Mic error — ${e.javaClass.simpleName}, tap to try again")
                }
            }
        }
    }

    private fun startDictation(notePrefix: String?) {
        listening = true
        btnMic.setBackgroundResource(R.drawable.mic_button_bg_listening)
        tvStatus.text = (notePrefix ?: "") + "Loading speech model…"

        val session = VoskDictationSession(
            context = this,
            onPartial = { text ->
                uiHandler.post { if (::tvStatus.isInitialized) tvStatus.text = "\"$text\"" }
            },
            onFinalText = { text ->
                uiHandler.post {
                    currentInputConnection?.commitText("$text ", 1)
                    stopListening()
                }
            },
            onError = { message ->
                uiHandler.post {
                    stopListening(resetStatus = false)
                    showStatusThenReset(message)
                }
            }
        )
        dictationSession = session
        session.start()
        uiHandler.post { if (listening && ::tvStatus.isInitialized) tvStatus.text = "Listening…" }

        // Safety net: force-stop after 20s so a stuck recognizer never leaves the mic hot.
        maxDurationRunnable?.let { uiHandler.removeCallbacks(it) }
        val timeout = Runnable {
            if (listening) {
                stopListening(resetStatus = false)
                showStatusThenReset("Stopped listening — tap the mic and try again")
            }
        }
        maxDurationRunnable = timeout
        uiHandler.postDelayed(timeout, 20000)
    }

    private fun stopListening(resetStatus: Boolean = true) {
        listening = false
        maxDurationRunnable?.let { uiHandler.removeCallbacks(it) }
        dictationSession?.stop()
        dictationSession = null
        scoHelper.disconnect()
        if (::btnMic.isInitialized) {
            btnMic.setBackgroundResource(R.drawable.mic_button_bg)
        }
        if (resetStatus) {
            statusResetRunnable?.let { uiHandler.removeCallbacks(it) }
            if (::tvStatus.isInitialized) {
                tvStatus.text = "Tap the mic and speak"
            }
        }
    }

    /** Shows a diagnostic message and reverts to the default prompt a few seconds later,
     *  instead of letting it get clobbered instantly by whatever calls stopListening() next. */
    private fun showStatusThenReset(message: String) {
        if (!::tvStatus.isInitialized) return
        statusResetRunnable?.let { uiHandler.removeCallbacks(it) }
        tvStatus.text = message
        val runnable = Runnable {
            if (::tvStatus.isInitialized && !listening) {
                tvStatus.text = "Tap the mic and speak"
            }
        }
        statusResetRunnable = runnable
        uiHandler.postDelayed(runnable, 4000)
    }

    private fun runOnUiIfAlive(block: () -> Unit) {
        if (::btnMic.isInitialized) {
            btnMic.post(block)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        ActivationGate.silentReverify(this)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopListening()
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }
}
