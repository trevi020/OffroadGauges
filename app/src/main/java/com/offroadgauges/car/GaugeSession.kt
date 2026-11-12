package com.offroadgauges.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.offroadgauges.core.SensorHub
import com.offroadgauges.core.TelemetryService

class GaugeSession : Session() {

    private var serviceStarted = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                SensorHub.acquire(carContext)
                startTelemetryService()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (serviceStarted) {
                    carContext.stopService(Intent(carContext, TelemetryService::class.java))
                    serviceStarted = false
                }
                SensorHub.release()
            }
        })
    }

    private fun startTelemetryService() {
        try {
            ContextCompat.startForegroundService(carContext, Intent(carContext, TelemetryService::class.java))
            serviceStarted = true
        } catch (e: Exception) {
            // Background start not allowed: the sensors stay active through the Session.
        }
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val granted = carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return GaugeScreen(carContext)

        lateinit var permScreen: PermissionScreen
        permScreen = PermissionScreen(carContext) {
            SensorHub.restartLocation()
            startTelemetryService()
            val sm = carContext.getCarService(ScreenManager::class.java)
            sm.push(GaugeScreen(carContext))
            sm.remove(permScreen)
        }
        return permScreen
    }
}
