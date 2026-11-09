package com.lockbar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lockbar.app.BuildConfig
import com.lockbar.app.Prefs
import com.lockbar.app.R
import com.lockbar.app.update.UpdateChecker
import com.lockbar.app.update.UpdateInfo
import com.lockbar.app.update.UpdateState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * App 根页面的更新弹窗控制器。
 *
 * 自动开关开启时，每次检查到新版本都弹出，不记录「已提示过」；手动检查也每次都弹出。
 * 放在根页面后，检查结果无论在哪个页面到达，都能直接提示用户。
 */
@Composable
internal fun AppUpdateDialog() {
    val updateState by UpdateChecker.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    var shownUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

    LaunchedEffect(updateState) {
        val s = updateState
        if (s !is UpdateState.Found) return@LaunchedEffect
        if (s.manual || Prefs.getBoolean(Prefs.KEY_AUTO_UPDATE, true)) {
            shownUpdate = s.info
        }
    }

    UpdateDialog(
        info = shownUpdate,
        currentVersion = BuildConfig.VERSION_NAME,
        onDismiss = { shownUpdate = null },
        onOpenDownload = {
            runCatching { uriHandler.openUri(UpdateChecker.DOWNLOAD_URL) }
            shownUpdate = null
        },
    )
}

/**
 * 「发现新版本」弹窗。
 *
 * 形态照抄项目里已有的 [RestartDialog]：Miuix 的 [OverlayDialog] 在手机上默认就是从屏幕
 * 底部滑出，所以这里**不传 `largeScreen`**，保持贴底。
 *
 * 内容只放三块：版本对比、这次的更新条目（可滚动）、底部「取消 / 前往」。
 * 条目来自仓库的 `update.md`，解析规则见 `UpdateChecker.parse`。
 */
@Composable
internal fun UpdateDialog(
    info: UpdateInfo?,
    currentVersion: String,
    onDismiss: () -> Unit,
    onOpenDownload: () -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    OverlayDialog(
        show = info != null,
        title = stringResource(R.string.update_dialog_title),
        onDismissRequest = onDismiss,
    ) {
        // show=false 的最后一帧还会组合一次，这里必须空保护
        val data = info ?: return@OverlayDialog

        Column(modifier = Modifier.fillMaxWidth()) {
            // 当前版本 → 新版本
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "v$currentVersion",
                    style = textStyles.main,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = " → ",
                    style = textStyles.main,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = "v${data.version}",
                    style = textStyles.title4,
                    color = colorScheme.primary,
                )
            }

            Spacer(Modifier.height(12.dp))

            // 条目多的时候（一次发很多条）不能把按钮顶出屏幕：内容区限高、内部滚动
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (data.notes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.update_dialog_empty),
                        style = textStyles.footnote2,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
                data.notes.forEach { note ->
                    if (note.heading) {
                        Text(
                            text = note.text,
                            style = textStyles.title4,
                            color = colorScheme.onBackground,
                        )
                    } else {
                        Text(
                            // 用圆点而不是 `-`：markdown 的列表符号在弹窗里看着像代码
                            text = "· ${note.text}",
                            style = textStyles.main,
                            color = colorScheme.onBackground,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            // 取消在左、前往在右；两个都靠右排，和 RestartDialog 的单按钮位置对齐
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onDismiss,
                    // 次要按钮：不抢「前往」的主色
                    colors = ButtonDefaults.buttonColors(
                        color = colorScheme.surfaceContainerHigh,
                        contentColor = colorScheme.onBackground,
                    ),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onOpenDownload) {
                    Text(stringResource(R.string.update_dialog_open))
                }
            }
        }
    }
}
