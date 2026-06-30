package com.gotohex.rdp.ui.screens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.gotohex.rdp.R
import com.gotohex.rdp.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service that keeps the remote session alive when the user
 * leaves the app. The notification lets them tap back to the session.
 */
@AndroidEntryPoint
class RdpSessionService : Service() {

    companion object {
        const val CHANNEL_ID   = "rdp_session"
        const val NOTIF_ID     = 1001
        const val EXTRA_HOST   = "host"
        const val ACTION_STOP  = "com.gotohex.rdp.STOP_SESSION"

        fun start(context: android.content.Context, host: String) {
            val intent = android.content.Intent(context, RdpSessionService::class.java)
                .putExtra(EXTRA_HOST, host)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(android.content.Intent(context, RdpSessionService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // BUG-COMPAT-1 FIX: Service.stopForeground(int) was only added in API 33.
            // Calling stopForeground(STOP_FOREGROUND_REMOVE) directly on API 26–32 resolves
            // to the int overload which doesn't exist at runtime → NoSuchMethodError crash.
            // ServiceCompat.stopForeground() handles the API split internally.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val host = intent?.getStringExtra(EXTRA_HOST) ?: "Remote Session"
        createNotificationChannel()

        val returnIntent = Intent(this, RdpSessionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingReturn = PendingIntent.getActivity(
            this, 0, returnIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, RdpSessionService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.background_session_title))
            .setContentText(getString(R.string.background_session_text, host))
            // BUG 7 FIX: ic_menu_compass is an internal Android drawable with no
            // guaranteed availability or size across versions. Use the app's own
            // launcher icon for a consistent, branded notification icon.
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingReturn)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), pendingStop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.background_session_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            // CRIT-NEW-4 FIX: description was hardcoded English — Arabic users saw
            // English text in system notification settings.  Use string resource so
            // localisation applies correctly.
            description = getString(R.string.background_session_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
