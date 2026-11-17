package com.dilinkauto.vdserver

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * 多端口绑定与 SocketChannel 接受器（Refs #87）。
 *
 * 负责 vd-server 的多端口（9638 视频、9639 输入、9640 音频）非阻塞绑定与客户端连接接受。
 */
internal object PortAcceptor {

    data class BoundServers(
        val videoServer: ServerSocketChannel,
        val inputServer: ServerSocketChannel,
        val audioServer: ServerSocketChannel?
    ) : AutoCloseable {
        override fun close() {
            runCatching { videoServer.close() }
            runCatching { inputServer.close() }
            runCatching { audioServer?.close() }
        }
    }

    /**
     * 打开并绑定一个非阻塞的 [ServerSocketChannel]。
     */
    fun openAndBind(port: Int, host: String = "0.0.0.0"): ServerSocketChannel {
        val server = ServerSocketChannel.open()
        server.configureBlocking(false)
        server.socket().reuseAddress = true
        server.socket().bind(InetSocketAddress(host, port))
        return server
    }

    /**
     * 绑定视频与输入端口（必需），以及音频端口（可选）。
     *
     * 如果视频或输入端口绑定失败，返回 null，并自动清理已打开的 socket；
     * 如果音频端口绑定失败，仅记录错误并不中断会话（音频降级）。
     */
    fun bindServers(
        videoPort: Int,
        inputPort: Int,
        audioPort: Int,
        onLog: (String) -> Unit = {},
        onErr: (String) -> Unit = {}
    ): BoundServers? {
        val videoServer: ServerSocketChannel
        val inputServer: ServerSocketChannel
        try {
            videoServer = openAndBind(videoPort)
            onLog("Video on :$videoPort")
            try {
                inputServer = openAndBind(inputPort)
                onLog("Input on :$inputPort")
            } catch (e: Exception) {
                runCatching { videoServer.close() }
                onErr("Input bind: ${e.message}")
                return null
            }
        } catch (e: Exception) {
            onErr("Video bind: ${e.message}")
            return null
        }

        val audioServer: ServerSocketChannel? = run {
            var ch: ServerSocketChannel? = null
            try {
                ch = openAndBind(audioPort)
                onLog("Audio on :$audioPort (listen only)")
                ch
            } catch (e: Exception) {
                onErr("Audio bind on :$audioPort failed: ${e.message} — audio disabled for this session")
                runCatching { ch?.close() }
                null
            }
        }

        return BoundServers(videoServer, inputServer, audioServer)
    }

    /**
     * 在限定时间内等待并接受一个客户端连接。
     *
     * @param server 监听 socket
     * @param timeoutMs 超时时间（毫秒）
     * @param socketBufBytes 接收与发送缓冲区大小（字节）
     * @param isAllowedPeer 校验对端是否允许连接（白名单）
     * @param isRunning 当前服务是否仍在运行
     * @param onRefusedPeer 当对端被拒绝时的回调
     * @return 成功建立连接且配置完成的 [SocketChannel]，若超时或退出则返回 null
     */
    fun acceptCarChannel(
        server: ServerSocketChannel,
        timeoutMs: Int,
        socketBufBytes: Int,
        isAllowedPeer: (SocketChannel) -> Boolean,
        isRunning: () -> Boolean = { true },
        onRefusedPeer: (SocketChannel) -> Unit = {}
    ): SocketChannel? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (isRunning() && System.currentTimeMillis() < deadline) {
            val a = server.accept()
            if (a != null) {
                if (!isAllowedPeer(a)) {
                    onRefusedPeer(a)
                    try { a.close() } catch (_: Exception) {}
                    continue
                }
                a.configureBlocking(false)
                val s = a.socket()
                s.sendBufferSize = socketBufBytes
                s.receiveBufferSize = socketBufBytes
                s.tcpNoDelay = true
                return a
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    private const val POLL_INTERVAL_MS = 50L
}
