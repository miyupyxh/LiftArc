package com.lockbar.app

import java.io.BufferedReader

/**
 * 用 root 权限重启系统界面（`com.android.systemui` 进程）。
 *
 * hook 侧的改动必须重启 SystemUI 才生效，所以设置页右上角直接给了个按钮。
 * 用 `pidof` + `kill` 精确打进程，不用 `pkill -f`（后者会把命令自己的 shell 一起匹配掉）。
 */
object SystemUiRestarter {

    /** 同步执行一条命令，返回「退出码」和「stdout+stderr」。 */
    private fun exec(vararg cmd: String): Pair<Int, String> {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(cmd)
            val out = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val err = process.errorStream.bufferedReader().use(BufferedReader::readText)
            val code = process.waitFor()
            code to (out + err)
        } catch (t: Throwable) {
            -1 to (t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
                // 关不掉就算了
            }
        }
    }

    /**
     * 重启系统界面。
     *
     * @return `null` 表示成功；否则是给用户看的失败原因。
     */
    fun restart(): String? {
        val id = exec("su", "-c", "id")
        if (id.first != 0 || !id.second.contains("uid=0")) {
            return "获取 Root 失败，请先在 Root 管理器里允许本应用"
        }

        val pid = exec("su", "-c", "pidof com.android.systemui").second.trim()
        if (pid.isEmpty()) {
            return null // 进程已经不在了
        }

        exec("su", "-c", "kill $pid")
        Thread.sleep(800L)

        val still = exec("su", "-c", "pidof com.android.systemui").second.trim()
        if (still.isNotEmpty()) {
            exec("su", "-c", "kill -9 $still")
        }
        return null
    }
}
