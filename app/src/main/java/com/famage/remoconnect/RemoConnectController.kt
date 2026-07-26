package com.famage.remoconnect

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.famage.remoconnect.data.discovery.DeviceDiscoveryService
import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.protocol.InfraredProtocolEngine
import com.famage.remoconnect.data.repository.TvDeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object RemoConnectController {
    const val ACTION_POWER = "com.famage.remoconnect.action.POWER"
    const val ACTION_MUTE = "com.famage.remoconnect.action.MUTE"
    const val ACTION_VOLUME_UP = "com.famage.remoconnect.action.VOLUME_UP"
    const val ACTION_VOLUME_DOWN = "com.famage.remoconnect.action.VOLUME_DOWN"
    const val ACTION_PLAY_PAUSE = "com.famage.remoconnect.action.PLAY_PAUSE"

    private const val CHANNEL_ID = "remote_controls"
    private const val NOTIFICATION_ID = 1001

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var repository: TvDeviceRepository? = null

    @Synchronized
    fun getRepository(context: Context): TvDeviceRepository {
        repository?.let { return it }

        val appContext = context.applicationContext
        val created = TvDeviceRepository(
            context = appContext,
            discoveryService = null,
            adbEngine = null,
            androidTvEngine = null,
            irEngine = InfraredProtocolEngine(appContext)
        )
        val discoveryService = DeviceDiscoveryService(
            context = appContext,
            logger = { created.addLog(it) }
        )
        created.setDiscoveryService(discoveryService)
        repository = created
        return created
    }

    fun showRemoteNotification(context: Context) {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) return

        createNotificationChannel(appContext)
        val repository = getRepository(appContext)
        val activeDevice = repository.activeDevice.value
        val title = activeDevice?.name ?: "Remo Connect"
        val status = if (activeDevice?.isConnected == true) {
            "Connected"
        } else {
            "Quick controls for your active TV"
        }

        val openAppIntent = Intent(appContext, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_remote)
            .setContentTitle(title)
            .setContentText(status)
            .setContentIntent(contentIntent)
            .setOngoing(false)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(android.R.drawable.ic_lock_power_off, "Power", actionIntent(appContext, ACTION_POWER, 1))
            .addAction(android.R.drawable.ic_lock_silent_mode, "Mute", actionIntent(appContext, ACTION_MUTE, 2))
            .addAction(android.R.drawable.arrow_up_float, "Vol +", actionIntent(appContext, ACTION_VOLUME_UP, 3))
            .addAction(android.R.drawable.arrow_down_float, "Vol -", actionIntent(appContext, ACTION_VOLUME_DOWN, 4))
            .addAction(android.R.drawable.ic_media_play, "Play/Pause", actionIntent(appContext, ACTION_PLAY_PAUSE, 5))
            .build()

        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
    }

    fun handleNotificationAction(context: Context, action: String?) {
        val key = when (action) {
            ACTION_POWER -> RemoteKey.POWER
            ACTION_MUTE -> RemoteKey.MUTE
            ACTION_VOLUME_UP -> RemoteKey.VOLUME_UP
            ACTION_VOLUME_DOWN -> RemoteKey.VOLUME_DOWN
            ACTION_PLAY_PAUSE -> RemoteKey.PLAY_PAUSE
            else -> return
        }

        val appContext = context.applicationContext
        val repository = getRepository(appContext)
        scope.launch {
            if (repository.activeDevice.value?.isConnected != true) {
                repository.reconnectActiveDevice()
            }
            repository.sendRemoteKey(key)
            showRemoteNotification(appContext)
        }
    }

    fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Remote controls",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Quick TV controls for the notification shade and lock screen"
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        manager?.createNotificationChannel(channel)
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, RemoteNotificationReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
