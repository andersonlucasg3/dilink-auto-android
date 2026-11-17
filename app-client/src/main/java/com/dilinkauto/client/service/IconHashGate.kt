package com.dilinkauto.client.service

/**
 * Icon send gate (audit R3-SRP-18): the car only needs each icon once per
 * session — [iconFor] returns an empty array while the package's hash is
 * unchanged since the last send, and the car falls back to its persisted
 * `AppIconCache`.
 *
 * Extracted from `AppListBuilder` so the "what changed since last send" policy
 * is separate from list assembly.
 */
internal class IconHashGate {

    // Tracks the last icon hash sent per package — survives across reconnections
    // within the same service lifetime to avoid re-sending unchanged icons.
    //
    // Concurrent (audit A-M11): reset() runs on the Main thread when a session
    // is torn down while iconFor() runs on the IO thread that builds the app
    // list — a plain LinkedHashMap shared that way can lose entries or, on
    // structural modification during a concurrent read, corrupt itself.
    private val lastSent = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Returns the PNG to put on the wire for [pkg] with the current [hash]:
     * an empty array when the car already has this icon, otherwise the
     * output of [load] (and the hash is remembered as sent).
     */
    fun iconFor(pkg: String, hash: String, load: () -> ByteArray): ByteArray {
        if (hash.isNotEmpty() && hash == lastSent[pkg]) return ByteArray(0)
        lastSent[pkg] = hash
        return load()
    }

    /** Clear the cache so the next list build re-sends every icon. */
    fun reset() {
        lastSent.clear()
    }
}
