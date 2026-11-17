package com.dilinkauto.client.service

import java.io.File

/**
 * ADB 安装 seam，供 [CarInstallCoordinator] 使用。
 *
 * 泛型于"session"类型：协调器只把这个句柄在 connect → readVersion → pushAndInstall → close
 * 之间透传，自身从不调用其方法。这样在没有真实 [dadb.Dadb] 的 JVM 测试里也能驱动整套安装
 * 状态机。生产实现是 [CarAppInstaller]（S = Dadb）。
 */
internal interface CarInstaller<S> {
    /** 连接 [carIp] 的车机；返回打开的 session，失败/超时返回 null。 */
    fun connect(carIp: String): S?

    /** 读取车机当前已安装版本名，未安装返回 "0"。 */
    fun readInstalledVersion(session: S): String

    /** 推送并安装 [apkFile]；返回 pm install 输出（成功含 "Success"）。 */
    fun pushAndInstall(session: S, apkFile: File, versionLabel: String): String

    /** 关闭 session。 */
    fun close(session: S)
}
