package app.orionmd.btvoicetype

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Wraps Android's classic-Bluetooth (HFP/SCO) audio routing so speech recognition reads from a
 * paired Bluetooth headset/mic instead of a (nonexistent) built-in microphone.
 */
class BluetoothScoHelper(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var receiver: BroadcastReceiver? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    /**
     * Starts SCO audio routing and calls back once the Bluetooth mic is actually connected
     * (or with success=false if it fails / times out after ~6 seconds — e.g. no BT headset
     * paired or connected, Bluetooth turned off, or an OEM audio-stack quirk). Never throws:
     * any failure just calls back with success=false instead of crashing the caller.
     */
    fun connect(onResult: (success: Boolean) -> Unit) {
        if (receiver != null) {
            // already trying
            return
        }

        val am = audioManager
        if (am == null) {
            onResult(false)
            return
        }

        try {
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            val br = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    try {
                        val state = intent.getIntExtra(
                            AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_ERROR
                        )
                        when (state) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                cancelTimeout()
                                onResult(true)
                            }
                            AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                            AudioManager.SCO_AUDIO_STATE_ERROR -> {
                                cancelTimeout()
                                unregister()
                                onResult(false)
                            }
                        }
                    } catch (_: Exception) {
                        cancelTimeout()
                        unregister()
                        onResult(false)
                    }
                }
            }
            receiver = br
            context.registerReceiver(br, filter)

            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = true
            am.startBluetoothSco()

            timeoutRunnable = Runnable {
                unregister()
                onResult(false)
            }
            timeoutHandler.postDelayed(timeoutRunnable!!, 6000)
        } catch (_: Exception) {
            // Some OEM audio stacks throw here (e.g. Bluetooth off, no adapter, permission
            // quirks on custom Go Edition builds) — fail safe instead of crashing the keyboard.
            unregister()
            cancelTimeout()
            onResult(false)
        }
    }

    fun disconnect() {
        cancelTimeout()
        try {
            audioManager?.let { am ->
                am.stopBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {
        }
        unregister()
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun unregister() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        receiver = null
    }
}
