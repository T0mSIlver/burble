package dev.silver.ramblerbubble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        startButton = findViewById(R.id.start)

        findViewById<Button>(R.id.overlay_perm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.a11y_perm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        startButton.setOnClickListener {
            if (ready()) BubbleService.start(this)
        }
        findViewById<Button>(R.id.test_audio).setOnClickListener { sendTestAudio() }
        findViewById<Button>(R.id.stop).setOnClickListener { BubbleService.stop(this) }
    }

    /** Streams the bundled 12 s clip through the app's own S3 client; shows the transcript. */
    private fun sendTestAudio() {
        val button = findViewById<Button>(R.id.test_audio)
        button.isEnabled = false
        status.text = getString(R.string.sending)
        Thread {
            val audio = assets.open("test12s.opus").use { it.readBytes() }
            val s = S3Stream(
                onPartial = { runOnUiThread { status.text = it } },
                onDone = { r ->
                    runOnUiThread {
                        button.isEnabled = true
                        r.onSuccess { text ->
                            status.text = if (text.isBlank()) getString(R.string.empty_result)
                            else getString(R.string.test_result, text)
                        }.onFailure { e ->
                            status.text = getString(R.string.failed, e.message?.take(120))
                        }
                    }
                },
            )
            s.start()
            // Feed like the dictation loop does: 224-byte Ogg chunks.
            var i = 0
            while (i < audio.size) {
                s.feed(audio.copyOfRange(i, minOf(i + 224, audio.size)))
                i += 224
            }
            s.finish()
        }.apply { start() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun ready(): Boolean {
        val overlay = Settings.canDrawOverlays(this)
        val mic = hasMic()
        val a11y = isInsertionEnabled()
        return overlay && mic && a11y
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val mic = hasMic()
        val a11y = isInsertionEnabled()
        status.text = buildString {
            append(getString(R.string.perm_overlay, mark(overlay))); append('\n')
            append(getString(R.string.perm_mic, mark(mic))); append('\n')
            append(getString(R.string.perm_a11y, mark(a11y)))
        }
        startButton.isEnabled = overlay && mic
        if (!mic) ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this,
        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isInsertionEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/${InsertionService::class.java.name}") == true

    private fun mark(ok: Boolean) = if (ok) "\u2713" else "\u2717"

    companion object {
        private const val TAG = "RamblerMain"
    }
}
