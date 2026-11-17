package com.dilinkauto.desktop

import com.dilinkauto.desktop.log.DesktopLog
import com.dilinkauto.desktop.video.VideoDecodePipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 每秒一条会话统计（audit R3-SRP-12）——联调时用它判断瓶颈在接收侧还是解码侧。
 *
 * 从 [DesktopApp] 的会话装配里移出：这条循环只读 [VideoDecodePipeline] 的计数器，
 * 顺带把「当前是否硬解」回写给会话流（硬解可能在运行中回退到软解，Phase 5b）。
 */
internal class SessionStatsLogger(
    private val log: DesktopLog,
    private val pipeline: VideoDecodePipeline,
    private val onHardwareChanged: (Boolean) -> Unit,
) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            var last = 0L
            while (true) {
                delay(1_000)
                val decoded = pipeline.framesDecoded.get()
                log.info(
                    "stats",
                    "decoded fps=${decoded - last} total=$decoded " +
                        "rebuilds=${pipeline.decoderRebuilds.get()} recv=${pipeline.framesFed.get()} " +
                        "cfg=${pipeline.configsFed.get()} hw=${pipeline.hardwareInUse}",
                )
                last = decoded
                onHardwareChanged(pipeline.hardwareInUse)
            }
        }
    }
}
