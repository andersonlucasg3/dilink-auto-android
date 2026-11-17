package com.dilinkauto.protocol

/**
 * 音频抖动缓冲的状态机与判据（issue #73 分节 PB / Refs #13），纯 JVM、无 Android 依赖。
 *
 * 为什么落在 `:protocol-core`：`AudioPlayer`（app-server）里只剩 `MediaCodec` /
 * `AudioTrack` 的 I/O，凡是不碰 I/O 的判定（目标水位换算、起播裁水位、队列上限丢最旧、
 * 补静音判定）都挤到这里，由 `AudioJitterBufferTest` 用字面量钉住 —— 车机侧无
 * `AudioTrack`/`AudioRecord` 可单测的 I/O，能测的只有这些判定。
 *
 * ## 参数来源（#13 §2.5 的 15 组实测拐点）
 *
 * AAC-LC 48 kHz 每帧 1024 samples = **21.333 ms / 46.875 fps**，这是硬约束：
 * 若按 20 ms 节拍消费，每秒会多发 7~8 帧、播放端队列从 5 涨到 13~15（+约 300 ms
 * 延迟，实测 R13）。抖动缓冲**默认 150 ms**，可配 60~200 ms（60→100→150 把突发下
 * 星饿 11→6→3 帧、恢复 175→110→45 ms；150→200 只多消 1 帧却把队列深度抬到 11~12）。
 *
 * 与 `vd-server` 的 `AudioSpec`（`internal`，S2 产物）是同一份实测数字的第二份抄写；
 * `vd-server` 不在本节改动范围（PA 的范围），故此处独立定义，不跨模块共享。
 */

/** AAC-LC 一帧的 samples 数（48 kHz）。 */
const val AUDIO_SAMPLES_PER_FRAME = 1024

/** 采集/播放采样率（Hz）。 */
const val AUDIO_SAMPLE_RATE = 48_000

/** 一帧时长（微秒）：1024 * 1e6 / 48000 = 21333.33… 截断为 21333。 */
const val AUDIO_FRAME_DURATION_US = 21_333L

/**
 * 一帧时长（纳秒）：1024 * 1e9 / 48000 = 21 333 333.33… 截断为 21 333 333。
 *
 * 播放线程按这个节拍消费（**不是** 20 ms / 20 000 000 ns）。
 */
const val AUDIO_FRAME_DURATION_NANOS = 21_333_333L

/** 帧率：48000 / 1024 = 46.875。 */
const val AUDIO_FRAMES_PER_SECOND = 46.875

/** 抖动缓冲下限（ms）：延迟优先。 */
const val AUDIO_JITTER_BUFFER_MIN_MS = 60

/** 抖动缓冲上限（ms）：再大只有延迟没有收益（#13 §2.5）。 */
const val AUDIO_JITTER_BUFFER_MAX_MS = 200

/** 抖动缓冲默认值（ms）：实测拐点（#13 §2.5）。 */
const val AUDIO_JITTER_BUFFER_DEFAULT_MS = 150

/** 队列上限相对目标水位的倍数（`2 × target`）。 */
const val AUDIO_JITTER_QUEUE_CAPACITY_FACTOR = 2

/** 把任意抖动缓冲毫秒值钳进 [AUDIO_JITTER_BUFFER_MIN_MS]..[AUDIO_JITTER_BUFFER_MAX_MS]。 */
fun coerceAudioJitterBufferMs(bufferMs: Int): Int =
    bufferMs.coerceIn(AUDIO_JITTER_BUFFER_MIN_MS, AUDIO_JITTER_BUFFER_MAX_MS)

/**
 * 目标水位（帧）：`ceil(bufferMs / 21.333)`，至少 1 帧。
 *
 * 用微秒整数运算避免浮点：`ceil(bufferMs * 1000 / 21333)`。
 * 实测对照（探针 `AudioProbe.java:729` 同式）：60→3、100→5、150→8、200→10。
 */
fun audioJitterTargetFrames(bufferMs: Int): Int {
    require(bufferMs > 0) { "bufferMs must be > 0: $bufferMs" }
    val us = bufferMs.toLong() * 1000L
    return ((us + AUDIO_FRAME_DURATION_US - 1) / AUDIO_FRAME_DURATION_US)
        .coerceAtLeast(1L)
        .toInt()
}

