package com.dilinkauto.vdserver

import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Process
import java.lang.reflect.Constructor

/**
 * Fake Context for use in app_process.
 * Mimics com.android.shell to get shell-level permissions.
 * Based on scrcpy's FakeContext + Workarounds (Apache 2.0 license).
 *
 * Uses ActivityThread to obtain a real system Context as the base,
 * so getSystemService() returns real service instances (UserManager, etc.).
 */
class FakeContext private constructor() : ContextWrapper(SYSTEM_CONTEXT) {

    companion object {
        private const val PACKAGE_NAME = "com.android.shell"

        private lateinit var ACTIVITY_THREAD: Any
        private lateinit var ACTIVITY_THREAD_CLASS: Class<*>

        private val SYSTEM_CONTEXT: Context by lazy {
            try {
                val method = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext")
                method.invoke(ACTIVITY_THREAD) as Context
            } catch (e: Exception) {
                System.err.println("[FakeContext] getSystemContext failed: ${e.message}")
                throw RuntimeException(e)
            }
        }

        init {
            try {
                if (android.os.Looper.getMainLooper() == null) {
                    android.os.Looper.prepareMainLooper()
                }

                ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread")

                val ctor = ACTIVITY_THREAD_CLASS.getDeclaredConstructor()
                ctor.isAccessible = true
                ACTIVITY_THREAD = ctor.newInstance()

                ACTIVITY_THREAD_CLASS
                    .getDeclaredField("sCurrentActivityThread")
                    .apply { isAccessible = true }
                    .set(null, ACTIVITY_THREAD)

                ACTIVITY_THREAD_CLASS
                    .getDeclaredField("mSystemThread")
                    .apply { isAccessible = true }
                    .setBoolean(ACTIVITY_THREAD, true)

                if (Build.VERSION.SDK_INT >= 31) {
                    fillConfigurationController()
                }

                fillAppInfo()
            } catch (e: Exception) {
                throw RuntimeException("Failed to initialize ActivityThread for FakeContext", e)
            }
        }

        private fun fillConfigurationController() {
            try {
                val configControllerClass = Class.forName("android.app.ConfigurationController")
                val activityThreadInternalClass = Class.forName("android.app.ActivityThreadInternal")
                val ctor = configControllerClass.getDeclaredConstructor(activityThreadInternalClass)
                ctor.isAccessible = true
                val configController = ctor.newInstance(ACTIVITY_THREAD)

                ACTIVITY_THREAD_CLASS
                    .getDeclaredField("mConfigurationController")
                    .apply { isAccessible = true }
                    .set(ACTIVITY_THREAD, configController)
            } catch (t: Throwable) {
                System.err.println("[FakeContext] fillConfigurationController failed (non-fatal): ${t.message}")
            }
        }

        private fun fillAppInfo() {
            try {
                val appBindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
                val ctor = appBindDataClass.getDeclaredConstructor()
                ctor.isAccessible = true
                val appBindData = ctor.newInstance()

                val appInfo = ApplicationInfo().apply { packageName = PACKAGE_NAME }

                appBindDataClass
                    .getDeclaredField("appInfo")
                    .apply { isAccessible = true }
                    .set(appBindData, appInfo)

                ACTIVITY_THREAD_CLASS
                    .getDeclaredField("mBoundApplication")
                    .apply { isAccessible = true }
                    .set(ACTIVITY_THREAD, appBindData)
            } catch (t: Throwable) {
                System.err.println("[FakeContext] fillAppInfo failed (non-fatal): ${t.message}")
            }
        }

        @JvmStatic
        fun get(): FakeContext = INSTANCE

        private val INSTANCE = FakeContext()
    }

    override fun getPackageName(): String = PACKAGE_NAME
    override fun getOpPackageName(): String = PACKAGE_NAME

    @TargetApi(31)
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.SHELL_UID).apply {
            setPackageName(PACKAGE_NAME)
        }.build()

    override fun getApplicationContext(): Context = this

    override fun createPackageContext(packageName: String, flags: Int): Context = this

    override fun getContentResolver() = baseContext?.contentResolver

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        PackageManager.PERMISSION_GRANTED

    override fun getSystemService(name: String): Any? = super.getSystemService(name)

    override fun getSystemServiceName(serviceClass: Class<*>): String? = when (serviceClass) {
        DisplayManager::class.java -> Context.DISPLAY_SERVICE
        android.view.WindowManager::class.java -> Context.WINDOW_SERVICE
        android.os.UserManager::class.java -> Context.USER_SERVICE
        else -> baseContext?.getSystemServiceName(serviceClass)
    }
}
