package com.crosscheck.app.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.crosscheck.app.CrossCheckApp
import com.crosscheck.app.R

/**
 * `shortService`-type foreground service that owns one or more TTS utterances started from the
 * SMS receiver (docs/research/bank-sms-templates.md section 3). Each start id is released when its
 * utterance finishes (`stopSelfResult`), so the service disappears as soon as the last one is done;
 * [onTimeout] (about 3 min on API 34+) stops it unconditionally. The notification-listener path does
 * not use this service because that service is already system-bound.
 */
class SpeakService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        startInForeground()
        val speaker = CrossCheckApp.from(this).container.speaker
        val id = speaker.speak(text) { utteranceId, ok ->
            Log.i(TAG, "utterance $utteranceId finished ok=$ok (startId=$startId)")
            release(startId)
        }
        Log.i(TAG, "speaking id=$id startId=$startId text=\"$text\"")
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        Log.w(TAG, "shortService timeout (startId=$startId); stopping")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        onTimeout(startId)
    }

    private fun release(startId: Int) {
        if (stopSelfResult(startId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.tts_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.tts_fgs_title))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val TAG = "CrossCheckFGS"
        const val CHANNEL_ID = "tts_announcements"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_TEXT = "text"

        /** Starts the service for [text]; false if the platform refused (caller should speak directly). */
        fun start(context: Context, text: String): Boolean {
            val intent = Intent(context, SpeakService::class.java).putExtra(EXTRA_TEXT, text)
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException.
                Log.w(TAG, "startForegroundService refused", e)
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "startForegroundService denied", e)
                false
            }
        }
    }
}
