package com.rimapps.fetchit

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.rimapps.fetchit.platform.AndroidFirebase
import com.rimapps.fetchit.platform.AndroidPlatform
import com.rimapps.fetchit.platform.DeepLinks
import com.rimapps.fetchit.platform.SharedPhotos
import com.rimapps.fetchit.platform.downscaleJpeg
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

// FragmentActivity (not ComponentActivity) because BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    private var goBack: (() -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidPlatform.activity = WeakReference(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (goBack?.invoke() != true) finish()
            }
        })
        setContent { App(register = { goBack = it }) }
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        AndroidPlatform.activity = WeakReference(this)
    }

    // PayPal returns to fetchit://paypal/return (or /cancel) after the approval sheet; notifications land here too.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        intent?.data?.takeIf { it.scheme == "fetchit" }?.let { DeepLinks.incoming.tryEmit(it.toString()) }
        AndroidFirebase.handleIntent(intent) // a tapped notification
        handleShare(intent)
    }

    // "Share to Fetch It": an image from another app's share sheet (a screenshot, a gallery photo, a saved post).
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        // Pinterest, Instagram and browsers share a link (text), not the photo: the backend finds the photo.
        if (intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            intent.action = null
            SharedPhotos.pendingLink.value = text.take(4000)
            return
        }
        if (intent.type?.startsWith("image/") != true) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        intent.action = null // handled once, not again on recreation
        lifecycleScope.launch {
            val jpeg = withContext(Dispatchers.IO) { runCatching { downscaleJpeg(this@MainActivity, uri) }.getOrNull() }
            if (jpeg != null) SharedPhotos.pending.value = jpeg
        }
    }
}
