package com.dilinkauto.server.service

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 车机端音频生命周期（`AudioPlayer.start/stop`）的线程口径（S3-PB 修复，issue #73）。
 *
 * ## 缺陷
 *
 * `AudioPlayer.stop()` 拿 `lifecycleLock` 后 `join` 播放线程，上限
 * `STOP_JOIN_TIMEOUT_MS = 2000`。它的调用点却在 Main 上跑：服务类作用域是
 * `Dispatchers.Main`，`disconnectAllConnections()` 的两条路径（`startConnection` /
 * `connectToPhone`）都在 Main 上下文，音频连接的 `onDisconnect` 也在 Main（其
 * EOF 宽限分支是 `scope.launch`，而该连接拿到的 scope 正是服务主 scope）。
 * 同步 `stop()` 因此最多把主线程钉住 2s —— 与 `stopDecoderAsync()` 当初规避的形态同类。
 *
 * ## 口径
 *
 * 音频的 start / stop 一律交给 [newAudioExecutor] 建的**音频专属单线程**执行，
 * 调用线程（Main / 网络读线程）立即返回。该单线程的 FIFO 队列钉住两件事：
 *
 * 1. **停播先于断开 9640**：[submitAudioTeardown] 把 `stop()` 与 `disconnect()` 放进
 *    **同一个任务**，次序由任务内的语句顺序保证 —— 播放线程不会在 9640 socket
 *    关闭后继续解码。若只把 `stop()` 异步化而把 `disconnect()` 留在调用线程，
 *    socket 会先关，正是本次修复必须避开的反向竞态。
 * 2. **上一会话的 stop 先于新会话的 start**：start 也走这条队列，于是新会话的
 *    `start()` 必然排在尚未跑完的 `stop()` 之后。若 start 绕开队列，`lifecycleLock`
 *    只能保证两次调用不交叠、不能保证顺序 —— 迟到的 `stop()` 会把刚起的播放线程停掉。
 *    这条理由与 `stopDecoderAsync` 给 video 的理由同构（video 的 start 也走
 *    `decoderExecutor`）。
 *
 * ## 为何不把 `stop()` 本身改成不 join
 *
 * 播放线程在 `running.get()` 为真时持续消费，而 `start()` 会把该标志重新置真。不 join
 * 就返回，则未退出的旧线程会在新一轮 `start()` 之后继续跑 —— 两个播放线程同时写
 * `AudioTrack`。串行队列的代价远小于改线程归属判定，也不动 `AudioPlayer` 既有契约
 * （线程是 codec/track 的 release 唯一属主）。
 *
 * ## 销毁路径不等待
 *
 * 交互路径（会话 teardown、断链回调、连接失败）永不等待 —— 那正是 P1；销毁路径
 * （`ACTION_STOP` / `onDestroy`，均 Main）同样不等，只 `shutdown()` 执行器：它会让
 * 已排队的任务照常跑完、仅拒绝新任务。这与 `stopDecoderAsync()` +
 * `decoderExecutor.shutdown()` 给 video 的口径完全一致，不为音频另立一套 teardown 语义。
 */

/** 音频生命周期的专属单线程。Daemon，避免纯生命周期工作挂住进程（同 `decoderExecutor`）。 */
internal fun newAudioExecutor(): ExecutorService =
    Executors.newSingleThreadExecutor { r ->
        Thread(r, "CarAudioLifecycle").apply { isDaemon = true }
    }

/**
 * 把一次音频生命周期操作交给 [executor]，调用线程立即返回。
 *
 * @return `true` = 已入队异步执行；`false` = 执行器已 shutdown（destroy 路径），
 *   已内联跑完。内联是刻意的：宁可让 destroy 阻塞，也不漏掉停播 / 断连
 *   （同 `stopDecoderAsync` 的 fallback 口径）。
 */
internal fun submitAudioLifecycle(executor: ExecutorService, action: () -> Unit): Boolean =
    try {
        executor.execute(action)
        true
    } catch (_: RejectedExecutionException) {
        action()
        false
    }

/** 「先停播、再断开 9640」的一个不可分割任务（见文件头第 1 条）。 */
internal fun submitAudioTeardown(
    executor: ExecutorService,
    stop: () -> Unit,
    disconnect: () -> Unit,
): Boolean = submitAudioLifecycle(executor) {
    stop()
    disconnect()
}
