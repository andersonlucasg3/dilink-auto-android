package com.dilinkauto.client

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.dilinkauto.client.service.ConnectionService
import com.dilinkauto.client.ui.BackStack
import com.dilinkauto.client.ui.DiLinkAutoTheme
import com.dilinkauto.client.ui.MainScreen
import com.dilinkauto.client.ui.OnboardingScreen
import com.dilinkauto.client.ui.PermissionIntents
import com.dilinkauto.client.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) { prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply() }

    /**
     * Explicit "user stopped the service" flag (audit A-L21).
     *
     * Persisted, not in-memory, on purpose: the resurrection path this guards
     * is PhoneDisplayRestorer launching MainActivity with
     * FLAG_TURN_SCREEN_ON after a teardown — a *fresh* Activity instance, so
     * an in-memory flag would be back to its default (false) by the time
     * onCreate ran. Lives in this Activity's own prefs file: nothing outside
     * the module needs it yet. (The notification's stop action goes straight
     * to ConnectionService, which cannot write an Activity-owned flag; if the
     * two stop entry points ever need to share the state, the key belongs in
     * AppPrefs so the service can clear it too.)
     */
    private var userStopped: Boolean
        get() = prefs.getBoolean(KEY_USER_STOPPED, false)
        set(value) { prefs.edit().putBoolean(KEY_USER_STOPPED, value).apply() }

    /**
     * Top-level screen routing (audit R3-SRP-09) — replaces the previous
     * onboarding/settings/allowlist boolean trio with a single sealed state.
     */
    private sealed interface Screen {
        data object Onboarding : Screen
        data object Main : Screen
        data object Settings : Screen
        data object Allowlist : Screen
    }

    private val navigation = BackStack<Screen>(Screen.Main)
    private val screen: Screen get() = navigation.current

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screen lock while streaming — HyperOS may override system timeout settings
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        navigation.reset(if (onboardingCompleted) Screen.Main else Screen.Onboarding)

        // Auto-start the service when the app is opened (e.g. by the car via USB ADB).
        // Only if onboarding is done, the service isn't already running, and the user
        // has not explicitly stopped it — calling startForegroundService on an
        // already-running service is harmless but noisy. The stop flag is what keeps
        // a restore-launched recreate (FLAG_TURN_SCREEN_ON from
        // PhoneDisplayRestorer) from resurrecting a service the user just stopped
        // (audit A-L21).
        if (onboardingCompleted && !userStopped &&
            ConnectionService.serviceState.value == com.dilinkauto.client.service.SessionState.IDLE
        ) {
            startConnectionService()
        }

        setContent {
            DiLinkAutoTheme {
                val installStatus by ConnectionService.installStatusFlow.collectAsState()
                // 子页吃系统返回；Main 不吃，保留「主界面返回 = 退出 App」的既有语义。
                BackHandler(enabled = screen == Screen.Settings || screen == Screen.Allowlist) {
                    navigation.back()
                }
                when (screen) {
                    Screen.Onboarding -> OnboardingScreen(
                        onComplete = {
                            onboardingCompleted = true
                            navigation.reset(Screen.Main)
                        },
                        onInstallOnCar = { installOnCar(null) },
                        installStatus = installStatus
                    )
                    Screen.Allowlist -> AllowlistScreen(onBack = { navigation.back() })
                    Screen.Settings -> SettingsScreen(
                        onBack = { navigation.back() },
                        onOpenAllFilesAccess = { PermissionIntents.openAllFilesAccess(this) },
                        onOpenBatteryExemption = { PermissionIntents.openBatteryExemption(this) },
                        onOpenAccessibility = { PermissionIntents.openAccessibilitySettings(this) },
                        onOpenDeveloperOptions = { PermissionIntents.openDeveloperOptions(this) },
                        onOpenAllowlist = { navigation.navigate(Screen.Allowlist) }
                    )
                    Screen.Main -> MainScreen(
                        onStartService = { startConnectionService() },
                        onStopService = { stopConnectionService() },
                        onInstallOnCar = { ip -> installOnCar(ip) },
                        onOpenSettings = { navigation.navigate(Screen.Settings) },
                        onShareLogs = { LogSharer.share(this) }
                    )
                }
            }
        }
    }

    private fun startConnectionService() {
        userStopped = false
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopConnectionService() {
        userStopped = true
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_STOP
        }
        startService(intent)
    }

    private fun installOnCar(ip: String? = null) {
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_INSTALL_CAR
            if (!ip.isNullOrBlank()) putExtra("car_ip", ip)
        }
        startService(intent)
    }

    companion object {
        private const val PREFS_NAME = "dilinkauto_onboarding"
        private const val KEY_ONBOARDING_DONE = "has_completed_onboarding"
        private const val KEY_USER_STOPPED = "user_stopped_service"
    }
}
