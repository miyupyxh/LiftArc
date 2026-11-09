package com.lockbar.app.update

import android.util.Log
import com.lockbar.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * 更新说明里的一行。
 *
 * @param text 行内容（已经去掉 `- ` 前缀）
 * @param heading `### 修复` 这类分组小标题，渲染时单独一行、不挂圆点
 */
data class UpdateNote(val text: String, val heading: Boolean = false)

/** 从仓库 `update.md` 最新一段解析出来的版本信息。 */
data class UpdateInfo(
    val version: String,
    val notes: List<UpdateNote>,
    val downloadUrl: String,
)

/** 检查更新的四种结果，关于页那一行和更新弹窗都读这个。 */
sealed interface UpdateState {
    /** 还没查过（自动检查关掉时一直停在这里）。 */
    data object Idle : UpdateState

    data class Checking(val manual: Boolean) : UpdateState

    /** 已经是最新：手动查时要在行尾回一句「已是最新」，自动查就静默。 */
    data class UpToDate(val manual: Boolean) : UpdateState

    data class Found(val info: UpdateInfo, val manual: Boolean) : UpdateState

    data class Failed(val message: String, val manual: Boolean) : UpdateState
}

/**
 * 检查 GitHub 上的更新。
 *
 * ## 更新内容从哪来
 *
 * 读仓库根目录的 `update.md`（main 分支），跳过顶部空的 `## latest` 收件箱段，
 * 取下面**第一个带版本号的段**（最近一次发版归档出来的那段），
 * 里面的 `- ` 行就是这次要展示给用户的更新条目。发版由 GitHub Actions 完成归档，
 * 应用不用重新发就能读到新文案。
 *
 * 直连 `raw.githubusercontent.com`，失败自动换 `gh-proxy.com` 镜像再试一次。
 * 镜像只提供更新说明文本；「前往」始终打开官方 GitHub Release。
 *
 * ## 版本比较
 *
 * `update.md` 里的版本号 > `BuildConfig.VERSION_NAME` 才算有更新，按 `.` 分段逐段比数字，
 * 所以 `1.0.10` 会正确大于 `1.0.9`（字符串比较会错）。
 *
 * ## 线程
 *
 * 自己开一条线程跑网络，不依赖调用方的作用域 —— 关于页随时可能被销毁，
 * 检查结果仍然要写回 [state] 供下次进入时展示。
 */
object UpdateChecker {

    private const val TAG = "LiftArcUpdate"

    /** 本模块仓库。 */
    const val REPO_URL = "https://github.com/miyupyxh/LiftArc"

    /** 「前往」按钮的去处：永远指向最新 Release。 */
    const val DOWNLOAD_URL = "$REPO_URL/releases/latest"

    private const val RAW_URL = "https://raw.githubusercontent.com/miyupyxh/LiftArc/main/update.md"

    /** GitHub 镜像站，只在直连失败时使用一次。 */
    private const val MIRROR_PREFIX = "https://gh-proxy.com/"

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 10_000

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val requestSequence = AtomicLong(0)

    /** 关于页用 `collectAsState()` 订阅它。 */
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * 发起一次检查。
     *
     * 同类请求不重复发起；自动检查进行中时允许手动检查接管，并通过请求序号丢弃
     * 较早的自动检查结果，避免用户点击后只看到一个 `manual=false` 的静默结果。
     *
     * @param manual true = 用户点了「检查更新」（没更新也要给反馈）；
     *               false = 启动时自动检查（没更新就保持安静）
     */
    @Synchronized
    fun check(manual: Boolean = false) {
        val current = _state.value
        if (current is UpdateState.Checking && (current.manual || !manual)) return

        val requestId = requestSequence.incrementAndGet()
        _state.value = UpdateState.Checking(manual)
        Thread({
            val result = try {
                val info = fetch()
                Log.i(TAG, "update metadata: remote=${info.version}, installed=${BuildConfig.VERSION_NAME}, manual=$manual")
                if (compareVersion(info.version, BuildConfig.VERSION_NAME) > 0) {
                    UpdateState.Found(info, manual)
                } else {
                    UpdateState.UpToDate(manual)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "check update failed", t)
                UpdateState.Failed(t.message ?: "网络不可达", manual)
            }
            synchronized(this) {
                if (requestSequence.get() == requestId) {
                    _state.value = result
                }
            }
        }, "liftarc-update-check").start()
    }

