package com.lockbar.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockbar.app.BuildConfig
import com.lockbar.app.Prefs
import com.lockbar.app.R
import com.lockbar.app.update.UpdateChecker
import com.lockbar.app.update.UpdateState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 本模块自己的仓库：关于页「访问该项目」直接跳这里。 */
private const val PROJECT_URL = "https://github.com/miyupyxh/LiftArc"

/**
 * 关于页：Logo + 应用名 + 版本（参考 KernelSU 设置-关于），下面一个 MiuiX 框放作者信息。
 */
@Composable
internal fun AboutScreen(onBack: () -> Unit, onOpenCredits: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val uriHandler = LocalUriHandler.current

    // 这里只显示检查状态和开关；更新弹窗由 App 根页面统一处理。
    val updateState by UpdateChecker.state.collectAsState()
    // 默认自动检查；开关改了立刻落盘，下次启动按它的值决定查不查
    var autoCheck by rememberPrefBool(Prefs.KEY_AUTO_UPDATE, true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.about_title),
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
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_app_icon),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.about_module_summary) + " · v" + BuildConfig.VERSION_NAME,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    // 作者
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_avatar_mimo),
                            contentDescription = stringResource(R.string.about_author),
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(colorScheme.surfaceContainerHigh),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.about_author),
                                style = textStyles.footnote2,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = stringResource(R.string.about_author_name),
                                style = textStyles.title4,
                                color = colorScheme.onBackground,
                            )
                            Text(
                                text = stringResource(R.string.about_author_summary),
                                style = textStyles.footnote2,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    AboutRow(Icons.Rounded.Info, stringResource(R.string.about_module_version), "v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    AboutRow(Icons.Rounded.Code, stringResource(R.string.about_hook_version), stringResource(R.string.info_hook_version_value))
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    AboutRow(Icons.Rounded.Security, stringResource(R.string.about_scope), stringResource(R.string.info_scope_value))
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    AboutRow(Icons.Rounded.Tag, stringResource(R.string.about_framework), stringResource(R.string.about_framework_value))
                }
            }

            // 四行合成一张卡：访问仓库 / 自动检查更新 / 检查更新 / 引用与致谢。
            // 全部去掉副标题 —— 这四行都是「动作」，一行说清楚就够了。
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    AboutNavRow(
                        icon = Icons.Rounded.Public,
                        title = stringResource(R.string.about_project),
                        // 手机上没装浏览器 / 链接失效都不该让页面崩掉
                        onClick = { runCatching { uriHandler.openUri(PROJECT_URL) } },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    AboutSwitchRow(
                        icon = Icons.Rounded.ArrowUpward,
                        title = stringResource(R.string.about_auto_update),
                        checked = autoCheck,
                        onCheckedChange = { autoCheck = it },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    AboutNavRow(
                        icon = Icons.Rounded.Refresh,
                        title = stringResource(R.string.about_check_update),
                        onClick = { UpdateChecker.check(manual = true) },
                        // 状态放行尾，不占副标题位置，四行高度才一致
                        trailing = {
                            UpdateStatusText(
                                state = updateState,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    AboutNavRow(
                        icon = Icons.Rounded.FormatQuote,
                        title = stringResource(R.string.about_credits),
                        onClick = onOpenCredits,
                    )
                }
            }

        }
    }
}

@Composable
private fun AboutRow(icon: ImageVector, title: String, value: String) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = textStyles.main,
            color = colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = textStyles.footnote1,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 关于页里的可跳转行：比 [AboutRow] 多一个按压态和右箭头。
 * 点击涟漪走 MiuixTheme 提供的 `LocalIndication`，和设置页里的偏好项手感一致。
 *
 * [trailing] 不传时右侧是右箭头；传了就用调用方给的（「检查更新」用它显示状态文字）。
 */
@Composable
private fun AboutNavRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = textStyles.main,
                color = colorScheme.onBackground,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = textStyles.footnote2,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = colorScheme.onBackground.copy(alpha = 0.35f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * 关于页里的开关行：左边的图标/标题和 [AboutNavRow] 逐像素对齐，右侧换成 Miuix 的开关。
 *
 * 整行可点（和 `SwitchPreference` 一样），不是只有开关本体能点。
 */
@Composable
private fun AboutSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            // 开关自带高度，上下内边距比文字行小一点，整行才和另外三行一样高
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = textStyles.main,
            color = colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 「检查更新」行尾巴上的状态：
 *
 * - 自动检查且没更新 / 失败 → 什么都不显示（不打扰）
 * - 手动检查 → 「已是最新」「检查失败」都要回一句，否则用户不知道点没点上
 * - 查到新版本 → 版本号，永远显示（自动检查也会显示，点进去就能看）
 */
@Composable
private fun UpdateStatusText(state: UpdateState, modifier: Modifier = Modifier) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    val text = when (state) {
        UpdateState.Idle -> null
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.UpToDate -> if (state.manual) stringResource(R.string.update_latest) else null
        is UpdateState.Found -> stringResource(R.string.update_available, state.info.version)
        is UpdateState.Failed -> if (state.manual) stringResource(R.string.update_failed) else null
    } ?: return

    Text(
        text = text,
        style = textStyles.footnote2,
        color = if (state is UpdateState.Found) colorScheme.primary
        else colorScheme.onSurfaceVariantSummary,
        modifier = modifier,
    )
}
