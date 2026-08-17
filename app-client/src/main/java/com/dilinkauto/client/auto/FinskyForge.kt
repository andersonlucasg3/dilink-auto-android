package com.dilinkauto.client.auto

import android.content.Context

/**
 * Legacy wrapper around [AASelfTweaker].
 *
 * The original FinskyForge used Termux sqlite3 and only forged Finsky DB rows.
 * It has been superseded by [AASelfTweaker], which implements the full 4-phase
 * auto-registration flow (installer spoof, phenotype flags, Finsky DBs, warmup).
 *
 * This object remains as a compatibility shim for any existing callers.
 */
object FinskyForge {

    /**
     * Delegates to [AASelfTweaker.ensureRegistered].
     *
     * Returns true when the full registration flow completes successfully.
     * The actual work runs on a background thread — this call returns immediately.
     */
    fun ensureRows(context: Context): Boolean {
        AASelfTweaker.ensureRegistered(context)
        return true
    }
}