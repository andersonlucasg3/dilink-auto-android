package com.dilinkauto.server.decoder

/**
 * 冷启动补喂缓存 IDR 的代次判据（issue #216）。
 *
 * 现场：重连后新 codec 冷启动，把停播期缓存的 184B 全黑 IDR 当作冷启动参考帧喂了进去。
 * `awaitingIdr` 只拦「还没有参考帧」，陈旧参考帧恰好把这道门骗过去 —— 新流的第一批
 * P 帧（117637B / 11510B）解出来是 `size=12` 的空帧，直到新 IDR 到达才恢复（约 9s 花屏）。
 *
 * 缓存帧必须带上入缓存时的 codec 代次：只有**同一个 codec 实例**才允许复用
 * （`switchSurface` 不换 codec 的语义）；新 codec 领了新代号后，旧帧对应的编码器
 * 会话已经不存在，只能喂 CONFIG 并走 `awaitingIdr` 等新 IDR。
 *
 * 纯函数，便于在没有 MediaCodec 的单测里直接判决。
 */
internal fun shouldReuseCachedKeyFrame(cachedGeneration: Int, currentGeneration: Int): Boolean =
    cachedGeneration > 0 && cachedGeneration == currentGeneration
