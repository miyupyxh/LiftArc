package com.lockbar.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lockbar.app.Prefs
import com.lockbar.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模块日志页：显示 SystemUI 进程写回来的运行时间线。
 *
 * <p>专门服务于“有人测试模块不生效”—— 一眼能看出模块到底有没有被加载、作用域对不对、
 * 远端配置拿到没有、哪些 hook 挂失败了。骨架与 [CreditsScreen] / [AboutScreen] 一致
 * （Scaffold + TopAppBar + LazyColumn），不挂 BackHandler（由 NavDisplay 接管返回）。
 */
@Composable
internal fun LogScreen(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val context = LocalContext.current

    val log = rememberModuleLog()
    val serviceBound = rememberServiceBound()

    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }

    var exported by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    LaunchedEffect(exported, exportFailed) {
        if (exported || exportFailed) {
            kotlinx.coroutines.delay(2500)
            exported = false
            exportFailed = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.log_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 环境卡：连接状态是“模块不生效”最常见的原因，放最前面
            item {
                GroupCard(title = stringResource(R.string.log_group_env)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    if (serviceBound) R.string.log_service_ok
                                    else R.string.log_service_missing
                                ),
                                style = textStyles.main,
                                fontWeight = FontWeight.Bold,
                                color = if (serviceBound) colorScheme.onBackground else colorScheme.error,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(
                                    if (serviceBound) R.string.log_service_ok_summary
                                    else R.string.log_service_missing_summary
                                ),
                                style = textStyles.footnote2,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.log_lines_label),
                                style = textStyles.main,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (log.isBlank()) {
                                    stringResource(R.string.log_empty_short)
                                } else {
                                    stringResource(
                                        R.string.log_lines_value,
                                        log.trimEnd().count { it == '\n' } + 1,
                                    )
                                },
                                style = textStyles.footnote2,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            // 操作区：复制直接粘进聊天框；导出成 txt 存进「下载」并自动拉起分享
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                // 用 framework 剪贴板而不是 LocalClipboardManager：后者已废弃，
                                // 新的 LocalClipboard API 是 suspend 的，而 framework 这条全版本稳定
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("LiftArc log", log))
                                copied = true
                            },
                            enabled = log.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(
                                    if (copied) R.string.log_copied else R.string.log_copy,
                                ),
                            )
                        }

                        Button(
                            onClick = {
                                val uri = exportToDownloads(context, log)
                                if (uri == null) {
                                    exportFailed = true
                                } else {
                                    // 先落盘再分享：分享面板就算被某个应用拒了，
                                    // 文件也已经在「下载」里，用户自己还发得出去
                                    exported = true
                                    shareLog(context, uri)
                                }
                            },
                            enabled = log.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = when {
                                    exportFailed -> stringResource(R.string.log_export_failed)
                                    exported -> stringResource(R.string.log_exported)
                                    else -> stringResource(R.string.log_export)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.log_export_hint),
                        style = textStyles.footnote2,
                        color = colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            // 一键清空：两头都清才算数 —— 本地展示副本立刻删掉；
                            // 远端 log_clear 时间戳经 sync 推给 hook，把 SystemUI 进程内的
                            // 环形缓冲也清掉（不清的话下一次 flush 会把老日志原样灌回来）
                            Prefs.debug(context).edit().remove(Prefs.KEY_DEBUG_LOG).apply()
                            Prefs.putLong(Prefs.KEY_LOG_CLEAR, System.currentTimeMillis())
                            sync()
                        },
                        enabled = log.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.log_clear))
                    }
                }
            }

            // 正文
            item {
                if (log.isBlank()) {
                    Text(
                        text = stringResource(R.string.log_empty),
                        style = textStyles.footnote2,
                        color = colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                    )
                } else {
                    GroupCard(title = stringResource(R.string.log_group_content)) {
                        Text(
                            text = log.trimEnd(),
                            style = textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                            color = colorScheme.onBackground.copy(alpha = 0.85f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 把日志写进系统「下载」目录。
 *
 * <p>`minSdk = 33`（Android 13），写 [MediaStore.Downloads] **不需要任何存储权限**，
 * 所以 Manifest 里一个权限都不用加 —— 这也是选 MediaStore 而不是自己存私有目录的原因：
 * 测试者不用连电脑、不用 root 就能在文件管理里直接找到。
 *
 * <p>[MediaStore.MediaColumns.IS_PENDING] 是关键：置 1 让文件在写完之前对其它应用隐藏，
 * 避免扫描器读到半截内容；写完再清 0，文件才会出现在「下载」列表里。
 *
 * @return 写入成功返回 [Uri]（拉分享面板必须用它），失败返回 null
 */
private fun exportToDownloads(context: Context, text: String): Uri? {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "LiftArc-log-$stamp.txt")
        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        val out = resolver.openOutputStream(uri)
        if (out == null) {
            resolver.delete(uri, null, null)
            return null
        }
        out.use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.flush()
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null, null,
        )
        uri
    } catch (t: Throwable) {
        try {
            resolver.delete(uri, null, null)
        } catch (ignored: Throwable) {
            // 清理失败就留着，别让这里把导出流程带崩
        }
        null
    }
}

/**
 * 拉起系统分享面板，把刚导出的文件发出去。
 *
 * <p>[Intent.FLAG_GRANT_READ_URI_PERMISSION] 不能省 —— 目标应用拿到的是我们刚建的
 * MediaStore Uri，没有这个 flag 它读不到内容，表现就是「分享过去是个空文件」。
 */
private fun shareLog(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "LiftArc 模块日志")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}
