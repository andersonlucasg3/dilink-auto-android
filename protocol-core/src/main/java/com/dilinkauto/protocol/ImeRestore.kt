package com.dilinkauto.protocol

/**
 * Shared IME-restore logic for the phone and the VD server.
 *
 * The phone ([com.dilinkauto.client.service.PhoneDisplayRestorer]) and the
 * car's VD server ([com.dilinkauto.vdserver.DisplayPowerController]) both
 * restore the user's original IME after the VD session tears down. They also
 * both gate IME save/restore on the same "linkpc" exclusion predicate (the
 * VD's own IME must never be persisted as the default). And the phone's
 * [com.dilinkauto.client.service.ConnectionService.cacheDefaultIme] uses the
 * same predicate when deciding whether to cache the current IME at handshake.
 *
 * Collecting the commands and the predicate here means a change to the IME
 * restore sequence or the exclusion string lands in one place — previously
 * it was duplicated across 4 files with subtle divergence (PhoneDisplayRestorer
 * ran the three commands as one Shizuku line with `2>/dev/null` on the last;
 * DisplayPowerController ran them as three separate `execShell` calls).
 */
object ImeRestore {

    /**
     * Predicate: true when [ime] is a non-null, non-blank, non-`"null"` IME id
     * that is NOT the VD's linkpc IME. The linkpc exclusion is critical —
     * persisting the linkpc IME as the default would leave the phone without
     * a usable keyboard after the VD session ends.
     */
    fun shouldRestoreIme(ime: String?): Boolean =
        !ime.isNullOrBlank() && ime != "null" && !ime.contains("linkpc", ignoreCase = true)

    /**
     * The three shell commands that restore [ime] as the system default IME.
     * Returned as a list for callers that execute each command separately
     * (the VD server's persistent shell).
     *
     * [ime] comes from `Settings.Secure.DEFAULT_INPUT_METHOD` (system-supplied,
     * not peer-controlled), but it is interpolated into `sh` lines — a malicious
     * installed IME whose id carried shell metacharacters would inject. Validate
     * the id shape here and shell-quote it.
     */
    fun imeRestoreCommands(ime: String): List<String> {
        val safe = requireSafeImeId(ime)
        return listOf(
            "ime enable ${VdDeploy.shellQuote(safe)}",
            "ime set ${VdDeploy.shellQuote(safe)}",
            "settings put secure default_input_method ${VdDeploy.shellQuote(safe)}"
        )
    }

    /**
     * The same three commands joined as a single shell line, with stderr
     * suppressed on the last command. For callers that run one Shizuku
     * `execAndWait` (the phone-side restorer).
     */
    fun imeRestoreCommandLine(ime: String): String {
        val safe = VdDeploy.shellQuote(requireSafeImeId(ime))
        return "ime enable $safe; ime set $safe; settings put secure default_input_method $safe 2>/dev/null"
    }

    /** IME ids are `pkg/.ImeService` — anything outside that shape is rejected. */
    private fun requireSafeImeId(ime: String): String {
        require(COMPONENT_REGEX.matches(ime)) { "Unsafe IME id: ${ime.length} chars" }
        return ime
    }
}
