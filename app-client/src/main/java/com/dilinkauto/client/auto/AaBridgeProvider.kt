package com.dilinkauto.client.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.dilinkauto.client.FileLog
import com.dilinkauto.protocol.aidl.IAaBridge
import com.dilinkauto.protocol.aidl.IAaSurfaceCallback

/**
 * Hands the IAaBridge binder to the root daemon. ContentProvider.call works
 * from app_process callers without an AMS process record (unlike bindService),
 * which is why Shizuku uses the same pattern for its own discovery.
 */
class AaBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        // Only the root daemon (UID 0) or system may grab the bridge binder
        if (uid != 0 && uid != Process.SYSTEM_UID) {
            FileLog.w(TAG, "AaBridge call denied for uid=$uid")
            throw SecurityException("AaBridgeProvider: root only")
        }
        if (method != "getBinder") throw IllegalArgumentException("Unknown method: $method")
        FileLog.i(TAG, "AaBridge binder handed to uid=$uid")
        return Bundle().apply { putBinder("binder", bridgeStub) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0

    private companion object {
        private const val TAG = "AaBridgeProvider"

        private var daemonCallback: IAaSurfaceCallback? = null

        private val bridgeStub = object : IAaBridge.Stub() {
            override fun ping(msg: String): String {
                FileLog.i(TAG, "ping from uid=${Binder.getCallingUid()}: $msg")
                return "pong:$msg"
            }

            override fun registerSurfaceCallback(cb: IAaSurfaceCallback?) {
                FileLog.i(TAG, "Daemon surface callback registered (uid=${Binder.getCallingUid()})")
                daemonCallback = cb
            }
        }
    }
}