    /** 直连失败换镜像，两个来源都失败才抛。 */
    private fun fetch(): UpdateInfo {
        val text = download(RAW_URL) ?: download(MIRROR_PREFIX + RAW_URL)
        ?: throw IOException("网络不可达")
        return parse(text) ?: throw IOException("更新说明格式不正确")
    }

    /** 下载文本；任何异常都吞掉返回 null，让调用方决定是否换镜像。 */
    private fun download(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                // raw.githubusercontent 前面有 CDN，默认会缓存几分钟。发版后立刻查也要能看到新段。
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "LiftArc/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            Log.w(TAG, "download failed: $url", t)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 解析 `update.md`。
     *
     * 约定格式（`update.md` 文件头的注释里也写了一遍）：
     *
     * ```
     * ## latest            ← 收件箱段，没有版本号，跳过
     *
     * ## v1.0.26 更新      ← 发版时由工作流归档出来的段，读这一段
     * - 修复锁屏上滑偶尔不跟手
     * - 新增锁屏小组件
     * ```
     *
     * 规则很死，是为了不让发版的人踩坑：
     * - 跳过标题正好是 `latest` 的段，取第一个能解析出版本号的 `## ` 段，后面的段落一律忽略
     * - 标题里不带版本号又没写 latest 的段也跳过，不当作错误
     * - `### 修复` / `### 更改` 这类小标题会原样成为一条分组标题
     * - `- ` 或 `* ` 开头才是条目，其余行（说明、空行）忽略
     * - 文件开头的 `<!-- ... -->` 注释整块跳过，注释里写什么都不影响解析
     */
    internal fun parse(md: String): UpdateInfo? {
        var version: String? = null
        val notes = mutableListOf<UpdateNote>()
        var inComment = false

        for (raw in md.lineSequence()) {
            val line = raw.trim()

            if (inComment) {
                if (line.contains("-->")) inComment = false
                continue
            }
            if (line.startsWith("<!--")) {
                if (!line.contains("-->")) inComment = true
                continue
            }

            if (line.startsWith("## ")) {
                // 第一段版本之后的段落全部不要：应用只提示「最新那一版改了什么」
                if (version != null) break
                val heading = line.removePrefix("## ").trim()
                // 文件顶部常驻一个空的 ## latest 收件箱（发版工作流往里写、发版时归档），
                // 它没有版本号，跳过继续往下找第一个真正带版本号的段。
                // 老版本应用不认识这一层，所以这里必须容错，不能让 latest 把解析顶掉。
                if (heading.equals("latest", ignoreCase = true)) continue
                // 认不出版本号的其它标题也跳过，别因为一行写错的标题就整篇失败
                version = extractVersion(heading) ?: continue
                continue
            }
            if (version == null) continue

            when {
                line.startsWith("### ") ->
                    notes += UpdateNote(line.removePrefix("### ").trim(), heading = true)

                line.startsWith("- ") || line.startsWith("* ") ->
                    notes += UpdateNote(line.drop(2).trim())
            }
        }

        val v = version ?: return null
        return UpdateInfo(version = v, notes = notes, downloadUrl = DOWNLOAD_URL)
    }

    /** 从 `v1.0.26` / `1.0.26` / `1.0.26 (126)` 里抠出纯数字版本号。 */
    private fun extractVersion(raw: String): String? =
        Regex("""(\d+(?:\.\d+)*)""").find(raw)?.groupValues?.get(1)

    /**
     * 按 `.` 分段比较两个版本号，返回 >0 表示 [a] 更新。
     *
     * 段数不同时缺的位补 0（`1.0` 与 `1.0.0` 相等），非数字段当 0 处理。
     */
    internal fun compareVersion(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
            if (diff != 0) return if (diff > 0) 1 else -1
        }
        return 0
    }
}
