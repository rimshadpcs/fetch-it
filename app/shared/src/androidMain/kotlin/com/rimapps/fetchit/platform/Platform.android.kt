package com.rimapps.fetchit.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.FragmentActivity
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference

/** Set by MainActivity: the app context and the current activity (for browser sheets and biometrics). */
@SuppressLint("StaticFieldLeak")
object AndroidPlatform {
    lateinit var context: Context
    var activity: WeakReference<FragmentActivity> = WeakReference(null)
}

actual class KeyValueStore actual constructor() {
    private val prefs by lazy { AndroidPlatform.context.getSharedPreferences("fetchit", Context.MODE_PRIVATE) }
    actual fun get(key: String): String? = prefs.getString(key, null)
    actual fun set(key: String, value: String?) = prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
}

actual fun httpClient(): HttpClient = HttpClient(OkHttp) {
    // OkHttp silently re-sends a POST when a pooled connection drops mid-request: that duplicated fetchItem
    // calls on Oct 26. purchaseOffer is idempotent now, but nothing should be sent twice behind our back.
    engine { config { retryOnConnectionFailure(false) } }
    // fetchItem and purchaseOffer (5 s undo window + PayPal) can take a while; the functions time out at 60 s.
    install(HttpTimeout) { requestTimeoutMillis = 70_000; socketTimeoutMillis = 70_000 }
}

actual fun openBrowser(url: String) {
    val activity = AndroidPlatform.activity.get() ?: return
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, Uri.parse(url))
}

@Composable
actual fun rememberPhotoSource(onPhoto: (ByteArray) -> Unit): PhotoSource {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deliver: (Uri) -> Unit = { uri ->
        scope.launch {
            val jpeg = withContext(Dispatchers.IO) { runCatching { downscaleJpeg(context, uri) }.getOrNull() }
            if (jpeg != null) onPhoto(jpeg)
        }
    }
    val cameraFile = remember { File(context.cacheDir, "camera/snap.jpg").apply { parentFile?.mkdirs() } }
    val cameraUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", cameraFile) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) deliver(cameraUri) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(deliver) }
    return remember {
        PhotoSource(
            takePhoto = { camera.launch(cameraUri) },
            pickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

/** Longest side ≤ 1024 px, EXIF rotation applied, JPEG 85: what fetchItem expects (and well under its 8 MB cap). */
fun downscaleJpeg(context: Context, uri: Uri, maxSide: Int = 1024): ByteArray {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
    val rotation = resolver.openInputStream(uri)!!.use { ExifInterface(it).rotationDegrees }
    val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
    val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
    val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
}

@Composable
actual fun rememberApprover(): Approver = remember {
    Approver { title, subtitle, onResult ->
        val activity = AndroidPlatform.activity.get()
        if (activity == null) {
            onResult(false); return@Approver
        }
        // Fingerprint/face, or the device PIN as a fallback (API 30+ allows combining strong biometrics + credential).
        val authenticators = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No lock screen set up (e.g. a fresh emulator): the explicit tap on "Approve" is the approval.
            onResult(true); return@Approver
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setAllowedAuthenticators(authenticators).build(),
        )
    }
}

/** Settings → Accessibility → Remove animations sets the animator scale to 0. */
@Composable
actual fun systemReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

actual object PhotoFiles {
    private fun file(id: String) = File(File(AndroidPlatform.context.filesDir, "snaps").apply { mkdirs() }, "$id.jpg")
    actual fun save(id: String, bytes: ByteArray) = file(id).writeBytes(bytes)
    actual fun load(id: String): ByteArray? = file(id).takeIf { it.exists() }?.readBytes()
    actual fun delete(id: String) { file(id).delete() }
}
