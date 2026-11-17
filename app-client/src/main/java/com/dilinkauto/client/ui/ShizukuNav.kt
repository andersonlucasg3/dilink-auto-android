package com.dilinkauto.client.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Handles navigation to the Shizuku application and store/download page.
 *
 * Extracted from ShizukuManager (SRP): isolates Android UI/Activity launch concerns
 * from the low-level Shizuku IPC lifecycle and process execution.
 */
object ShizukuNav {
    private const val TAG = "ShizukuNav"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_WEB_URL = "https://github.com/RikkaApps/Shizuku/releases"

    /**
     * Open the Shizuku app so the user can manage authorization manually.
     */
    fun openShizukuApp(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Shizuku: ${e.message}")
        }
    }

    /**
     * Send the user somewhere they can install Shizuku (audit UX-13: the
     * not-installed settings row looked tappable and silently did nothing).
     *
     * Prefers the local store app via the `market:` scheme — that resolves on
     * Chinese ROMs without Google Play — and falls back to the project's
     * GitHub releases, which is also where the ADB start-up instructions live.
     */
    fun openShizukuStorePage(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
            return
        } catch (e: Exception) {
            Log.w(TAG, "No market app for Shizuku, opening web page: ${e.message}")
        }
        try {
            val web = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_WEB_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(web)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Shizuku install page: ${e.message}")
        }
    }
}
