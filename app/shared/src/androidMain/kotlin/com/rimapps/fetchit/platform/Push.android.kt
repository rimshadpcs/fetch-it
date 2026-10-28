package com.rimapps.fetchit.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rimapps.fetchit.data.FetchApi
import com.rimapps.fetchit.data.FirebaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Native Firebase on Android is only used for push (and App Check); everything else goes over REST. */
object AndroidFirebase {
    const val CHANNEL = "approvals"

    fun init(context: Context) {
        AndroidPlatform.context = context.applicationContext
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey(FirebaseConfig.API_KEY)
                    .setApplicationId(FirebaseConfig.ANDROID_APP_ID)
                    .setProjectId(FirebaseConfig.PROJECT_ID)
                    .setGcmSenderId(FirebaseConfig.SENDER_ID)
                    .build(),
            )
        }
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Needs your OK", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Purchases waiting for your approval, and price-drop buys"
                },
            )
        }
    }

    /** Extras FCM puts on the launch intent when a notification is tapped (or ours, from [show]). */
    fun handleIntent(intent: Intent?) {
        val purchaseId = intent?.getStringExtra("purchaseId") ?: return
        PushOpens.pending.value = PushOpen(intent.getStringExtra("open") ?: "approve", purchaseId)
    }

    /** In the foreground FCM doesn't show notification messages itself. */
    fun show(context: Context, message: RemoteMessage) {
        val n = message.notification ?: return
        val purchaseId = message.data["purchaseId"] ?: return
        if (PushOpens.onScreen.value == purchaseId) return // already looking at it
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            message.data.forEach { (k, v) -> putExtra(k, v) }
        }
        val tap = PendingIntent.getActivity(context, purchaseId.hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(context).notify(
            purchaseId, 0,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(context.resources.getIdentifier("ic_notification", "drawable", context.packageName).takeIf { it != 0 } ?: android.R.drawable.stat_notify_more)
                .setContentTitle(n.title)
                .setContentText(n.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }
}

class FetchMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        // Only for a signed-in user; otherwise the app registers after sign-in.
        val api = FetchApi()
        if (api.firebase.signedIn) scope.launch { runCatching { api.registerPushToken(token, platformName) } }
    }

    override fun onMessageReceived(message: RemoteMessage) = AndroidFirebase.show(this, message)
}

actual suspend fun pushToken(): String? = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()

actual val platformName: String = "android"

@Composable
actual fun rememberNotificationPermission(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember {
        {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * The SDK caches the token and refreshes it before it expires. Never blocks a request for long: if the provider
 * can't attest (debug token not allow-listed, sideloaded build), the request goes without one and the backend
 * decides (ENFORCE_APP_CHECK).
 */
actual suspend fun appCheckToken(): String? =
    kotlinx.coroutines.withTimeoutOrNull(4_000) {
        runCatching { com.google.firebase.appcheck.FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token }.getOrNull()
    }
