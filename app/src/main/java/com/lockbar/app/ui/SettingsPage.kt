package com.lockbar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lockbar.app.R
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** 设置页：主题设置 + 关于。 */
@Composable
internal fun SettingsPage(
    innerPadding: PaddingValues,
    bottomBarHeight: Dp,
    scrollBehavior: ScrollBehavior,
    onOpenSub: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.pageScroll(scrollBehavior),
        contentPadding = pagePadding(innerPadding, bottomBarHeight),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GroupCard(title = stringResource(R.string.group_appearance)) {
                ArrowPreference(
                    title = stringResource(R.string.settings_theme),
                    summary = stringResource(R.string.settings_theme_summary),
                    startAction = { PrefIcon(Icons.Rounded.Palette) },
                    onClick = { onOpenSub("theme") },
                )
            }
        }

        item {
            GroupCard(title = stringResource(R.string.group_about)) {
                ArrowPreference(
                    title = stringResource(R.string.settings_about),
                    summary = stringResource(R.string.settings_about_summary),
                    startAction = { PrefIcon(Icons.Rounded.Info) },
                    onClick = { onOpenSub("about") },
                )
            }
        }

        item {
            // 诊断与日志：排查「模块不生效」的入口，和参数页的诊断卡同属一族
            GroupCard(title = stringResource(R.string.group_debug)) {
                ArrowPreference(
                    title = stringResource(R.string.settings_log),
                    summary = stringResource(R.string.settings_log_summary),
                    startAction = { PrefIcon(Icons.Rounded.BugReport) },
                    onClick = { onOpenSub("log") },
                )
            }
        }
    }
}