/** 队列上限（帧）＝ `AUDIO_JITTER_QUEUE_CAPACITY_FACTOR × target`。 */
fun audioJitterQueueCapacity(targetFrames: Int): Int {
    require(targetFrames > 0) { "targetFrames must be > 0: $targetFrames" }
    return targetFrames * AUDIO_JITTER_QUEUE_CAPACITY_FACTOR
}

/**
 * 起播裁水位时要丢多少**最旧**帧：`max(0, queued - targetFrames)`。
 *
 * 起播前把积压裁到目标水位，避免一上来就背着几百毫秒延迟（探针 `rx2` 的
 * `while (q.size() > target) q.pollFirst()`）。
 */
fun audioJitterStartupDropCount(queued: Int, targetFrames: Int): Int =
    (queued - targetFrames).coerceAtLeast(0)

/**
 * 队列满、要入队一帧时需要丢多少**最旧**帧以腾出位置：`max(0, queued - capacity + 1)`。
 *
 * 队列上限 `2×target`，超出丢最旧（防延迟无限累积）。`queued == capacity` 时丢 1 帧。
 */
fun audioJitterOverflowDropCount(queued: Int, capacity: Int): Int =
    (queued - capacity + 1).coerceAtLeast(0)

/** 本拍是否要补静音：队列为空（无真实帧可播）。 */
fun audioJitterShouldInsertSilence(queued: Int): Boolean = queued <= 0

/**
 * 抖动缓冲（线程安全的定长队列）。
 *
 * 网络线程 [enqueue]（必须常数级、非阻塞），播放线程 [poll]；队列上限 `2×target`，
 * 满时丢**最旧**。真实帧与「补静音」是两条路：这里只管真实帧，[poll] 返回 null 由
 * 调用方补静音 PCM 并计 `starved`（判定见 [audioJitterShouldInsertSilence]）。
 */
class AudioJitterBuffer<T>(bufferMs: Int = AUDIO_JITTER_BUFFER_DEFAULT_MS) {

    /** 钳位后的缓冲毫秒值（构造参数越界时收敛，不抛）。 */
    val bufferMs: Int = coerceAudioJitterBufferMs(bufferMs)

    /** 目标水位（帧）。 */
    val targetFrames: Int = audioJitterTargetFrames(this.bufferMs)

    /** 队列上限（帧）＝ `2 × targetFrames`。 */
    val capacity: Int = audioJitterQueueCapacity(targetFrames)

    private val lock = Any()
    private val queue = ArrayDeque<T>()

    /** 因队列满或起播裁剪而丢掉的帧数（诊断用）。 */
    var droppedOldest: Long = 0L
        private set

    /** 当前队列深度（帧）。 */
    val size: Int get() = synchronized(lock) { queue.size }

    /** 队列是否已达目标水位（起播门）。 */
    fun hasReachedTarget(): Boolean = size >= targetFrames

    /**
     * 入队一帧（网络线程调用，常数级）。队列满时丢最旧的若干帧腾位置。
     *
     * @return 本次丢弃的帧数（0 表示无丢弃）。
     */
    fun enqueue(frame: T): Int = synchronized(lock) {
        val drop = audioJitterOverflowDropCount(queue.size, capacity)
        repeat(drop) { queue.removeFirst() }
        droppedOldest += drop
        queue.addLast(frame)
        drop
    }

    /** 取队首真实帧；队列空返回 null（调用方补静音并计 starved）。 */
    fun poll(): T? = synchronized(lock) {
        if (queue.isEmpty()) null else queue.removeFirst()
    }

    /**
     * 起播裁水位：把积压裁到 [targetFrames]，丢最旧。
     *
     * @return 本次丢弃的帧数。
     */
    fun primeTrim(): Int = synchronized(lock) {
        val drop = audioJitterStartupDropCount(queue.size, targetFrames)
        repeat(drop) { queue.removeFirst() }
        droppedOldest += drop
        drop
    }

    /** 清空队列（会话结束）。不清 [droppedOldest]，它是会话内累计诊断量。 */
    fun clear() = synchronized(lock) { queue.clear() }
}
