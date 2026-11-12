package com.offroadgauges.car

import android.Manifest
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template

/** Mostrata solo se il permesso di posizione non e' ancora stato concesso. */
class PermissionScreen(
    carContext: CarContext,
    private val onGranted: () -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val ask = Action.Builder()
            .setTitle("Concedi sul telefono")
            .setOnClickListener(ParkedOnlyOnClickListener.create {
                carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted, _ ->
                    if (granted.isNotEmpty()) onGranted()
                }
            })
            .build()

        return MessageTemplate.Builder("Per bussola, altimetro e meteo serve il permesso di posizione.")
            .setTitle("Permesso necessario")
            .setHeaderAction(Action.APP_ICON)
            .addAction(ask)
            .build()
    }
}
