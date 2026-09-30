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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockbar.app.BuildConfig
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

/**
 * 关于页：Logo + 应用名 + 版本（参考 KernelSU 设置-关于），下面一个 MiuiX 框放作者信息。
 */
@Composable
internal fun AboutScreen(onBack: () -> Unit, onOpenCredits: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

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

            // 单独一张卡片：这是个「按钮」，不是只读信息，所以和上面的信息卡分开
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    AboutNavRow(
                        icon = Icons.Rounded.FormatQuote,
                        title = stringResource(R.string.about_credits),
                        summary = stringResource(R.string.about_credits_summary),
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
 */
@Composable
private fun AboutNavRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
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
            Text(
                text = summary,
                style = textStyles.footnote2,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = colorScheme.onBackground.copy(alpha = 0.35f),
            modifier = Modifier.size(17.dp),
        )
    }
}
