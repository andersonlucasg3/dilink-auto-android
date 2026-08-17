package com.dilinkauto.client.auto

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

    private const val WORK_APPSTATE_DB_NAME = "status_appstate_work.db"
    private const val WORK_PHENOTYPE_DB_NAME = "status_phenotype_work.db"

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
     * Safe to call from the main thread — all blocking I/O runs on
     * Dispatchers.IO.
     */
    suspend fun check(context: Context): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val ourPackage = appContext.packageName
        val rootAvailable = RootManager.isAvailable

        val installerCorrect = checkInstaller(appContext, ourPackage)
        val finskyRowsPresent = if (rootAvailable) checkFinskyRows(appContext, ourPackage) else false
        val phenotypeFlagsApplied = if (rootAvailable) checkPhenotypeFlags(appContext, ourPackage) else false

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

        Result(
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
     *
     * Copies the db to the app's private dir, opens with SQLiteDatabase,
     * queries, then cleans up. Uses RootManager.execFull only for cp/chmod.
     */
    private fun checkFinskyRows(context: Context, pkg: String): Boolean {
        val workDb = File(context.filesDir, WORK_APPSTATE_DB_NAME)
        return try {
            if (!copyDbRootToLocal(LOCAL_APP_STATE_DB_PATH, workDb)) {
                FileLog.i(TAG, "Finsky check: could not copy localappstate.db")
                return false
            }

            val db = SQLiteDatabase.openDatabase(
                workDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            try {
                if (!tableExists(db, "appstate")) {
                    FileLog.i(TAG, "Finsky check: 'appstate' table missing")
                    return false
                }
                val count = queryCount(db, "SELECT COUNT(*) FROM appstate WHERE package_name=?", arrayOf(pkg))
                val ok = count == 1L
                if (!ok) {
                    FileLog.i(TAG, "Finsky check: count=$count for '$pkg'")
                }
                ok
            } finally {
                runCatching { db.close() }
            }
        } catch (t: Throwable) {
            FileLog.e(TAG, "Finsky check failed", t)
            false
        } finally {
            deleteWorkDb(workDb)
        }
    }

    /**
     * Checks whether our package appears in the app_white_list flag override
     * in the GMS phenotype database.
     *
     * Copies the db to the app's private dir, opens with SQLiteDatabase,
     * checks both new (flag_overrides) and legacy (FlagOverrides) schemas,
     * then cleans up. Uses RootManager.execFull only for cp/chmod.
     */
    private fun checkPhenotypeFlags(context: Context, pkg: String): Boolean {
        val workDb = File(context.filesDir, WORK_PHENOTYPE_DB_NAME)
        return try {
            if (!copyDbRootToLocal(PHENOTYPE_DB_PATH, workDb)) {
                FileLog.i(TAG, "Phenotype check: could not copy phenotype.db")
                return false
            }

            val db = SQLiteDatabase.openDatabase(
                workDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            try {
                val hasNewSchema = tableExists(db, "flag_overrides")
                val hasLegacySchema = tableExists(db, "FlagOverrides")

                when {
                    hasNewSchema -> {
                        val ok = isPackageWhitelistedNewSchema(db, pkg)
                        if (!ok) {
                            FileLog.i(TAG, "Phenotype check (new schema): '$pkg' not in $FLAG_APP_WHITE_LIST")
                        }
                        ok
                    }
                    hasLegacySchema -> {
                        val ok = isPackageWhitelistedLegacy(db, pkg)
                        if (!ok) {
                            FileLog.i(TAG, "Phenotype check (legacy): '$pkg' not in $FLAG_APP_WHITE_LIST")
                        }
                        ok
                    }
                    else -> {
                        FileLog.i(TAG, "Phenotype check: no known overrides table")
                        false
                    }
                }
            } finally {
                runCatching { db.close() }
            }
        } catch (t: Throwable) {
            FileLog.e(TAG, "Phenotype check failed", t)
            false
        } finally {
            deleteWorkDb(workDb)
        }
    }

    // ---- Helpers (same pattern as AASelfTweaker) ----------------------------

    /**
     * Copies a db from a root-only path into [workDb] using a single su session,
     * then chmod 666 so our process can read it.
     */
    private fun copyDbRootToLocal(src: String, workDb: File): Boolean {
        val cmd = "cp $src ${workDb.absolutePath}; " +
            "cp ${src}-wal ${workDb.absolutePath}-wal 2>/dev/null; " +
            "cp ${src}-shm ${workDb.absolutePath}-shm 2>/dev/null; " +
            "chmod 666 ${workDb.absolutePath} ${workDb.absolutePath}-wal ${workDb.absolutePath}-shm 2>/dev/null; " +
            "true"
        val (exit, out, err) = RootManager.execFull(cmd)
        val ok = workDb.canRead()
        if (!ok) {
            FileLog.e(TAG, "copyDbRootToLocal failed (exit=$exit): out='${out.trim()}' err='${err.trim()}'")
        }
        return ok
    }

    /** Deletes a work db copy plus its WAL/SHM companions. */
    private fun deleteWorkDb(workDb: File) {
        runCatching { workDb.delete() }
        runCatching { File(workDb.absolutePath + "-wal").delete() }
        runCatching { File(workDb.absolutePath + "-shm").delete() }
    }

    /** True when [table] exists in the database. */
    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)
        ).use { return it.moveToFirst() }
    }

    /** Runs a COUNT query and returns the result, or -1 on failure. */
    private fun queryCount(db: SQLiteDatabase, sql: String, args: Array<String>): Long {
        return runCatching {
            db.rawQuery(sql, args).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }.getOrElse {
            FileLog.e(TAG, "queryCount failed: $sql", it)
            -1L
        }
    }

    // ---- New schema (flag_overrides) checks --------------------------------

    private fun resolveConfigPackageId(db: SQLiteDatabase, packagePrefix: String): Long? {
        return runCatching {
            db.rawQuery(
                "SELECT config_package_id, name FROM config_packages WHERE name LIKE ?",
                arrayOf("$packagePrefix%")
            ).use { cursor ->
                var firstId: Long? = null
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)
                    if (firstId == null) firstId = id
                    if (name == packagePrefix) return@use id
                }
                firstId
            }
        }.getOrElse {
            FileLog.e(TAG, "resolveConfigPackageId failed for '$packagePrefix'", it)
            null
        }
    }

    private fun readNewOverrideValue(
        db: SQLiteDatabase, configPackageId: Long, name: String
    ): String? {
        return runCatching {
            db.rawQuery(
                "SELECT value FROM flag_overrides " +
                    "WHERE config_package_id=? AND account_id=0 AND active=1 AND name=?",
                arrayOf(configPackageId.toString(), name)
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrElse {
            FileLog.e(TAG, "readNewOverrideValue failed for '$name'", it)
            null
        }
    }

    private fun isPackageWhitelistedNewSchema(db: SQLiteDatabase, pkg: String): Boolean {
        val gmsCarId = resolveConfigPackageId(db, GMS_CAR_PACKAGE) ?: return false
        val current = readNewOverrideValue(db, gmsCarId, FLAG_APP_WHITE_LIST) ?: return false
        return current.split(',').any { it.trim() == pkg }
    }

    // ---- Legacy schema (FlagOverrides) checks -------------------------------

    private fun readLegacyOverrideValue(db: SQLiteDatabase, packageName: String, name: String): String? {
        return runCatching {
            db.rawQuery(
                "SELECT stringVal FROM FlagOverrides WHERE packageName=? AND name=?",
                arrayOf(packageName, name)
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrElse {
            FileLog.e(TAG, "readLegacyOverrideValue failed for '$name'", it)
            null
        }
    }

    private fun isPackageWhitelistedLegacy(db: SQLiteDatabase, pkg: String): Boolean {
        val current = readLegacyOverrideValue(db, GMS_CAR_PACKAGE, FLAG_APP_WHITE_LIST) ?: return false
        return current.split(',').any { it.trim() == pkg }
    }
}