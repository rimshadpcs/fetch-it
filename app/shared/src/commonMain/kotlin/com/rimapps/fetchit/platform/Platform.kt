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

/** Ways to get a photo in, already downscaled to a JPEG (longest side ≤ 1024 px) for fetchItem. */
class PhotoSource(val takePhoto: () -> Unit, val pickPhoto: () -> Unit)

@Composable
expect fun rememberPhotoSource(onPhoto: (ByteArray) -> Unit): PhotoSource

/** Asks for a fingerprint / face / device PIN. `onResult(true)` once the user is verified. */
class Approver(val approve: (title: String, subtitle: String, onResult: (Boolean) -> Unit) -> Unit)

@Composable
expect fun rememberApprover(): Approver
