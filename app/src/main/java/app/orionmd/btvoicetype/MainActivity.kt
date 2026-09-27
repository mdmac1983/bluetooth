package app.orionmd.btvoicetype

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etKey: EditText
    private lateinit var tvFloatingMicStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ActivationGate.ensureFirstLaunchRecorded(this)

        tvStatus = findViewById(R.id.tvActivationStatus)
        etKey = findViewById(R.id.etActivationKey)
        val tvChangelog = findViewById<TextView>(R.id.tvChangelog)

        findViewById<Button>(R.id.btnOpenImeSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnOpenInputPicker).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showInputMethodPicker()
        }
        findViewById<Button>(R.id.btnOpenBtSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open Bluetooth settings", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnActivate).setOnClickListener {
            ActivationGate.activate(this, etKey.text.toString()) { success, message ->
                runOnUiThread {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            }
        }
        findViewById<Button>(R.id.btnPurchase).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/orionmd")))
        }

        tvFloatingMicStatus = findViewById(R.id.tvFloatingMicStatus)
        findViewById<Button>(R.id.btnOpenOverlaySettings).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission is granted automatically on this Android version", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnOpenAccessibilitySettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open Accessibility settings", Toast.LENGTH_SHORT).show()
            }
        }

        tvChangelog.text = resources.getStringArray(R.array.changelog_entries).joinToString("\n\n")

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        tvStatus.text = "${getString(R.string.activation_status_label)} ${ActivationGate.statusLabel(this)}"

        val overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
        val serviceEnabled = isAccessibilityServiceEnabled()

        tvFloatingMicStatus.text = when {
            overlayGranted && serviceEnabled -> getString(R.string.floating_mic_status_on)
            !overlayGranted -> getString(R.string.floating_mic_status_overlay_missing)
            else -> getString(R.string.floating_mic_status_service_missing)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${FloatingMicAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }
}
