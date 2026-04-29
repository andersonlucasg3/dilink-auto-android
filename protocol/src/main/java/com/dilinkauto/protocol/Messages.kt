package com.dilinkauto.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializable protocol messages.
 * Uses simple binary encoding (no protobuf dependency for now — keeps APK small).
 * All multi-byte values are big-endian.
 */

// ─── Handshake ───

data class HandshakeRequest(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val deviceName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val supportedFeatures: Int = FEATURE_VIDEO or FEATURE_AUDIO or FEATURE_NOTIFICATIONS,
    val displayMode: Byte = DISPLAY_MODE_VIRTUAL,
    val screenDpi: Int = 160,
    val appVersionCode: Int,
    val targetFps: Int = 30
) {
    fun encode(): ByteArray {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 2 + nameBytes.size + 4 + 4 + 4 + 1 + 4 + 4 + 4)
            .order(ByteOrder.BIG_ENDIAN)
        buf.putInt(protocolVersion)
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)
        buf.putInt(screenWidth)
        buf.putInt(screenHeight)
        buf.putInt(supportedFeatures)
        buf.put(displayMode)
        buf.putInt(screenDpi)
        buf.putInt(appVersionCode)
        buf.putInt(targetFps)
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): HandshakeRequest {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val version = buf.getInt()
            val nameLen = buf.getShort().toInt()
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val request = HandshakeRequest(
                protocolVersion = version,
                deviceName = String(nameBytes, Charsets.UTF_8),
                screenWidth = buf.getInt(),
                screenHeight = buf.getInt(),
                supportedFeatures = buf.getInt(),
                displayMode = if (buf.hasRemaining()) buf.get() else DISPLAY_MODE_VIRTUAL,
                screenDpi = if (buf.remaining() >= 4) buf.getInt() else 160,
                appVersionCode = if (buf.remaining() >= 4) buf.getInt() else 0,
                targetFps = if (buf.remaining() >= 4) buf.getInt() else 30
            )
            return request
        }
    }
}

data class HandshakeResponse(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val accepted: Boolean,
    val deviceName: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val virtualDisplayId: Int = -1,
    val adbPort: Int = 5555,
    val vdServerJarPath: String = "",
    val connectionMethod: Byte = CONNECTION_METHOD_USB_ADB
) {
    fun encode(): ByteArray {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val jarPathBytes = vdServerJarPath.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 1 + 2 + nameBytes.size + 4 + 4 + 4 + 4 + 2 + jarPathBytes.size + 1)
            .order(ByteOrder.BIG_ENDIAN)
        buf.putInt(protocolVersion)
        buf.put(if (accepted) 1.toByte() else 0.toByte())
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)
        buf.putInt(displayWidth)
        buf.putInt(displayHeight)
        buf.putInt(virtualDisplayId)
        buf.putInt(adbPort)
        buf.putShort(jarPathBytes.size.toShort())
        buf.put(jarPathBytes)
        buf.put(connectionMethod)
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): HandshakeResponse {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val version = buf.getInt()
            val accepted = buf.get() != 0.toByte()
            val nameLen = buf.getShort().toInt()
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val dw = buf.getInt()
            val dh = buf.getInt()
            val vdId = if (buf.hasRemaining()) buf.getInt() else -1
            val adbP = if (buf.hasRemaining()) buf.getInt() else 5555
            val jarPath = if (buf.remaining() >= 2) {
                val pathLen = buf.getShort().toInt()
                if (pathLen > 0 && buf.remaining() >= pathLen) {
                    val pathBytes = ByteArray(pathLen)
                    buf.get(pathBytes)
                    String(pathBytes, Charsets.UTF_8)
                } else ""
            } else ""
            val connMethod = if (buf.hasRemaining()) buf.get() else CONNECTION_METHOD_USB_ADB
            return HandshakeResponse(
                protocolVersion = version,
                accepted = accepted,
                deviceName = String(nameBytes, Charsets.UTF_8),
                displayWidth = dw,
                displayHeight = dh,
                virtualDisplayId = vdId,
                adbPort = adbP,
                vdServerJarPath = jarPath,
                connectionMethod = connMethod
            )
        }
    }
}

// ─── Touch Input ───

