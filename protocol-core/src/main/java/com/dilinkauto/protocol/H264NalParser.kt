package com.dilinkauto.protocol

/**
 * H.264 NAL 单元解析（解码路径共用）。
 *
 * 原属 `app-server` 的 `decoder/H264NalParser`，迁移到 :protocol-core 供车机端与桌面端共用（DRY）：
 * 两端都需要"从 Annex B 码流里判断这一帧是不是 IDR"，判定逻辑必须只有一份。
 * 纯字节数组进、布尔值出，无任何平台依赖。
 */
object H264NalParser {

    /**
     * [data] 中是否包含 IDR 画面（NAL unit type 5）。
     *
     * 只扫描前 ~1KB：一个访问单元里决定帧类型的 NAL 头都在起始处，逐字节全量扫描是浪费
     * （车机是弱 CPU）。兼容 3 字节（`00 00 01`）与 4 字节（`00 00 00 01`）起始码。
     */
    fun isKeyFrame(data: ByteArray): Boolean {
        val limit = minOf(data.size - 3, 1024)
        var i = 0
        while (i < limit) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val nalStart = if (data[i + 2] == 1.toByte()) i + 3
                    else if (data[i + 2] == 0.toByte() && i + 3 < data.size && data[i + 3] == 1.toByte()) i + 4
                    else { i++; continue }
                if (nalStart < data.size) {
                    if ((data[nalStart].toInt() and 0x1F) == 5) return true
                }
            }
            i++
        }
        return false
    }
}