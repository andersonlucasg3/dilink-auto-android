package com.dilinkauto.vdserver

/**
 * 把异常变成可读的一行。
 *
 * `CodecException` 的 message 可能是**空串**（MI 9 的 `OMX.qcom.video.encoder.avc` 就是），
 * 只打 message 会得到一行 `Fatal: encoder: `，现场无法定位 —— 所以空 message 回退到类名。
 */
internal fun Throwable.describe(): String =
    message?.trim().takeUnless { it.isNullOrEmpty() } ?: javaClass.simpleName
