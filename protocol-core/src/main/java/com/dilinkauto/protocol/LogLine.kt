package com.dilinkauto.protocol

/**
 * Line assembly for the three log sinks.
 *
 * The platform sinks themselves are rightly separate — Android `Log`, a file
 * writer on the phone, a socket forwarder on the car, a console on the desktop —
 * but each was also formatting its own line, and the formats had drifted into
 * three variants (docs/audit-srp-dry.md DRY-8):
 *
 * ```
 * FileLog      [$ts][$level][$tag] $msg
 * CarLogWriter [$ts][$level] $msg
 * DesktopLog   $ts [$level] [$tag] $msg
 * ```
 *
 * Only the *line shape* is shareable. Timestamp precision genuinely differs and
 * must not be unified: the desktop format carries a date because its log spans
 * days, while the phone and car log files are rotated daily and carry only
 * wall-clock time. So this object takes the already-formatted timestamp and owns
 * nothing about how it was produced.
 */
@Suppress("StringLiteralDuplication")
object LogLine {

    /** `[ts][level][tag] message` — phone `FileLog`. */
    fun bracketedTagFirst(ts: String, level: String, tag: String, message: String): String =
        "[" + ts + "][" + level + "][" + tag + "] " + message

    /** `[ts][level] message` — car `CarLogWriter`, which carries no tag. */
    fun bracketed(ts: String, level: String, message: String): String =
        "[" + ts + "][" + level + "] " + message

    /** `ts [level] [tag] message` — desktop `DesktopLog`. */
    fun bracketedTagLast(ts: String, level: String, tag: String, message: String): String =
        ts + " [" + level + "] [" + tag + "] " + message
}