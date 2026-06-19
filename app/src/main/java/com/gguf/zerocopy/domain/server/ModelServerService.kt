package com.gguf.zerocopy.domain.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gguf.zerocopy.ZeroCopyApp

class ModelServerService : Service() {
  companion object {
    const val CHANNEL_ID = "zerocopy_server_service"
    const val NOTIFICATION_ID = 1002
    const val ACTION_STOP = "com.gguf.zerocopy.STOP_SERVER"
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }

    val app = ZeroCopyApp.instance
    val server = app.modelServer

    startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

    server.notificationSetter = { text ->
      val nm = getSystemService(NotificationManager::class.java)
      nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    server.start()

    return START_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
    val app = ZeroCopyApp.instance
    app.modelServer.notificationSetter = null
    app.modelServer.stop()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID, "ZeroCopy Server",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "ZeroCopy AI server foreground service"
        setShowBadge(false)
      }
      getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
  }

  private fun buildNotification(text: String): Notification {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    val contentIntent = PendingIntent.getActivity(
      this, 0, launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val stopIntent = Intent(this, ModelServerService::class.java).apply { action = ACTION_STOP }
    val stopPending = PendingIntent.getService(
      this, 1, stopIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("ZeroCopy Server")
      .setContentText(text)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setOngoing(true)
      .setContentIntent(contentIntent)
      .addAction(android.R.drawable.ic_media_pause, "Stop Server", stopPending)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }
}
