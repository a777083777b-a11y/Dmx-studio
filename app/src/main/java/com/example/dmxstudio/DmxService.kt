package com.example.dmxstudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

// Keeps Art-Net output, effects, shows and the schedule running with the screen off
class DmxService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Studio.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Studio.background = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CH, "DMX Studio", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DmxService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setContentTitle("DMX Studio")
            .setContentText(L("Art-Net yuborilmoqda (fon rejimi)", "Art-Net передаётся (фоновый режим)", "Sending Art-Net (background)"))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, L("To'xtatish", "Стоп", "Stop"), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        Studio.background = true
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dmxstudio:out").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm?.createWifiLock(mode, "dmxstudio:wifi")?.also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            wake?.release()
        } catch (e: Exception) {
        }
        try {
            wifiLock?.release()
        } catch (e: Exception) {
        }
        wake = null
        wifiLock = null
        Studio.background = false
        super.onDestroy()
    }

    companion object {
        const val CH = "dmx"
        const val ACTION_STOP = "stop"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DmxService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DmxService::class.java).setAction(ACTION_STOP))
        }
    }
}

// After a reboot, start again if the schedule (LSM) or background mode was on
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Studio.start(context.applicationContext)
        if (Engine.schedOn || Studio.backgroundWanted) {
            try {
                DmxService.start(context)
            } catch (e: Exception) {
            }
        }
    }
}
