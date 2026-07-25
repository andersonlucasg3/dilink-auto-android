package com.dilinkauto.vdserver

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Persistent root input injector for the AA virtual display.
 *
 * Why this exists: on HyperOS, uid 2000 (shell) is denied INJECT_EVENTS for
 * virtual displays and `su` is hidden from the shell daemon — but root passes
 * the check. The app (which holds the KernelSU grant) spawns this process via
 * `su -c ... app_process / com.dilinkauto.vdserver.DaemonEntry input-injector`
 * and sends gesture commands over a localhost socket (127.0.0.1:19648), so
 * multi-event gestures (drag, pinch) cost no per-event process spawn.
 *
 * Line protocol (UTF-8, \n-terminated), one client at a time:
 *   display <displayId>             — set target display for subsequent events
 *   tap <x> <y>                     — single-pointer DOWN+UP
 *   key <keyCode>                   — keyevent via root `input` CLI
 *   down|move|up <pointerId> <x> <y> — single-pointer event
 *   mdown|mmove|mup <x1> <y1> <x2> <y2> — two-pointer (pinch) event step
 *
 * Unknown or malformed commands are logged and ignored — the loop never dies
 * on bad input. All log lines go to stderr with an [InputInjector] prefix
 * (the app redirects it to /data/local/tmp/input-injector.log).
 */
object InputInjectorMain {

    private const val PORT = 19648

    @JvmStatic
    fun run(): Int {
        // FakeContext builds an ActivityThread, whose Handler needs a Looper
        // on the *initializing* thread — force class-init here on the main
        // thread (same pattern as AaDaemonMain).
        FakeContext.get()
        val bridge = NativeBridge()

        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", PORT))
        log("listening on 127.0.0.1:$PORT")

        while (true) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                log("accept failed: ${e.message}")
                continue
            }
            log("client connected")
            try {
                serve(client, bridge)
            } catch (e: Exception) {
                log("client error: ${e.message}")
            } finally {
                try { client.close() } catch (_: Exception) {}
                log("client disconnected")
            }
        }
    }

    private fun serve(client: Socket, bridge: NativeBridge) {
        var displayId = -1
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: return
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                val parts = trimmed.split(" ")
                when (parts[0]) {
                    "display" -> {
                        val id = parts[1].toInt()
                        if (id != displayId) {
                            displayId = id
                            // Harmless if already set — keeps the VD from
                            // re-laying itself out on rotation requests.
                            bridge.execShell("wm set-ignore-orientation-request -d $id true")
                            log("target display=$id")
                        }
                    }
                    "tap" -> {
                        val x = parts[1]; val y = parts[2]
                        if (!bridge.injectMotionEvent(displayId, 0, "0,0,$x,$y,1.0")) {
                            log("tap DOWN failed on display $displayId")
                        }
                        if (!bridge.injectMotionEvent(displayId, 2, "0,0,$x,$y,1.0")) {
                            log("tap UP failed on display $displayId")
                        }
                    }
                    "key" -> bridge.execShell("input -d $displayId keyevent ${parts[1]}")
                    "down" -> single(bridge, displayId, 0, parts)
                    "move" -> single(bridge, displayId, 1, parts)
                    "up" -> single(bridge, displayId, 2, parts)
                    "mdown" -> multi(bridge, displayId, 0, parts)
                    "mmove" -> multi(bridge, displayId, 1, parts)
                    "mup" -> multi(bridge, displayId, 2, parts)
                    else -> log("unknown command: $trimmed")
                }
            } catch (e: Exception) {
                log("malformed command '$trimmed': ${e.message}")
            }
        }
    }

    /** down|move|up <pointerId> <x> <y> — action: 0=DOWN, 1=MOVE, 2=UP. */
    private fun single(bridge: NativeBridge, displayId: Int, action: Int, parts: List<String>) {
        val desc = "0,${parts[1]},${parts[2]},${parts[3]},1.0"
        if (!bridge.injectMotionEvent(displayId, action, desc)) {
            log("${parts[0]} failed on display $displayId")
        }
    }

    /** mdown|mmove|mup <x1> <y1> <x2> <y2> — phase: 0=DOWN, 1=MOVE, 2=UP. */
    private fun multi(bridge: NativeBridge, displayId: Int, phase: Int, parts: List<String>) {
        val ok = bridge.injectTwoPointerEvent(displayId, phase,
            parts[1].toFloat(), parts[2].toFloat(),
            parts[3].toFloat(), parts[4].toFloat())
        if (!ok) log("${parts[0]} failed on display $displayId")
    }

    private fun log(msg: String) = System.err.println("[InputInjector] $msg")
}
