package kiesel

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.kiesel.R

class KieselApp : Application() {
    val model by lazy { AppModel(this) }
}

/** Keeps the process and GPS alive while navigating with the screen off. */
class NavigationService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = navigationNotification(getString(R.string.navigating))
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        startForeground(NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Shows the latest instruction on the lock screen. */
fun Context.showNavigation(text: String) {
    getSystemService(NotificationManager::class.java)
        .notify(NOTIFICATION_ID, navigationNotification(text))
}

private fun Context.navigationNotification(text: String): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    val name = getString(R.string.navigating)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW),
    )
    val open = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )
    return Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_NAVIGATION)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .build()
}

private const val CHANNEL = "navigation"
private const val NOTIFICATION_ID = 1
