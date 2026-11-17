package com.dilinkauto.vdserver

/**
 * `screen_off_timeout` 的会话哨兵值与快照翻译（audit S-M10）。
 *
 * [VirtualDisplayCreator.configureEnvironment] 在会话期间写入 [SESSION_TIMEOUT_SENTINEL]
 * 永不熄屏。正常情况下快照早于该写入取得，用户真实值不会丢；但如果上个会话被
 * SIGKILL / `Runtime.halt(1)` 异常终止，`settings get system screen_off_timeout`
 * 会读回我们自己写的哨兵。此时"快照==哨兵"意味着**没有有效快照**，恢复时必须回落
 * [DEFAULT_TIMEOUT_MS]，绝不能把哨兵再写回去 —— 否则用户的手机永不息屏，且之后每个
 * 会话都继承这个值。
 *
 * 常量集中在这里一份，[DisplayPowerController]（读快照/恢复）与
 * [VirtualDisplayCreator]（写哨兵）共同引用，避免两边字面量漂移。
 */
internal object SessionScreenTimeout {
    /** 会话期间写入 `system.screen_off_timeout` 的"永不息屏"哨兵。 */
    const val SESSION_TIMEOUT_SENTINEL = 2147483647L

    /** `settings get system screen_off_timeout` 的通用默认值（60s）。 */
    const val DEFAULT_TIMEOUT_MS = 60_000L

    /** 原值存档文件名（位于 [com.dilinkauto.protocol.VdDeploy.DIR_PATH]，见 [TimeoutSnapshotFile]）。 */
    const val SNAPSHOT_FILE_NAME = "screen-timeout.snapshot"

    /**
     * 计算本次会话应记录（存档 / 恢复）的原始值（2026-10-17 S19 真机实证的 kill 路径修正）。
     *
     * 内存快照在被 SIGKILL 的上个会话里会丢失，此时 `settings get` 读回哨兵。修正顺序：
     *  - [current] 非哨兵 → 原样（正常路径，与旧行为逐字一致，含 null → 默认值）；
     *  - [current] 是哨兵（上个会话被 kill）→ 用文件存档 [fromFile] 修正；
     *  - 存档不可用（缺失 / 读失败标记 / 非正数 / 本身是哨兵）→ 回落 [DEFAULT_TIMEOUT_MS]。
     *
     * 真机实证（S19 20 轮循环，vd-server.log 行 148→161→205）：第 N 轮被重建强杀 →
     * 第 N+1 轮读到哨兵 → 旧逻辑 fallback 60000 → **用户的 300000 被永久改成 60000**。
     * 文件存档（会话开始即写入）让 kill 路径下个会话仍能恢复真实原值。
     */
    fun effectiveSnapshot(current: String?, fromFile: String?): String {
        val c = current?.trim()
        if (!isSentinel(c)) return c ?: DEFAULT_TIMEOUT_MS.toString()
        val f = fromFile?.trim()
        if (isSentinel(f)) return DEFAULT_TIMEOUT_MS.toString()
        if (f.isNullOrEmpty() || f.equals("null", true) || f.equals("undefined", true)) {
            return DEFAULT_TIMEOUT_MS.toString()
        }
        val n = f.toLongOrNull()
        if (n == null || n <= 0L) return DEFAULT_TIMEOUT_MS.toString()
        return f
    }

    /** [snapshot] 是否就是本模块写的哨兵（读失败/非数值都算"不是"）。 */
    fun isSentinel(snapshot: String?): Boolean =
        snapshot?.trim()?.toLongOrNull() == SESSION_TIMEOUT_SENTINEL

    /**
     * 把快照翻译成恢复时要写回的值：
     *  - 快照等于哨兵（上个会话被异常杀死留下的）→ 回落 [DEFAULT_TIMEOUT_MS]；
     *  - 快照不可用（读失败标记 / 非正数）→ 返回 null，调用方保持现状不写；
     *  - 其余原样返回。
     */
    fun valueToRestore(snapshot: String?): String? {
        val v = snapshot?.trim()
        if (v.isNullOrEmpty() || v.equals("null", true) || v.equals("undefined", true)) return null
        val n = v.toLongOrNull()
        if (n != null && n <= 0L) return null
        if (n == SESSION_TIMEOUT_SENTINEL) return DEFAULT_TIMEOUT_MS.toString()
        return v
    }
}
