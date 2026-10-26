package com.rimapps.fetchit

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.rimapps.fetchit.platform.AndroidPlatform
import com.rimapps.fetchit.platform.DeepLinks
import java.lang.ref.WeakReference

// FragmentActivity (not ComponentActivity) because BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    private var goBack: (() -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidPlatform.context = applicationContext
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

    // PayPal returns to fetchit://paypal/return (or /cancel) after the approval sheet.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        intent?.data?.takeIf { it.scheme == "fetchit" }?.let { DeepLinks.incoming.tryEmit(it.toString()) }
    }
}