data class TouchEvent(
    val action: Byte,  // TOUCH_DOWN, TOUCH_MOVE, TOUCH_UP
    val pointerId: Int,
    val x: Float,      // Normalized 0.0-1.0 (relative to display)
    val y: Float,      // Normalized 0.0-1.0
    val pressure: Float,
    val timestamp: Long
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
        buf.put(action)
        buf.putInt(pointerId)
        buf.putFloat(x)
        buf.putFloat(y)
        buf.putFloat(pressure)
        buf.putLong(timestamp)
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): TouchEvent {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            return TouchEvent(
                action = buf.get(),
                pointerId = buf.getInt(),
                x = buf.getFloat(),
                y = buf.getFloat(),
                pressure = buf.getFloat(),
                timestamp = buf.getLong()
            )
        }
    }
}

/** Batched MOVE: all pointers in a single message (reduces syscalls for multi-touch) */
data class TouchMoveBatch(val pointers: List<TouchEvent>) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(1 + pointers.size * 24) // count + N * (id+x+y+p+ts)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(pointers.size.toByte())
        for (p in pointers) {
            buf.putInt(p.pointerId)
            buf.putFloat(p.x)
            buf.putFloat(p.y)
            buf.putFloat(p.pressure)
            buf.putLong(p.timestamp)
        }
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): TouchMoveBatch {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val count = buf.get().toInt() and 0xFF
            val pointers = (0 until count).map {
                TouchEvent(
                    action = InputMsg.TOUCH_MOVE,
                    pointerId = buf.getInt(),
                    x = buf.getFloat(),
                    y = buf.getFloat(),
                    pressure = buf.getFloat(),
                    timestamp = buf.getLong()
                )
            }
            return TouchMoveBatch(pointers)
        }
    }
}

// ─── Notifications ───

data class NotificationData(
    val id: Int,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val progressIndeterminate: Boolean = false,
    val progress: Int = 0,
    val progressMax: Int = 0
) {
    fun encode(): ByteArray {
        val fields = listOf(packageName, appName, title, text)
        val fieldBytes = fields.map { it.toByteArray(Charsets.UTF_8) }
        val baseSize = 4 + fieldBytes.sumOf { 2 + it.size } + 8 + 1
        val totalSize = if (progressIndeterminate) baseSize else baseSize + 4 + 4
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(id)
        fieldBytes.forEach { bytes ->
            buf.putShort(bytes.size.toShort())
            buf.put(bytes)
        }
        buf.putLong(timestamp)
        buf.put(if (progressIndeterminate) 1.toByte() else 0.toByte())
        if (!progressIndeterminate) {
            buf.putInt(progress)
            buf.putInt(progressMax)
        }
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): NotificationData {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val id = buf.getInt()
            fun readString(): String {
                val len = buf.getShort().toInt()
                val bytes = ByteArray(len)
                buf.get(bytes)
                return String(bytes, Charsets.UTF_8)
            }
            val pkg = readString()
            val app = readString()
            val title = readString()
            val text = readString()
            val ts = buf.getLong()
            val indeterminate = if (buf.remaining() >= 1) buf.get() != 0.toByte() else false
            val prog = if (!indeterminate && buf.remaining() >= 4) buf.getInt() else 0
            val progMax = if (!indeterminate && buf.remaining() >= 4) buf.getInt() else 0
            return NotificationData(id, pkg, app, title, text, ts, indeterminate, prog, progMax)
        }
    }
}

// ─── App List ───

data class AppInfo(
    val packageName: String,
    val appName: String,
    val category: AppCategory,
    val iconPng: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppInfo) return false
        return packageName == other.packageName
    }

    override fun hashCode(): Int = packageName.hashCode()
}

enum class AppCategory(val id: Byte) {
    NAVIGATION(0),
    MUSIC(1),
    COMMUNICATION(2),
    OTHER(3);

    companion object {
        fun fromId(id: Byte): AppCategory = entries.find { it.id == id } ?: OTHER
    }
}

