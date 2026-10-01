package com.lockbar.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * 诊断数据回传通道：SystemUI 进程 → App 进程。
 *
 * ## 为什么需要它
 *
 * libxposed 官方 javadoc 对 `XposedInterface#getRemotePreferences` 的原话是：
 *
 * > Gets remote preferences stored in Xposed framework.
 * > Note that **those are read-only in hooked apps.**
 *
 * 也就是说 hook 进程里 `prefs.edit()` 必然抛
 * `UnsupportedOperationException: Read only implementation` ——
 * 「SystemUI 写回远端配置 → App 读」这条反向通道**从 API 契约上就不成立**。
 * 真机日志里连续 5 次 `failed to write debug_log` 就是这么来的，
 * `debug_status` / `debug_views` / `debug_block` / `debug_log` 一直全军覆没。
 *
 * `ContentProvider` 是 Android 里跨进程写数据的标准解法，而且调用方不需要
 * App 正在运行（系统会自动拉起进程），正合适做这条低频诊断链路。
 *
 * ## 存哪
 *
 * 独立的 [Prefs.DIAG_GROUP] 文件，**不和 `lockbar_settings` 混在一起** ——
 * 这样 `Prefs.syncToFramework` 只遍历 `lockbar_settings`，永远不会把回传数据冲掉。
 * （旧设计正是因为把 `debug_*` 放在同一组里才那么脆。）
 *
 * ## 安全
 *
 * `exported = true` 且没加权限，因为 SystemUI 与本模块签名不同、走不了 signature 权限。
 * 防护靠三件事，都写在 [call] 里：
 * 1. **method 白名单** —— 只认 4 个诊断键，别的方法名一律丢弃；
 * 2. **长度上限** —— 超过 [MAX_CHARS] 直接丢，防止被当垃圾桶；
 * 3. **只收不给** —— `query`/`insert`/`update`/`delete` 全部返回空，
 *     即使有人调进来也拿不走任何东西。
 *
 * 最坏情况是别的 App 往日志里塞假内容，仅此而已：没有提权面，没有数据泄露面。
 * 相比之下加 uid 校验的代价是 —— 只要某些 ROM 的 SystemUI 不是 uid 1000，
 * 日志就又没了，那正是这次要修的那类“有的手机不生效”。
 */
class DiagProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /** 只收 SystemUI 写回来的诊断文本。 */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (method !in ALLOWED) {
            return null
        }
        val value = extras?.getString(EXTRA_VALUE) ?: return null
        if (value.length > MAX_CHARS) {
            return null
        }
        Prefs.debug(ctx).edit().putString(method, value).apply()
        return Bundle.EMPTY
    }

    // 下面一律不实现：这个 Provider 是单向的，只进不出。
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** 与 AndroidManifest 里的 `android:authorities` 必须一致。 */
        const val AUTHORITY = "io.github.miyupyxh.liftarc.diag"

        const val EXTRA_VALUE = "value"

        /** 单条诊断值的长度上限；超过直接丢弃。 */
        const val MAX_CHARS = 16 * 1024

        /** 只认这几个 key —— 它们同时就是 `lockbar_debug` 里的 SP 键名。 */
        val ALLOWED = setOf(
            Prefs.KEY_DEBUG_STATUS,
            Prefs.KEY_DEBUG_VIEWS,
            Prefs.KEY_DEBUG_BLOCK,
            Prefs.KEY_DEBUG_LOG,
            // 安全模式标志：首页据此决定要不要显示「退出安全模式」按钮
            Prefs.KEY_DEBUG_SAFETY,
        )
    }
}
