package com.dilinkauto.client.auto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.RootManager

/**
 * Reads the current Android Auto registration status from the device.
 *
 * Each check is independent and non-blocking. When root is unavailable,
 * checks that require root report false instead of failing.
 */
object AASelfTweakerStatus {

    private const val TAG = "AASelfTweakerStatus"

    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val GMS_CAR_PACKAGE = "com.google.android.gms.car"
    private const val PHENOTYPE_DB_PATH =
        "/data/data/com.google.android.gms/databases/phenotype.db"

    private const val VENDING_PACKAGE = "com.android.vending"
    private const val LOCAL_APP_STATE_DB_PATH =
        "/data/data/com.android.vending/databases/localappstate.db"

    private const val FLAG_APP_WHITE_LIST = "app_white_list"

    data class Result(
        val rootAvailable: Boolean,
        val installerCorrect: Boolean,
        val finskyRowsPresent: Boolean,
        val phenotypeFlagsApplied: Boolean,
        val overallReady: Boolean,
        val message: String
    )

    /**
     * Runs all checks and returns a [Result].
     *
     * Safe to call from the main thread — all blocking I/O runs on a
     * background thread via RootManager.execFull.
     */
    fun check(context: Context): Result {
        val ourPackage = context.packageName
        val rootAvailable = RootManager.isAvailable

        val installerCorrect = checkInstaller(context, ourPackage)
        val finskyRowsPresent = if (rootAvailable) checkFinskyRows(ourPackage) else false
        val phenotypeFlagsApplied = if (rootAvailable) checkPhenotypeFlags(ourPackage) else false

        val overallReady = rootAvailable &&
            installerCorrect &&
            finskyRowsPresent &&
            phenotypeFlagsApplied

        val message = when {
            !rootAvailable -> "Root required for auto-registration"
            !installerCorrect -> "Installer not set to Play Store"
            !phenotypeFlagsApplied -> "Phenotype flags missing"
            !finskyRowsPresent -> "Play Store rows missing"
            else -> "Android Auto registration complete"
        }

        FileLog.i(TAG, "Status: root=$rootAvailable installer=$installerCorrect " +
                "finsky=$finskyRowsPresent phenotype=$phenotypeFlagsApplied -> $message")

        return Result(
            rootAvailable = rootAvailable,
            installerCorrect = installerCorrect,
            finskyRowsPresent = finskyRowsPresent,
            phenotypeFlagsApplied = phenotypeFlagsApplied,
            overallReady = overallReady,
            message = message
        )
    }

    /**
     * Checks whether our package's installer is com.android.vending.
     */
    @Suppress("DEPRECATION")
    private fun checkInstaller(context: Context, pkg: String): Boolean {
        return try {
            val pm = context.packageManager
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                pm.getInstallerPackageName(pkg)
            }
            val ok = installer == VENDING_PACKAGE
            if (!ok) {
                FileLog.i(TAG, "Installer check: '$installer' (expected $VENDING_PACKAGE)")
            }
            ok
        } catch (t: Throwable) {
            FileLog.e(TAG, "Installer check failed", t)
            false
        }
    }

    /**
     * Checks whether our package has an appstate row in localappstate.db.
     */
    private fun checkFinskyRows(pkg: String): Boolean {
        val (exit, out, err) = RootManager.execFull(
            "sqlite3 $LOCAL_APP_STATE_DB_PATH \"SELECT COUNT(*) FROM appstate WHERE package_name='$pkg';\"",
            timeoutSec = 10
        )
        return if (exit == 0 && out.trim() == "1") {
            true
        } else {
            FileLog.i(TAG, "Finsky check: exit=$exit out='${out.trim()}' err='${err.trim()}'")
            false
        }
    }

    /**
     * Checks whether our package appears in the app_white_list flag override
     * in the GMS phenotype database.
     */
    private fun checkPhenotypeFlags(pkg: String): Boolean {
        // Try new schema first (flag_overrides), then legacy (FlagOverrides).
        val (newExit, newOut, _) = RootManager.execFull(
            "sqlite3 $PHENOTYPE_DB_PATH \"SELECT value FROM flag_overrides " +
                "WHERE name='$FLAG_APP_WHITE_LIST' AND active=1 LIMIT 1;\"",
            timeoutSec = 10
        )
        if (newExit == 0 && newOut.contains(pkg)) {
            return true
        }

        val (oldExit, oldOut, _) = RootManager.execFull(
            "sqlite3 $PHENOTYPE_DB_PATH \"SELECT stringVal FROM FlagOverrides " +
                "WHERE packageName='$GMS_CAR_PACKAGE' AND name='$FLAG_APP_WHITE_LIST' LIMIT 1;\"",
            timeoutSec = 10
        )
        if (oldExit == 0 && oldOut.contains(pkg)) {
            return true
        }

        FileLog.i(TAG, "Phenotype check: new(exit=$newExit out='${newOut.trim()}') " +
                "old(exit=$oldExit out='${oldOut.trim()}')")
        return false
    }
}