data class AppListMessage(val apps: List<AppInfo>) {
    fun encode(): ByteArray {
        val appBuffers = apps.map { app ->
            val pkgBytes = app.packageName.toByteArray(Charsets.UTF_8)
            val nameBytes = app.appName.toByteArray(Charsets.UTF_8)
            val iconBytes = app.iconPng
            ByteBuffer.allocate(2 + pkgBytes.size + 2 + nameBytes.size + 1 + 4 + iconBytes.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort(pkgBytes.size.toShort())
                .apply { put(pkgBytes) }
                .putShort(nameBytes.size.toShort())
                .apply { put(nameBytes) }
                .put(app.category.id)
                .putInt(iconBytes.size)
                .apply { put(iconBytes) }
                .array()
        }
        val totalSize = 2 + appBuffers.sumOf { it.size }
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(apps.size.toShort())
        appBuffers.forEach { buf.put(it) }
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): AppListMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val count = buf.getShort().toInt()
            val apps = (0 until count).map {
                fun readStr(): String {
                    val len = buf.getShort().toInt()
                    val bytes = ByteArray(len)
                    buf.get(bytes)
                    return String(bytes, Charsets.UTF_8)
                }
                val pkg = readStr()
                val name = readStr()
                val category = AppCategory.fromId(buf.get())
                val iconSize = if (buf.remaining() >= 4) buf.getInt() else 0
                val iconPng = if (iconSize > 0 && buf.remaining() >= iconSize) {
                    ByteArray(iconSize).also { buf.get(it) }
                } else ByteArray(0)
                AppInfo(
                    packageName = pkg,
                    appName = name,
                    category = category,
                    iconPng = iconPng
                )
            }
            return AppListMessage(apps)
        }
    }
}

// ─── Media ───

data class MediaMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
) {
    fun encode(): ByteArray {
        val fields = listOf(title, artist, album)
        val fieldBytes = fields.map { it.toByteArray(Charsets.UTF_8) }
        val totalSize = fieldBytes.sumOf { 2 + it.size } + 8
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        fieldBytes.forEach { bytes ->
            buf.putShort(bytes.size.toShort())
            buf.put(bytes)
        }
        buf.putLong(durationMs)
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): MediaMetadata {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            fun readStr(): String {
                val len = buf.getShort().toInt()
                val bytes = ByteArray(len)
                buf.get(bytes)
                return String(bytes, Charsets.UTF_8)
            }
            return MediaMetadata(
                title = readStr(),
                artist = readStr(),
                album = readStr(),
                durationMs = buf.getLong()
            )
        }
    }
}

data class PlaybackState(
    val state: Byte, // 0=stopped, 1=playing, 2=paused
    val positionMs: Long
) {
    fun encode(): ByteArray {
        return ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .put(state)
            .putLong(positionMs)
            .array()
    }

    companion object {
        const val STOPPED: Byte = 0
        const val PLAYING: Byte = 1
        const val PAUSED: Byte = 2

        fun decode(data: ByteArray): PlaybackState {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            return PlaybackState(buf.get(), buf.getLong())
        }
    }
}

// ─── Media Actions (car → phone) ───

enum class MediaAction(val id: Byte) {
    PLAY(0), PAUSE(1), NEXT(2), PREVIOUS(3), SEEK(4);

    companion object {
        fun fromId(id: Byte): MediaAction = entries.find { it.id == id } ?: PLAY
    }
}

// ─── Launch App (car → phone) ───

data class LaunchAppMessage(val packageName: String) {
    fun encode(): ByteArray = packageName.toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(data: ByteArray) = LaunchAppMessage(String(data, Charsets.UTF_8))
    }
}

// ─── Constants ───

const val PROTOCOL_VERSION = 1

const val DISPLAY_MODE_MIRROR: Byte = 0
const val DISPLAY_MODE_VIRTUAL: Byte = 1

const val FEATURE_VIDEO = 0x01
const val FEATURE_AUDIO = 0x02
const val FEATURE_NOTIFICATIONS = 0x04
const val FEATURE_MEDIA_CONTROL = 0x08
const val FEATURE_NAVIGATION = 0x10

// Connection methods (handshake response)
const val CONNECTION_METHOD_USB_ADB: Byte = 0
const val CONNECTION_METHOD_WIFI_ADB: Byte = 1
const val CONNECTION_METHOD_SHIZUKU: Byte = 2

