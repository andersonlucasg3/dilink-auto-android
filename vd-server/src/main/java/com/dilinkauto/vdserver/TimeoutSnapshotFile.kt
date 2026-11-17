package com.dilinkauto.vdserver

import com.dilinkauto.protocol.VdDeploy
import java.io.File

/**
 * `screen_off_timeout` 原值的跨进程存档（默认 `/sdcard/DiLinkAuto/screen-timeout.snapshot`）。
 *
 * 为什么需要它（2026-10-17 S19 真机实证）：会话快照原本只存内存
 * （[DisplayPowerController.saveCurrentIme] 的字段），会话被 SIGKILL
 * （黑屏重建 / 看门狗 `Runtime.halt(1)` / Eject 竞态）后丢失，下个会话
 * `settings get` 读回哨兵、只能 fallback 60s——用户的息屏设置被改小且
 * 无法找回（vd-server.log 行 205/206 逐字可见）。把原值落盘后，kill 路径
 * 下个会话也能恢复真实原值。
 *
 * 文件语义 = 「最近一次已知的用户原值」：每次会话开始都覆盖写入（读到哨兵时
 * 先用旧存档修正再写，见 [SessionScreenTimeout.effectiveSnapshot]）。**故意不删**
 * ——残留无害（下个会话开始即覆盖），且正常结束路径的恢复也读它兜底。
 *
 * 读失败一律返回 null、写失败一律吞掉：settings 面/文件面任何一侧坏掉时，
 * 调用方按"无存档"退化（= 修复前的行为），绝不让本机制成为新的故障点。
 *
 * **单测注意**：默认路径在 JVM 单测里会按工作目录解析（Windows 上 = `C:\sdcard\...`），
 * 必须注入临时路径（见 `DisplayPowerControllerRestoreTest` 的 `tempSnapshotFile()`），
 * 否则跨测试互相污染（2026-10-17 实测：healthy 测试写入的值被 sentinel 测试读到）。
 */
internal class TimeoutSnapshotFile(
    private val path: String = "${VdDeploy.DIR_PATH}/${SessionScreenTimeout.SNAPSHOT_FILE_NAME}"
) {
    /** 读取存档值；缺失 / 空 / 读失败均返回 null。 */
    fun read(): String? = try {
        File(path).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** 覆盖写入存档值；写失败静默（退化为无存档）。 */
    fun write(value: String) {
        try {
            File(path).apply { parentFile?.mkdirs() }.writeText(value)
        } catch (_: Exception) {
        }
    }
}
