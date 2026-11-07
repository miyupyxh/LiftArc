package com.lockbar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lockbar.app.Prefs
import com.lockbar.app.R
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** 设置页：主题设置 + 关于。 */
@Composable
internal fun SettingsPage(
    innerPadding: PaddingValues,
    bottomBarHeight: Dp,
    scrollBehavior: ScrollBehavior,
    onOpenSub: (String) -> Unit,
) {
    var safetyDisabled by rememberPrefBool(Prefs.KEY_SAFETY_DISABLED, false)

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val launcherAlias = "com.lockbar.app.ui.LauncherAlias"
    fun launcherHidden(): Boolean = runCatching {
        ctx.packageManager.getComponentEnabledSetting(
            android.content.ComponentName(ctx.packageName, launcherAlias),
        ) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(false)

    var hideIcon by remember { mutableStateOf(launcherHidden()) }

    LazyColumn(
        modifier = Modifier.pageScroll(scrollBehavior),
        contentPadding = pagePadding(innerPadding, bottomBarHeight),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GroupCard(title = stringResource(R.string.group_appearance)) {
                ArrowPreference(
                    title = stringResource(R.string.settings_theme),
                    summary = optSummary(R.string.settings_theme_summary),
                    startAction = { PrefIcon(Icons.Rounded.Palette) },
                    onClick = { onOpenSub("theme") },
                )
            }
        }

        item {
            GroupCard(title = stringResource(R.string.group_safety)) {
                SwitchPreference(
                    title = stringResource(R.string.settings_safety_disabled),
                    summary = optSummary(R.string.settings_safety_disabled_summary),
                    startAction = { PrefIcon(Icons.Rounded.Security) },
                    checked = safetyDisabled,
                    onCheckedChange = { safetyDisabled = it },
                )
            }
        }

        item {
            GroupCard(title = stringResource(R.string.group_about)) {
                SwitchPreference(
                    title = stringResource(R.string.settings_hide_launcher),
                    summary = optSummary(R.string.settings_hide_launcher_summary),
                    startAction = { PrefIcon(Icons.Rounded.VisibilityOff) },
                    checked = hideIcon,
                    onCheckedChange = { want ->
                        hideIcon = want
                        runCatching {
                            ctx.packageManager.setComponentEnabledSetting(
                                android.content.ComponentName(ctx.packageName, launcherAlias),
                                if (want) {
                                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                } else {
                                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                },
                                android.content.pm.PackageManager.DONT_KILL_APP,
                            )
                        }
                    },
                )

                ArrowPreference(
                    title = stringResource(R.string.settings_about),
                    summary = optSummary(R.string.settings_about_summary),
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
                    summary = optSummary(R.string.settings_log_summary),
                    startAction = { PrefIcon(Icons.Rounded.BugReport) },
                    onClick = { onOpenSub("log") },
                )
            }
        }
    }
}
