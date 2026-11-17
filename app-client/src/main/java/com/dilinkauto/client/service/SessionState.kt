package com.dilinkauto.client.service

/**
 * Lifecycle states of the client car connection session.
 *
 * Extracted from [ConnectionService] (SRP) to decouple session lifecycle
 * state definition from the Android Service host.
 */
enum class SessionState {
    IDLE,
    WAITING,
    CONNECTED,
    STREAMING;

    val isRunning: Boolean
        get() = this != IDLE
}
