package com.dilinkauto.protocol

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug

/**
 * Shared device-info block for diagnostics.
 *
 * Both the phone-side [com.dilinkauto.client.service.ConnectionService.logDeviceInfo]
 * and the car-side [com.dilinkauto.server.CarCrashHandler] build the same
 * "── Device Info ──" block to log at startup or on crash. Centralizing it
 * here means a new Build field or format change lands in one place — the
 * two sides previously carried byte-identical copies that could drift.
 *
 * Pure JVM + Android-Build only; no UI/framework dependencies so both
 * modules can call it without extra coupling.
 *
 * @param includeMemory when true, append a "── Memory ──" section
 *   (car crash reports include it; the phone startup log does not)
 */
object DeviceInfo {
    fun buildDeviceInfoBlock(context: Context, includeMemory: Boolean = false): String {
        val sb = StringBuilder()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val dm = context.resources.displayMetrics

        sb.appendLine("── Device Info ──")
        sb.appendLine("model=${Build.MODEL} manufacturer=${Build.MANUFACTURER}")
        sb.appendLine("product=${Build.PRODUCT} device=${Build.DEVICE}")
        sb.appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        sb.appendLine("display=${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi density=${dm.density}")
        sb.appendLine("cores=${Runtime.getRuntime().availableProcessors()}")
        sb.appendLine("abi=${Build.SUPPORTED_ABIS?.joinToString(",") ?: "?"}")
        if (includeMemory) {
            sb.appendLine("heapMax=${Runtime.getRuntime().maxMemory()} heapTotal=${Runtime.getRuntime().totalMemory()} heapFree=${Runtime.getRuntime().freeMemory()}")
            sb.appendLine("totalMem=${mi.totalMem} availMem=${mi.availMem} lowMemory=${mi.lowMemory}")
            sb.appendLine("nativeHeap=${Debug.getNativeHeapAllocatedSize()} nativeFree=${Debug.getNativeHeapFreeSize()}")
        }
        return sb.toString()
    }
}
