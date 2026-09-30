package com.lockbar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockbar.app.BuildConfig
import com.lockbar.app.R
import com.lockbar.app.ui.theme.isDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 首页：模块状态专页 —— KernelSU 式大绿框 + 模块信息 + 模块介绍。 */
@Composable
internal fun HomePage(
    innerPadding: PaddingValues,
    bottomBarHeight: Dp,
    scrollBehavior: ScrollBehavior,
) {
    val activated = rememberServiceBound()

    LazyColumn(
        modifier = Modifier.pageScroll(scrollBehavior),
        contentPadding = pagePadding(innerPadding, bottomBarHeight),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(activated) }
        item { ModuleInfoCard(activated) }
        item { IntroCard() }
    }
}

// ---------------------------------------------------------------- 状态大绿框

/**
 * 模块激活状态卡：KernelSU 首页那张大绿框 + 右下角对勾。
 *
 * 未激活时换成同结构的红色框，避免“看起来激活了其实没激活”。
 */
@Composable
private fun StatusCard(activated: Boolean) {
    val colorScheme = MiuixTheme.colorScheme
    val dynamic = MiuixTheme.isDynamicColor
    val dark = isDarkTheme()

    val container: Color
    val iconTint: Color
    val titleColor: Color
    val summaryColor: Color
    when {
        activated && dynamic -> {
            container = colorScheme.secondaryContainer
            iconTint = colorScheme.primary.copy(alpha = 0.8f)
            titleColor = colorScheme.onSecondaryContainer
            summaryColor = colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
        }

        activated && dark -> {
            container = Color(0xFF1A3825)
            iconTint = Color(0xFF36D167)
            titleColor = Color(0xFFE8FFF1)
            summaryColor = Color(0xFFB8E8C5)
        }

        activated -> {
            container = Color(0xFFDFFAE4)
            iconTint = Color(0xFF36D167)
            titleColor = Color(0xFF10291A)
            summaryColor = Color(0xFF2C5B3D)
        }

        dynamic -> {
            container = colorScheme.errorContainer
            iconTint = colorScheme.error
            titleColor = colorScheme.onErrorContainer
            summaryColor = colorScheme.onErrorContainer.copy(alpha = 0.75f)
        }

        dark -> {
            container = Color(0xFF3A1F21)
            iconTint = Color(0xFFF0736A)
            titleColor = Color(0xFFFFECEA)
            summaryColor = Color(0xFFF3C4C0)
        }

        else -> {
            container = Color(0xFFFCE9E7)
            iconTint = Color(0xFFE94634)
            titleColor = Color(0xFF3A1512)
            summaryColor = Color(0xFF7A3B36)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = container),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp),
        ) {
            Icon(
                imageVector = if (activated) Icons.Rounded.CheckCircleOutline else Icons.Rounded.ErrorOutline,
                contentDescription = if (activated) {
                    stringResource(R.string.status_activated)
                } else {
                    stringResource(R.string.status_not_activated)
                },
                tint = iconTint,
                modifier = Modifier
                    .size(104.dp)
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 18.dp, top = 16.dp, end = 96.dp),
            ) {
                Text(
                    text = stringResource(if (activated) R.string.status_activated else R.string.status_not_activated),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(
                        R.string.status_working_version,
                        "v" + BuildConfig.VERSION_NAME,
                    ),
                    fontSize = 15.sp,
                    color = summaryColor,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (activated) {
                        stringResource(R.string.status_activated_summary)
                    } else {
                        stringResource(R.string.status_not_activated_summary)
                    },
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = summaryColor.copy(alpha = 0.85f),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 模块信息

@Composable
private fun ModuleInfoCard(activated: Boolean) {
    GroupCard(title = stringResource(R.string.home_info_title)) {
        InfoRow(Icons.Rounded.Tag, stringResource(R.string.info_module_name), stringResource(R.string.info_module_name_value))
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        InfoRow(
            Icons.Rounded.Info,
            stringResource(R.string.info_module_version),
            "v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        InfoRow(Icons.Rounded.Code, stringResource(R.string.info_hook_version), stringResource(R.string.info_hook_version_value))
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        InfoRow(Icons.Rounded.Security, stringResource(R.string.info_scope), stringResource(R.string.info_scope_value))
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        InfoRow(
            Icons.Rounded.Link,
            stringResource(R.string.info_service),
            stringResource(if (activated) R.string.info_service_on else R.string.info_service_off),
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String) {
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
        Spacer(Modifier.size(12.dp))
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

// ------------------------------------------------------------------ 模块介绍

@Composable
private fun IntroCard() {
    val colorScheme = MiuixTheme.colorScheme
    GroupCard(title = stringResource(R.string.home_intro_title)) {
        Text(
            text = stringResource(R.string.home_intro),
            style = MiuixTheme.textStyles.footnote1,
            color = colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}
