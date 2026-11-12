package com.offroadgauges.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service (tipo "location"): tiene vivi GPS e sensori mentre Android Auto e' connesso,
 * anche se l'app sul telefono e' in background o lo schermo e' spento.
 */
class TelemetryService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "telemetry"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Telemetria", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Offroad Gauges attivo")
            .setContentText("GPS e sensori in uso per Android Auto")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            // Permesso di posizione mancante o avvio in background negato: i sensori restano comunque
            // gestiti dalla Session di Android Auto.
            stopSelf()
            return
        }
        SensorHub.acquire(this)
        acquired = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        if (acquired) SensorHub.release()
        super.onDestroy()
    }

    private var acquired = false
}
