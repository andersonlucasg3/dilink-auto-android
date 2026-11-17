package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the three log-line shapes as they are actually emitted
 * (docs/audit-srp-dry.md DRY-8).
 *
 * The formats are deliberately NOT unified into one: the desktop log carries a
 * date because it spans days, the phone and car logs are rotated daily and carry
 * only wall-clock time, and the car has no per-line tag. Collapsing them would
 * silently change what a log file looks like on disk.
 */
class LogLineTest {

    private val ts = "12:34:56.789"
    private val level = "I"
    private val tag = "VideoDecoder"
    private val msg = "Fed 30 frames"

    @Test
    fun bracketedTagFirst_isThePhoneFileFormat() {
        assertEquals("[$ts][$level][$tag] $msg", LogLine.bracketedTagFirst(ts, level, tag, msg))
        assertEquals("[12:34:56.789][I][VideoDecoder] Fed 30 frames",
            LogLine.bracketedTagFirst(ts, level, tag, msg))
    }

    @Test
    fun bracketed_isTheCarFormatAndCarriesNoTag() {
        assertEquals("[$ts][$level] $msg", LogLine.bracketed(ts, level, msg))
        assertEquals("[12:34:56.789][I] Fed 30 frames", LogLine.bracketed(ts, level, msg))
    }

    @Test
    fun bracketedTagLast_isTheDesktopFormat() {
        assertEquals("$ts [$level] [$tag] $msg", LogLine.bracketedTagLast(ts, level, tag, msg))
        assertEquals("12:34:56.789 [I] [VideoDecoder] Fed 30 frames",
            LogLine.bracketedTagLast(ts, level, tag, msg))
    }

    @Test
    fun theThreeFormatsStayDistinct() {
        // If these ever converge, a log-parsing change would be needed; until
        // then the divergence is a compatibility surface, not an oversight.
        val phone = LogLine.bracketedTagFirst(ts, level, tag, msg)
        val car = LogLine.bracketed(ts, level, msg)
        val desktop = LogLine.bracketedTagLast(ts, level, tag, msg)
        assertEquals(3, setOf(phone, car, desktop).size)
    }

    @Test
    fun splittingOnBracketsYieldsTheLevelAndTagInOrder() {
        // FileLog parses the queued line by splitting on "?" first and then
        // positional extraction, so what matters here is that the brackets
        // delimit level and tag in a fixed order.
        val line = LogLine.bracketedTagFirst(ts, "E", "T", "msg")
        val parts = line.split("]", limit = 3)
        assertEquals(3, parts.size)
        assertEquals("[$ts", parts[0])
        assertEquals("[E", parts[1])
        assertEquals("[T] msg", parts[2])
    }

    @Test
    fun aBracketInsideTheMessageDoesNotShiftTheLevelOrTagFields() {
        // A "]" in the message body must not be able to corrupt the fields that
        // precede it: level and tag are already delimited by the time the
        // message starts.
        val line = LogLine.bracketedTagFirst(ts, "E", "T", "msg with ] bracket")
        val parts = line.split("]", limit = 3)
        assertEquals("[$ts", parts[0])
        assertEquals("[E", parts[1])
        assertEquals("[T] msg with ] bracket", parts[2])
    }

    @Test
    fun emptyMessageStillProducesATrailingSeparator() {
        assertEquals("[$ts][$level][$tag] ", LogLine.bracketedTagFirst(ts, level, tag, ""))
        assertEquals("[$ts][$level] ", LogLine.bracketed(ts, level, ""))
        assertEquals("$ts [$level] [$tag] ", LogLine.bracketedTagLast(ts, level, tag, ""))
    }
}