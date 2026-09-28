package io.github.hagbard235.ringnotes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.hagbard235.ringnotes.ui.MainActivity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a ring connection is wanted, so recordings
 * keep arriving with the screen off. Stops itself when the user disconnects.
 */
class RingService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(ringController.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        lifecycleScope.launch {
            ringController.state
                .map { NotificationContent(it.wantConnected, it.link, it.battery?.level, it.recording != null, it.locked) }
                .distinctUntilChanged()
                .collect { content ->
                    if (!content.wantConnected) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(ringController.state.value))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_DISCONNECT) {
            ringController.disconnect()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private data class NotificationContent(
        val wantConnected: Boolean,
        val link: LinkState,
        val battery: Int?,
        val recording: Boolean,
        val locked: Boolean,
    )

    private fun buildNotification(state: RingUiState) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ring)
            .setContentTitle(state.deviceName ?: getString(R.string.app_name))
            .setContentText(statusText(state))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(
                0, "Trennen",
                PendingIntent.getService(
                    this, 1, Intent(this, RingService::class.java).setAction(ACTION_DISCONNECT),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "ring"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "disconnect"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RingService::class.java))
        }
    }
}

fun statusText(state: RingUiState): String = buildString {
    append(
        when (state.link) {
            LinkState.DISCONNECTED -> "Getrennt"
            LinkState.CONNECTING -> "Verbinde …"
            LinkState.WAITING_FOR_RING -> "Warte auf Ring …"
            LinkState.CONNECTED -> "Verbunden"
        },
    )
    state.battery?.let { append(" · Akku ${it.level} %") }
    if (state.locked) append(" · gesperrt")
    if (state.recording != null) append(" · Aufnahme läuft")
}
