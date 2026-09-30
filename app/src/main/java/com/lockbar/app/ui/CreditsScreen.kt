package com.lockbar.app.ui

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lockbar.app.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一条引用：图标 + 名称 + 许可证 + 说明，[url] 为空表示没有主页可跳（例如闭源 APK）。 */
private data class CreditItem(
    @StringRes val name: Int,
    @StringRes val desc: Int,
    @StringRes val license: Int,
    val icon: ImageVector,
    val url: String?,
)

/** 直接打进 APK 的库。 */
private val dependencyCredits = listOf(
    CreditItem(
        name = R.string.credit_miuix,
        desc = R.string.credit_miuix_desc,
        license = R.string.license_apache2,
        icon = Icons.Rounded.Palette,
        url = "https://github.com/compose-miuix-ui/miuix",
    ),
    CreditItem(
        name = R.string.credit_compose,
        desc = R.string.credit_compose_desc,
        license = R.string.license_apache2,
        icon = Icons.Rounded.Code,
        url = "https://developer.android.com/jetpack/compose",
    ),
    CreditItem(
        name = R.string.credit_libxposed,
        desc = R.string.credit_libxposed_desc,
        license = R.string.license_apache2,
        icon = Icons.Rounded.Security,
        // 仓库 github.com/libxposed/api 已随组织下线（POM 里留的是死链），改挂 LSPosed 官方文档：
        // 这一页讲的就是 io.github.libxposed.api 的用法，也正是本模块依赖的东西
        url = "https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API",
    ),
    CreditItem(
        name = R.string.credit_kotlinx,
        desc = R.string.credit_kotlinx_desc,
        license = R.string.license_apache2,
        icon = Icons.Rounded.Sync,
        url = "https://github.com/Kotlin/kotlinx.coroutines",
    ),
)

/** 参考过实现 / 界面的项目。 */
private val referenceCredits = listOf(
    CreditItem(
        name = R.string.credit_kernel,
        desc = R.string.credit_kernel_desc,
        // KernelSU 的 kernel/ 是 GPL-2.0-only，但 manager/（我们抄的底栏与动画）是 GPL-3.0-or-later
        license = R.string.license_gpl3,
        icon = Icons.Rounded.Style,
        url = "https://github.com/tiann/KernelSU",
    ),
    CreditItem(
        name = R.string.credit_liquidglass,
        desc = R.string.credit_liquidglass_desc,
        license = R.string.license_apache2,
        icon = Icons.Rounded.WaterDrop,
        url = "https://github.com/Kyant0/AndroidLiquidGlass",
    ),
    CreditItem(
        name = R.string.credit_hyperbetter,
        desc = R.string.credit_hyperbetter_desc,
        license = R.string.license_ref,
        icon = Icons.Rounded.AutoAwesome,
        url = "https://www.coolapk.com/u/1030764",
    ),
)

/**
 * 引用与致谢页：两组卡片（代码依赖 / 设计与实现参考）+ 底部说明。
 *
 * 骨架与 [AboutScreen] 完全一致（Scaffold + TopAppBar + LazyColumn），这样转场、顶栏收起、
 * 返回箭头的手感全都自动对齐。点击条目用系统浏览器打开项目主页。
 */
@Composable
internal fun CreditsScreen(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val uriHandler = LocalUriHandler.current

    val openUrl: (String) -> Unit = { url ->
        // 手机上没装浏览器 / 链接失效都不该让页面崩掉
        runCatching { uriHandler.openUri(url) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.credits_title),
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
                CreditGroup(
                    title = stringResource(R.string.credits_group_dep),
                    items = dependencyCredits,
                    onOpen = openUrl,
                )
            }

            item {
                CreditGroup(
                    title = stringResource(R.string.credits_group_ref),
                    items = referenceCredits,
                    onOpen = openUrl,
                )
            }

            item {
                Text(
                    text = stringResource(R.string.credits_note),
                    style = textStyles.footnote2,
                    color = colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun CreditGroup(
    title: String,
    items: List<CreditItem>,
    onOpen: (String) -> Unit,
) {
    GroupCard(title = title) {
        // forEachIndexed 是 inline 函数，里面直接调 Composable 是合法的
        items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            val url = item.url
            if (url == null) {
                CreditRow(item = item, onClick = null)
            } else {
                CreditRow(item = item, onClick = { onOpen(url) })
            }
        }
    }
}

@Composable
private fun CreditRow(item: CreditItem, onClick: (() -> Unit)?) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 没传 onClick 就是纯展示行，不要按压态
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(item.name),
                    style = textStyles.main,
                    color = colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(item.license),
                    style = textStyles.footnote2,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(item.desc),
                style = textStyles.footnote2,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = colorScheme.onBackground.copy(alpha = 0.35f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}
