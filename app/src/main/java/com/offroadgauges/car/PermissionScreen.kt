package com.offroadgauges.car

import android.Manifest
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template

/** Shown only if the location permission has not been granted yet. */
class PermissionScreen(
    carContext: CarContext,
    private val onGranted: () -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val ask = Action.Builder()
            .setTitle("Grant on the phone")
            .setOnClickListener(ParkedOnlyOnClickListener.create {
                carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted, _ ->
                    if (granted.isNotEmpty()) onGranted()
                }
            })
            .build()

        return MessageTemplate.Builder("Compass, altimeter and weather need the location permission.")
            .setTitle("Permission required")
            .setHeaderAction(Action.APP_ICON)
            .addAction(ask)
            .build()
    }
}
