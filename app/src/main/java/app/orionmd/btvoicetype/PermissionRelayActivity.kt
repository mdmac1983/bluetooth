package app.orionmd.btvoicetype

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent trampoline activity. InputMethodService can't request runtime permissions
 * directly, so the keyboard launches this to get RECORD_AUDIO (and location, required on
 * API 29 for Bluetooth device scanning/state) granted, then finishes itself.
 *
 * Deliberately extends the plain androidx ComponentActivity, NOT AppCompatActivity: this
 * activity uses the platform's Theme.Translucent.NoTitleBar (not an AppCompat theme), and
 * AppCompatActivity requires an AppCompat-descendant theme or it can crash. ComponentActivity
 * has no such requirement and still supports registerForActivityResult.
 */
class PermissionRelayActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        Toast.makeText(
            this,
            if (allGranted) "Permission granted — try the mic again" else "Permission denied — dictation needs microphone access",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
    }
}
