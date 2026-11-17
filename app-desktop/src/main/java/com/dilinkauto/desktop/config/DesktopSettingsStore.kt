package com.dilinkauto.desktop.config

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `config.json` 的读写（audit R3-SRP-16）。
 *
 * 文件 IO 从 [DesktopSettings] 数据类里分出去：数据类只保留字段、键名与
 * 解析/序列化（[DesktopSettings.from] / [DesktopSettings.toJson]），
 * 本类负责"从哪个文件读、写到哪个文件、失败怎么汇报"。
 *
 * ── 原子写（audit D-02）──
 * 保存走"写 `config.json.tmp` → [Files.move] ATOMIC_MOVE + REPLACE_EXISTING"。
 * 此前的 `file.writeText` 是原地截断写：两次并发保存（DPI 修改 vs 常亮开关，两条
 * 不同线程）交错时另一方可能读到半截文件，极端情况下用户拿到一份损坏的
 * config.json —— 下次启动解析失败即静默回全默认（`dev_phone_ip` 丢失）。
 * 临时文件统一在 finally 里清理，失败不会留 `.tmp` 垃圾。
 *
 * ── 加载失败即重写（audit D-L6）──
 * 解析失败时除了回报原因，还用默认值把文件重写回去：否则损坏的 config.json 会
 * **永远**坏在那里，每次启动都静默回默认值，用户改的任何键都不生效。
 */
class DesktopSettingsStore(private val file: File = DesktopPaths.configFile()) {

    /**
     * 读取配置。
     *
     * 文件不存在返回默认值；读/解析失败时通过 [onError] 汇报原因，并**用默认值
     * 重写文件**（audit D-L6：让损坏的配置自愈，而不是每次启动都悄悄回默认）。
     */
    fun load(onError: (String) -> Unit = {}): DesktopSettings {
        if (!file.isFile) return DesktopSettings()
        val text = runCatching { file.readText() }
            .getOrElse {
                onError("读取 $file 失败: ${it.message}")
                return DesktopSettings()
            }
        val values = runCatching { JsonConfig.parse(text) }
            .getOrElse {
                onError("解析 $file 失败: ${it.message}")
                // 自愈：把坏文件换成默认配置，否则它会一直坏着（每次启动都回默认）。
                // 重写失败也要单独回报 —— 那是"用户改不动配置"的真问题。
                save(DesktopSettings()) { writeError -> onError("重写 $file 失败: $writeError") }
                return DesktopSettings()
            }
        return DesktopSettings.from(values)
    }

    /**
     * 写回配置文件。返回是否成功；失败时返回 false 并通过 [onError] 回报原因
     * （配置写不进去不该让程序起不来）。
     *
     * 原子写（audit D-02）：tmp + [Files.move]（ATOMIC_MOVE，带 REPLACE_EXISTING
     * 兜底 —— 个别文件系统不支持原子移动时会抛 AtomicMoveNotSupportedException，
     * 此时退回普通替换，至少不破坏原有行为）。
     */
    fun save(settings: DesktopSettings, onError: (String) -> Unit = {}): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return runCatching {
            file.parentFile?.mkdirs()
            tmp.writeText(settings.toJson())
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                // 不支持 ATOMIC_MOVE 的文件系统：退回普通替换（等价于旧行为）。
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            runCatching { tmp.delete() }
            onError("写入 $file 失败: ${it.message}")
        }.isSuccess
    }
}
