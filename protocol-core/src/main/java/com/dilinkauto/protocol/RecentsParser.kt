package com.dilinkauto.protocol

/**
 * `dumpsys activity recents` 输出解析 + 「快速切换」目标选择。
 *
 * 为什么需要它：「最近」导航键的原始实现是 `input -d N keyevent 187`，但
 * Android 15（AOSP）的系统 recents 硬编码作用于默认显示器 —— `-d N` 注入的
 * APP_SWITCH 实测只会 toggle 物理屏的最近任务（搅乱用户正在使用的物理屏），
 * 对 VirtualDisplay 完全无效，系统也没有 per-display recents 的 shell 接口
 * （`wm shell` 无相关命令、Launcher 的 RecentsActivity 未导出无法直接启动）。
 *
 * 替代实现：把「最近用过、但非当前 VD 顶层」的任务用
 * `am start --display N --task <id> -n <cmp>` 拉到 VD 前台 —— 实测该命令可把
 * 任务从任意 display 迁移过来并置于前台。本文件负责数据侧：
 * 解析任务列表、选出切换目标。
 *
 * 纯逻辑（无 Android 依赖，可单测）放 protocol-core —— 与 [VdDimensions]、
 * [BlackScreenDetector] 同一先例。
 */
data class RecentTask(
    /** `id=` 字段。`am start --task` 用它。 */
    val taskId: Int,
    /** baseIntent 的 `cmp=` 组件。可能是 `pkg/.Act` 短形式，`am -n` 接受。 */
    val component: String,
    /** [component] 的包名部分（`cmp=` 里 `/` 之前）。 */
    val packageName: String,
)

object RecentsParser {

    /**
     * 解析 `dumpsys activity recents` 的 `* RecentTaskInfo #N:` 块。
     *
     * 只保留「可切换」的条目：
     *  - `hasTask=true`（false = 只剩历史记录、task 体已死，`am start --task`
     *    会失败）；
     *  - 块内同时拿到 `id=` 与合法的 `cmp=` 组件。
     *
     * 顺序保持 dump 原序（最近活跃在前）。注意 dump 开头的
     * `taskId=... rootTaskId=...` 概要段不参与解析（那些行不以 `id=` /
     * `hasTask=` 开头，与块内字段自然区分）。
     */
    fun parse(dump: String): List<RecentTask> {
        val out = ArrayList<RecentTask>()
        var id: Int? = null
        var hasTask = false
        var component: String? = null

        fun flush() {
            val c = component
            val i = id
            if (hasTask && i != null && c != null) out += RecentTask(i, c, c.substringBefore('/'))
            id = null; hasTask = false; component = null
        }

        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("* RecentTaskInfo") -> flush()
                // 实机格式（Android 15）把三个字段挤在同一行：
                //   `id=2048 userId=0 hasTask=true lastActiveTime=849748141`
                // —— 旧格式 hasTask 可能单独成行，两种都解析。
                line.startsWith("id=") -> {
                    id = line.removePrefix("id=").substringBefore(' ').toIntOrNull()
                    fieldValue(line, "hasTask=")?.let { hasTask = it.equals("true", ignoreCase = true) }
                }
                line.startsWith("hasTask=") ->
                    hasTask = fieldValue(line, "hasTask=")?.equals("true", ignoreCase = true) ?: false
                line.startsWith("baseIntent=") ->
                    component = extractComponent(line)
            }
        }
        flush()
        return out
    }

    /** 取 `key=` 后到下一个空格为止的值；行内没有该 key 时返回 null。 */
    private fun fieldValue(line: String, key: String): String? {
        val i = line.indexOf(key)
        if (i < 0) return null
        return line.substring(i + key.length).substringBefore(' ').takeIf { it.isNotEmpty() }
    }

    /**
     * 「快速切换」目标：最近活跃、且不是「当前 VD 顶层 / 自家控制端 /
     * launcher、systemui」的任务；没有可切换目标时返回 null（调用方
     * 什么都不做，只记日志）。
     *
     * @param currentTaskId 当前 VD 顶层任务 id（由 `dumpsys activity
     *   activities` 的 display 段解析），null = 未知（不排除任何任务）。
     *   [selfPackage] 是控制端包名：把自家 Activity 拉进 VD 会踩
     *   "no focused window" 输入派发超时 → ANR（见 moveTopApp 的注释）。
     * @param allowedPackages 车机可见应用的包名集合（issue #21）。非空时只在该集合内
     *   挑选：建屏恢复属于"系统替用户做的动作"，不能把用户明确没勾选上车的应用
     *   （银行/聊天/相册等）拉到车机屏。空集合 = 不做白名单过滤（车机/桌面 ADB
     *   部署路径拿不到手机 prefs），行为与引入该参数前一致。
     */
    fun pickQuickSwitchTarget(
        tasks: List<RecentTask>,
        currentTaskId: Int?,
        selfPackage: String,
        allowedPackages: Set<String> = emptySet(),
    ): RecentTask? = tasks.firstOrNull { t ->
        t.taskId != currentTaskId &&
            t.packageName != selfPackage &&
            !t.packageName.contains("launcher", ignoreCase = true) &&
            !t.packageName.contains("systemui", ignoreCase = true) &&
            (allowedPackages.isEmpty() || t.packageName in allowedPackages)
    }

    /**
     * 从 `baseIntent=Intent { ... cmp=pkg/act ... }` 行提取 `cmp=` 值。
     *
     * 值必须过 [COMPONENT_REGEX]（与 shell 注入防护同一白名单）—— 它会被
     * 拼进 vd-server 侧以 shell UID 执行的 `am start -n` 命令行。
     * 不合形状（含空格/分号/引号等）一律返回 null，宁可少切一个任务。
     */
    private fun extractComponent(baseIntentLine: String): String? {
        val i = baseIntentLine.indexOf("cmp=")
        if (i < 0) return null
        val candidate = baseIntentLine.substring(i + 4)
            .substringBefore(' ')
            .substringBefore('}')
        return candidate.takeIf { COMPONENT_REGEX.matches(it) }
    }
}
