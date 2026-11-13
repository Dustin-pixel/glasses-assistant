package dev.glasses.assistant.display

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import dev.glasses.assistant.AssistantService

/**
 * An invisible screen that shows Android's "Start recording or casting?" prompt, then hands the
 * answer to the assistant. Android requires this from a visible screen, once per session.
 */
class MirrorConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) {
            AssistantService.mirrorAnswer(this, resultCode, data)
            finish()
        }
    }

    private companion object {
        const val REQUEST = 7
    }
}
