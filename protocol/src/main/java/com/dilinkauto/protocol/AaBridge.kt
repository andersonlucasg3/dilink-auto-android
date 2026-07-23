package com.dilinkauto.protocol

/**
 * Wire constants for the AA daemon bridge. The daemon's binder travels inside
 * an explicit broadcast Intent (extras) — custom ServiceManager services are
 * invisible to untrusted_app (AOSP sepolicy denies { find }).
 */
object AaBridge {
    const val ACTION_ANNOUNCE = "com.dilinkauto.client.AA_DAEMON"
    const val EXTRA_BINDER = "binder"
    const val EXTRA_TOKEN = "token"
    const val TOKEN_FILE = "/data/local/tmp/dilink.aa.token"
    const val APP_PACKAGE = "com.dilinkauto.client"
    const val RECEIVER_FQCN = "com.dilinkauto.client.auto.AaDaemonReceiver"
    const val LAUNCHER_FQCN = "com.dilinkauto.client.launcher.DiLinkLauncher"
}
