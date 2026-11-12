package com.offroadgauges.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class GaugeCarAppService : CarAppService() {

    // Per uso personale/test accetta qualsiasi host. Per una distribuzione pubblica
    // sostituire con una lista di host fidati (HostValidator.Builder).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = GaugeSession()
}
