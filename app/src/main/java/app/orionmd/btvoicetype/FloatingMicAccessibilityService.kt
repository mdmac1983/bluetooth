package app.orionmd.btvoicetype

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Draws a small draggable "mic bubble" over every app (like a chat head), so dictation works
 * without switching the system keyboard away from Gboard (or whatever the user normally uses).
 *
 * Tapping the bubble runs the exact same Bluetooth-SCO-connect-then-recognize flow as the
 * keyboard (VoiceInputMethodService), but instead of committing text through an InputConnection
 * it finds the currently focused editable field via the Accessibility API and sets its text
 * directly (ACTION_SET_TEXT). This does mean recognized text is appended at the end of whatever
 * is already in the field rather than at the exact cursor position — a known trade-off of doing
 * text insertion this way instead of through a real IME.
 */
class FloatingMicAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var dictationSession: VoskDictationSession? = null
    private lateinit var scoHelper: BluetoothScoHelper
    private var listening = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private var maxDurationRunnable: Runnable? = null

    // Remembers the last focused editable node so we still have something to type into even if
    // focus-tracking briefly lags behind (e.g. right after the user taps the bubble itself).
    private var lastFocusedNode: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ActivationGate.ensureFirstLaunchRecorded(this)
        scoHelper = BluetoothScoHelper(this)
        // Kick off the (one-time) model unpack in the background right away.
        VoskEngine.ensureModelLoaded(this, onReady = {}, onError = {})
        showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val source = event?.source ?: return
        if (source.isEditable) {
            lastFocusedNode = source
        }
    }

    override fun onInterrupt() {
        // Required override; nothing to clean up specifically on interrupt.
    }

    override fun onDestroy() {
        stopListening()
        removeBubble()
        super.onDestroy()
    }

    // ---- Floating bubble UI -------------------------------------------------------------

    private fun showBubble() {
        if (bubbleView != null) return
        if (!canDrawOverlays()) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val view = LayoutInflater.from(this).inflate(R.layout.floating_mic_bubble, null)
        bubbleView = view

        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 300
        bubbleParams = params

        val micButton = view.findViewById<ImageButton>(R.id.floatingMicButton)
        attachDragAndClick(micButton, params)

        try {
            wm.addView(view, params)
        } catch (_: Exception) {
            bubbleView = null
        }
    }

    private fun removeBubble() {
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        bubbleView = null
    }

    private fun attachDragAndClick(button: ImageButton, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchDownX = 0f
        var touchDownY = 0f
        var moved = false

        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownX).toInt()
                    val dy = (event.rawY - touchDownY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        windowManager?.updateViewLayout(bubbleView, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    true
                }
                else -> false
            }
        }
        button.setOnClickListener { onMicTapped(button) }
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    // ---- Mic / dictation flow (mirrors VoiceInputMethodService, Vosk-based) --------------

    private fun onMicTapped(button: ImageButton) {
        try {
            onMicTappedInternal(button)
        } catch (e: Exception) {
            listening = false
            button.setBackgroundResource(R.drawable.mic_button_bg)
            try { scoHelper.disconnect() } catch (_: Exception) {}
            toastThenNothing("Mic error — ${e.javaClass.simpleName}")
        }
    }

    private fun onMicTappedInternal(button: ImageButton) {
        if (listening) {
            stopListening()
            button.setBackgroundResource(R.drawable.mic_button_bg)
            return
        }

        if (!ActivationGate.isUsageAllowed(this)) {
            toastThenNothing(ActivationGate.statusLabel(this))
            return
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toastThenNothing("Open ${getString(R.string.app_name)} and grant microphone permission first")
            val i = Intent(this, PermissionRelayActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            return
        }

        button.setBackgroundResource(R.drawable.mic_button_bg_connecting)

        scoHelper.connect { connected ->
            uiHandler.post {
                try {
                    startDictation(button)
                    if (!connected) {
                        toastThenNothing("No BT mic connected — using device audio")
                    }
                } catch (e: Exception) {
                    stopListening()
                    button.setBackgroundResource(R.drawable.mic_button_bg)
                    toastThenNothing("Mic error — ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun startDictation(button: ImageButton) {
        listening = true
        button.setBackgroundResource(R.drawable.mic_button_bg_listening)

        val session = VoskDictationSession(
            context = this,
            onPartial = {},
            onFinalText = { text ->
                uiHandler.post {
                    insertTextIntoFocusedField("$text ")
                    stopListening()
                    button.setBackgroundResource(R.drawable.mic_button_bg)
                }
            },
            onError = { message ->
                uiHandler.post {
                    stopListening()
                    button.setBackgroundResource(R.drawable.mic_button_bg)
                    toastThenNothing(message)
                }
            }
        )
        dictationSession = session
        session.start()

        maxDurationRunnable?.let { uiHandler.removeCallbacks(it) }
        val timeout = Runnable {
            if (listening) {
                stopListening()
                button.setBackgroundResource(R.drawable.mic_button_bg)
                toastThenNothing("Stopped listening")
            }
        }
        maxDurationRunnable = timeout
        uiHandler.postDelayed(timeout, 20000)
    }

    private fun stopListening() {
        listening = false
        maxDurationRunnable?.let { uiHandler.removeCallbacks(it) }
        dictationSession?.stop()
        dictationSession = null
        scoHelper.disconnect()
    }

    /**
     * Finds the currently focused editable field (via the live accessibility tree first, falling
     * back to the last one seen) and appends the recognized text to it with ACTION_SET_TEXT.
     * Note: this replaces the field's full text rather than inserting at the cursor, since the
     * Accessibility API doesn't give reliable cross-app cursor-position control the way a real
     * IME's InputConnection does.
     */
    private fun insertTextIntoFocusedField(text: String) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: lastFocusedNode
        if (node == null || !node.isEditable) {
            toastThenNothing("No text field focused — tap into one first")
            return
        }
        try {
            val existing = node.text?.toString() ?: ""
            val newText = existing + text
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (_: Exception) {
            toastThenNothing("Couldn't type into that field")
        }
    }

    private fun toastThenNothing(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }
}
