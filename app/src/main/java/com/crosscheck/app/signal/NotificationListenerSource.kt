package com.crosscheck.app.signal

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.crosscheck.app.CrossCheckApp
import com.crosscheck.app.data.PaymentSource
import com.crosscheck.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Mode 1 production path: reads title + (bigText or text) of notifications posted by packages in the
 * user's trusted set (Settings). Access is granted through the system notification-listener page
 * or, on the emulator, `adb shell cmd notification allow_listener <component>`.
 * The service is system-bound, so it speaks directly (no foreground service needed here).
 */
class NotificationListenerSource : NotificationListenerService(), PaymentSignalSource {

    override val sourceName: String = PaymentSource.NOTIFICATION

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var settings: Settings = Settings()

    override fun onCreate() {
        super.onCreate()
        val container = CrossCheckApp.from(this).container
        scope.launch { container.settings.settings.collect { settings = it } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        Log.i(TAG, "listener connected; trusted=${settings.trustedPackages}")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val current = settings
        if (!current.notificationSourceEnabled) return
        if (sbn.packageName == packageName) return
        if (sbn.packageName !in current.trustedPackages) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val text = extractText(notification.extras)
        if (text.isBlank()) return

        val container = CrossCheckApp.from(this).container
        scope.launch {
            val result = deliver(container.ingestor, sbn.packageName, true, text, sbn.postTime)
            Log.i(TAG, "pkg=${sbn.packageName} result=${result::class.simpleName} text=\"${text.take(80)}\"")
        }
    }

    companion object {
        private const val TAG = "CrossCheckNotif"

        /** title + "\n" + (bigText ?: text) - see [NotificationText]. */
        fun extractText(extras: Bundle): String = NotificationText.compose(
            title = extras.getCharSequence(Notification.EXTRA_TITLE),
            text = extras.getCharSequence(Notification.EXTRA_TEXT),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        )

        fun component(context: Context): ComponentName =
            ComponentName(context, NotificationListenerSource::class.java)

        fun isAccessGranted(context: Context): Boolean {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return nm.isNotificationListenerAccessGranted(component(context))
        }
    }
}
