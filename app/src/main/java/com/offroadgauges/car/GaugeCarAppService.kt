package com.offroadgauges.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class GaugeCarAppService : CarAppService() {

    // For personal use/testing it accepts any host. For a public release,
    // replace with a list of trusted hosts (HostValidator.Builder).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = GaugeSession()
}
