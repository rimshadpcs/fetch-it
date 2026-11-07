package com.rimapps.fetchit.platform

import platform.Foundation.create
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
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

// TODO(iOS): untested (iOS isn't compiled yet). Files under Application Support/snaps.
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual object PhotoFiles {
    private fun path(id: String): String {
        val dir = (platform.Foundation.NSSearchPathForDirectoriesInDomains(platform.Foundation.NSApplicationSupportDirectory, platform.Foundation.NSUserDomainMask, true).first() as String) + "/snaps"
        platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return "$dir/$id.jpg"
    }
    actual fun save(id: String, bytes: ByteArray) {
        val data = bytes.usePinned { platform.Foundation.NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        data.writeToFile(path(id), true)
    }
    actual fun load(id: String): ByteArray? {
        val data = platform.Foundation.NSData.dataWithContentsOfFile(path(id)) ?: return null
        return ByteArray(data.length.toInt()).also { out -> out.usePinned { platform.posix.memcpy(it.addressOf(0), data.bytes, data.length) } }
    }
    actual fun delete(id: String) { platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(path(id), null) }
}
