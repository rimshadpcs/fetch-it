package com.rimapps.fetchit.platform

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableSharedFlow

/** Small persistent key/value store (SharedPreferences / NSUserDefaults). */
expect class KeyValueStore() {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

expect fun httpClient(): HttpClient

/** Opens a URL in an in-app browser sheet (Custom Tabs / Safari view). */
expect fun openBrowser(url: String)

/** Deep links (fetchit://…) the OS hands to the app. */
object DeepLinks {
    val incoming = MutableSharedFlow<String>(extraBufferCapacity = 4)
}

/**
 * A photo shared into the app from another app's share sheet ("Share to Fetch It"), already downscaled to a JPEG.
 * Held here until the app can fetch it (signed in and onboarded); then it's taken and set to null.
 */
object SharedPhotos {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<ByteArray?>(null)
}

/** Ways to get a photo in, already downscaled to a JPEG (longest side ≤ 1024 px) for fetchItem. */
class PhotoSource(val takePhoto: () -> Unit, val pickPhoto: () -> Unit)

@Composable
expect fun rememberPhotoSource(onPhoto: (ByteArray) -> Unit): PhotoSource

/** Asks for a fingerprint / face / device PIN. `onResult(true)` once the user is verified. */
class Approver(val approve: (title: String, subtitle: String, onResult: (Boolean) -> Unit) -> Unit)

@Composable
expect fun rememberApprover(): Approver

/** This device's push token (FCM on Android), or null when push isn't available. */
expect suspend fun pushToken(): String?

/** "android" / "ios", for registerPushToken. */
expect val platformName: String

/** Asks for permission to show notifications (Android 13+); does nothing where it isn't needed. */
@Composable
expect fun rememberNotificationPermission(): () -> Unit

/** A tapped notification asks the app to open a purchase: `approve` → the approval sheet, `purchase` → its outcome. */
data class PushOpen(val open: String, val purchaseId: String)

object PushOpens {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<PushOpen?>(null)
    /** The purchase whose approval sheet is showing: its notification isn't shown on top of it. */
    val onScreen = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

/** An App Check token for our backend (Play Integrity / debug provider on Android), or null if there isn't one. */
expect suspend fun appCheckToken(): String?

/** The system's "reduce motion" / "remove animations" accessibility setting. */
@Composable
expect fun systemReduceMotion(): Boolean

/** Photo files kept on this device only (Snaps): never uploaded except when the user searches with one. */
expect object PhotoFiles {
    fun save(id: String, bytes: ByteArray)
    fun load(id: String): ByteArray?
    fun delete(id: String)
}
