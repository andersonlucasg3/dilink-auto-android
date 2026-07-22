package com.dilinkauto.client.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for the Android Auto mode: the stock AA host binds here and
 * gets a session that mirrors the phone's virtual display into the
 * NavigationTemplate surface. Sideloaded app — accept any host (DHU, BYD).
 */
class DilinkCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return MirrorSession()
    }
}
