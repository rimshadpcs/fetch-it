package com.rimapps.fetchit.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication

actual class KeyValueStore actual constructor() {
    private val defaults = NSUserDefaults.standardUserDefaults
    actual fun get(key: String): String? = defaults.stringForKey("fetchit.$key")
    actual fun set(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey("fetchit.$key") else defaults.setObject(value, "fetchit.$key")
    }
}

actual fun httpClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) { requestTimeoutMillis = 70_000; socketTimeoutMillis = 70_000 }
}

// TODO(iOS): ASWebAuthenticationSession so the fetchit:// return lands back in the app without a Safari hop.
actual fun openBrowser(url: String) {
    NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null) }
}

// TODO(iOS): PHPickerViewController + UIImagePickerController (camera), downscaled to 1024 px JPEG. Android first.
@Composable
actual fun rememberPhotoSource(onPhoto: (ByteArray) -> Unit): PhotoSource = remember { PhotoSource(takePhoto = {}, pickPhoto = {}) }

// TODO(iOS): LocalAuthentication (LAContext.evaluatePolicy) for Face ID / Touch ID.
@Composable
actual fun rememberApprover(): Approver = remember { Approver { _, _, onResult -> onResult(true) } }

// TODO(iOS): APNs via FirebaseMessaging (needs the APNs key in the Firebase console).
actual suspend fun pushToken(): String? = null
actual val platformName: String = "ios"

@Composable
actual fun rememberNotificationPermission(): () -> Unit = remember { {} }

// TODO(iOS): App Attest / DeviceCheck provider via the Firebase iOS SDK.
actual suspend fun appCheckToken(): String? = null

@Composable
actual fun systemReduceMotion(): Boolean = remember { platform.UIKit.UIAccessibilityIsReduceMotionEnabled() }
