package com.dilinkauto.client.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
import com.dilinkauto.client.ShizukuManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class Available(val version: String, val sizeBytes: Long) : UpdateState()
    data class Downloading(val progress: Int, val version: String) : UpdateState()
    data class ReadyToInstall(val file: File, val version: String) : UpdateState()
    data class Installing(val version: String) : UpdateState()
    data object Installed : UpdateState()
    data class UpToDate(val version: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

enum class DistributionChannel { RELEASE, PRE_RELEASE }

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val GITHUB_REPO = "https://api.github.com/repos/andersonlucasg3/dilink-auto-android/releases"
    private const val COOLDOWN_MS = 6 * 3600 * 1000L
    private const val PREFS_NAME = "dilinkauto_update"
    private const val KEY_LAST_CHECK = "last_check_timestamp"
    private const val KEY_CHANNEL = "distribution_channel"

    var channel: DistributionChannel
        get() = when (prefs.getString(KEY_CHANNEL, "release")) {
            "pre_release" -> DistributionChannel.PRE_RELEASE
            else -> DistributionChannel.RELEASE
        }
        set(value) = prefs.edit().putString(KEY_CHANNEL,
            if (value == DistributionChannel.PRE_RELEASE) "pre_release" else "release").apply()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private var latestRelease: ReleaseInfo? = null
    private var downloadedFile: File? = null
    private val isDownloading = AtomicBoolean(false)

    private data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val apkUrl: String,
        val apkSize: Long
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Clean partial downloads from previous sessions
        try {
            File(appContext.filesDir, "update.apk.tmp").delete()
        } catch (_: Exception) {}
    }

    fun checkForUpdate(force: Boolean = false) {
        if (_updateState.value is UpdateState.Checking) return
        if (_updateState.value is UpdateState.Downloading) return

        if (!force) {
            val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
            if (System.currentTimeMillis() - lastCheck < COOLDOWN_MS) return
        }

        _updateState.value = UpdateState.Checking

        scope.launch(Dispatchers.IO) {
            try {
                val release = fetchLatestRelease() ?: run {
                    _updateState.value = UpdateState.Error(appContext.getString(R.string.update_no_release))
                    return@launch
                }

                val currentVersion = readCurrentVersion()
                if (currentVersion == null) {
                    FileLog.w(TAG, "Could not read current version")
                    _updateState.value = UpdateState.Error(appContext.getString(R.string.update_no_version))
                    return@launch
                }

                if (compareVersions(release.versionName, currentVersion) > 0) {
                    FileLog.i(TAG, "Update available: $currentVersion -> ${release.versionName}")
                    latestRelease = release
                    _updateState.value = UpdateState.Available(release.versionName, release.apkSize)
                } else {
                    FileLog.d(TAG, "Up to date: $currentVersion")
                    _updateState.value = UpdateState.UpToDate(currentVersion)
                }

                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                FileLog.e(TAG, "Update check failed", e)
                _updateState.value = UpdateState.Error(e.message ?: "Update check failed")
            }
        }
    }

    fun downloadUpdate() {
        val release = latestRelease ?: return
        if (_updateState.value !is UpdateState.Available) return
        if (!isDownloading.compareAndSet(false, true)) return

        _updateState.value = UpdateState.Downloading(0, release.versionName)
        _downloadProgress.value = 0

        scope.launch(Dispatchers.IO) {
            val tmpFile = File(appContext.filesDir, "update.apk.tmp")
            val outFile = File(appContext.filesDir, "update.apk")

            try {
                tmpFile.delete()
                outFile.delete()

                val url = URL(release.apkUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.requestMethod = "GET"
                conn.connect()

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    _updateState.value = UpdateState.Error(appContext.getString(R.string.update_download_http_error, conn.responseCode))
                    return@launch
                }

                val totalSize = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                val input = BufferedInputStream(conn.inputStream)
                val output = FileOutputStream(tmpFile)

                val buffer = ByteArray(8192)
                var downloaded = 0L
                var bytesRead: Int

                try {
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0) {
                            val pct = (downloaded * 100 / totalSize).toInt()
                            _downloadProgress.value = pct
                            if (downloaded % (512 * 1024) < 8192) { // update UI ~every 512KB
                                _updateState.value = UpdateState.Downloading(pct, release.versionName)
                            }
                        }
                    }
                } finally {
                    output.close()
                    input.close()
                    conn.disconnect()
                }

                // Verify APK
                val pkgInfo = appContext.packageManager.getPackageArchiveInfo(
                    tmpFile.absolutePath, 0
                )
                if (pkgInfo == null || pkgInfo.packageName != appContext.packageName) {
                    tmpFile.delete()
                    _updateState.value = UpdateState.Error(appContext.getString(R.string.update_invalid_apk))
                    return@launch
                }

                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.copyTo(outFile, overwrite = true)
                    tmpFile.delete()
                }

                downloadedFile = outFile
                _updateState.value = UpdateState.ReadyToInstall(outFile, release.versionName)
                FileLog.i(TAG, "Downloaded ${release.versionName} (${outFile.length()} bytes)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLog.e(TAG, "Download failed", e)
                tmpFile.delete()
                _updateState.value = UpdateState.Error(appContext.getString(R.string.update_download_failed, e.message ?: "unknown"))
            } finally {
                isDownloading.set(false)
            }
        }
    }

    fun installUpdate(context: Context) {
        val apkFile = downloadedFile
        if (apkFile == null || !apkFile.exists()) {
            _updateState.value = UpdateState.Error(appContext.getString(R.string.update_file_not_found))
            return
        }

        val version = latestRelease?.versionName ?: ""

        if (ShizukuManager.isAvailable) {
            // Silent install via Shizuku — no system confirmation dialog
            _updateState.value = UpdateState.Installing(version)
            scope.launch(Dispatchers.IO) {
                try {
                    val result = ShizukuManager.execAndWait("pm install -r ${apkFile.absolutePath}")
                    if (result != null && result.contains("Success")) {
                        FileLog.i(TAG, "Shizuku install succeeded: $result")
                        _updateState.value = UpdateState.Installed
                        downloadedFile?.delete()
                        downloadedFile = null
                        latestRelease = null
                    } else {
                        val msg = result ?: "Shizuku command returned null"
                        FileLog.w(TAG, "Shizuku install failed: $msg")
                        _updateState.value = UpdateState.Error(appContext.getString(R.string.update_installer_error, msg.take(120)))
                    }
                } catch (e: Exception) {
                    FileLog.e(TAG, "Shizuku install error", e)
                    _updateState.value = UpdateState.Error(appContext.getString(R.string.update_installer_error, e.message ?: "unknown"))
                }
            }
        } else {
            // Fallback to system package installer
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                FileLog.e(TAG, "Install intent failed", e)
                _updateState.value = UpdateState.Error(appContext.getString(R.string.update_installer_error, e.message ?: "unknown"))
            }
        }
    }

    fun reset() {
        latestRelease = null
        downloadedFile?.delete()
        downloadedFile = null
        isDownloading.set(false)
        _updateState.value = UpdateState.Idle
        _downloadProgress.value = 0
    }

    // ─── Internal ───

    private fun getApiUrl(): String = when (channel) {
        DistributionChannel.RELEASE -> "$GITHUB_REPO/latest"
        DistributionChannel.PRE_RELEASE -> "$GITHUB_REPO?per_page=1"
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val url = URL(getApiUrl())
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", "DiLink-Auto-Client")
        conn.connect()

        if (conn.responseCode == 403 || conn.responseCode == 429) {
            FileLog.w(TAG, "GitHub API rate limited (HTTP ${conn.responseCode})")
            _updateState.value = UpdateState.Error(appContext.getString(R.string.update_rate_limit))
            return null
        }

        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            FileLog.w(TAG, "GitHub API returned HTTP ${conn.responseCode}")
            return null
        }

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        // releases/latest returns a single object; releases?per_page=1 returns an array
        val json = if (body.trimStart().startsWith("[")) {
            val arr = org.json.JSONArray(body)
            if (arr.length() == 0) return null
            arr.getJSONObject(0)
        } else {
            JSONObject(body)
        }
        val tagName = json.getString("tag_name")
        val versionName = tagName.removePrefix("v")

        // Find APK asset
        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (name.endsWith(".apk")) {
                apkUrl = asset.getString("browser_download_url")
                apkSize = asset.getLong("size")
                break
            }
        }

        if (apkUrl == null) {
            FileLog.w(TAG, "No APK asset found in release $tagName")
            return null
        }

        return ReleaseInfo(tagName, versionName, apkUrl, apkSize)
    }

    private fun readCurrentVersion(): String? {
        return try {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            info.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    // "0.17.0-dev-02" → ("0.17.0", true, 2)
    // "0.17.0-dev"    → ("0.17.0", true, 0)
    // "0.17.0"        → ("0.17.0", false, 0)
    internal data class ParsedVersion(val base: String, val isDev: Boolean, val devNum: Int)

    internal fun parseVersion(v: String): ParsedVersion {
        val m = Regex("^(.*)-dev(?:-(\\d+))?\$").find(v)
        return if (m != null) {
            ParsedVersion(m.groupValues[1], true, m.groupValues[2].toIntOrNull() ?: 0)
        } else {
            ParsedVersion(v, false, 0)
        }
    }

    internal fun compareVersions(a: String, b: String): Int {
        val (baseA, devA, numA) = parseVersion(a)
        val (baseB, devB, numB) = parseVersion(b)
        val aParts = baseA.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = baseB.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(aParts.size, bParts.size)
        for (i in 0 until maxLen) {
            val aVal = aParts.getOrElse(i) { 0 }
            val bVal = bParts.getOrElse(i) { 0 }
            if (aVal != bVal) return aVal.compareTo(bVal)
        }
        if (!devA && devB) return 1
        if (devA && !devB) return -1
        return numA.compareTo(numB)
    }
}
