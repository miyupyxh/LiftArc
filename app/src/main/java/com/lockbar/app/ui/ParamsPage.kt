package com.lockbar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lockbar.app.Prefs
import com.lockbar.app.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** 参数页：常规 / 手感 / 弧形描边 / 诊断。 */
@Composable
internal fun ParamsPage(
    innerPadding: PaddingValues,
    bottomBarHeight: Dp,
    scrollBehavior: ScrollBehavior,
    onOpenSub: (String) -> Unit,
    /** 模态弹窗（取色器）的可见性：MainShell 据此让开悬浮底栏，否则底栏会盖在弹窗之上。 */
    onModalChange: (Boolean) -> Unit,
) {
    var enabled by rememberPrefBool(Prefs.KEY_ENABLED, true)
    var showHandle by rememberPrefBool(Prefs.KEY_SHOW_HANDLE, true)
    var parallax by rememberPrefBool(Prefs.KEY_PARALLAX, true)
    var releaseReturn by rememberPrefBool(Prefs.KEY_RELEASE_RETURN, true)
    var haptic by rememberPrefBool(Prefs.KEY_HAPTIC, true)
    var arcEnabled by rememberPrefBool(Prefs.KEY_ARC_ENABLED, true)
    var arcDim by rememberPrefBool(Prefs.KEY_ARC_DIM, true)
    var interceptSwipe by rememberPrefBool(Prefs.KEY_INTERCEPT_SWIPE, false)
    var handleWide by rememberPrefBool(Prefs.KEY_HANDLE_WIDE, false)
    var dragRatio by rememberPrefFloat(Prefs.KEY_DRAG_RATIO, 0.85f)
    var handleAlpha by rememberPrefFloat(Prefs.KEY_HANDLE_ALPHA, 1f)
    var arcWidth by rememberPrefFloat(Prefs.KEY_ARC_WIDTH, 3f)
    var arcAlpha by rememberPrefFloat(Prefs.KEY_ARC_ALPHA, 1f)
    var dimStrength by rememberPrefFloat(Prefs.KEY_DIM_STRENGTH, 0.57f)
    var arcColorValue by rememberPrefInt(Prefs.KEY_ARC_COLOR, 0xFFFFFFFF.toInt())

    // 取色器：拖动过程中只改本地状态，关掉对话框时才落盘 + 同步（避免每帧跨进程写）
    var showArcColor by remember { mutableStateOf(false) }
    var pendingArcColor by remember { mutableStateOf(arcColorValue) }

    // 取色器是模态的：显示时通知 MainShell 撤掉悬浮底栏。
    // 离开组合时补一次 false —— 免得切标签的瞬间把底栏永久留在隐藏态。
    SideEffect { onModalChange(showArcColor) }
    DisposableEffect(Unit) { onDispose { onModalChange(false) } }
    val commitArcColor: () -> Unit = {
        showArcColor = false
        if (pendingArcColor != arcColorValue) {
            arcColorValue = pendingArcColor
        }
    }

    LazyColumn(
        modifier = Modifier.pageScroll(scrollBehavior),
        contentPadding = pagePadding(innerPadding, bottomBarHeight),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GroupCard(title = stringResource(R.string.group_general)) {
                SwitchPreference(
                    title = stringResource(R.string.pref_enabled),
                    summary = stringResource(R.string.pref_enabled_summary),
                    startAction = { PrefIcon(Icons.Rounded.AutoAwesome) },
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_show_handle),
                    summary = stringResource(R.string.pref_show_handle_summary),
                    startAction = { PrefIcon(Icons.Rounded.Backup) },
                    enabled = enabled,
                    checked = showHandle,
                    onCheckedChange = { showHandle = it },
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_parallax),
                    summary = stringResource(R.string.pref_parallax_summary),
                    startAction = { PrefIcon(Icons.Rounded.Wallpaper) },
                    enabled = enabled,
                    checked = parallax,
                    onCheckedChange = { parallax = it },
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_intercept_swipe),
                    summary = stringResource(R.string.pref_intercept_swipe_summary),
                    startAction = { PrefIcon(Icons.Rounded.Swipe) },
                    enabled = enabled,
                    checked = interceptSwipe,
                    onCheckedChange = { interceptSwipe = it },
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_handle_wide),
                    summary = stringResource(R.string.pref_handle_wide_summary),
                    startAction = { PrefIcon(Icons.Rounded.SwapHoriz) },
                    enabled = enabled,
                    checked = handleWide,
                    onCheckedChange = { handleWide = it },
                )
                ArrowPreference(
                    title = stringResource(R.string.pref_hint_page),
                    summary = stringResource(R.string.pref_hint_page_summary),
                    startAction = { PrefIcon(Icons.Rounded.TextFields) },
                    enabled = enabled,
                    onClick = { onOpenSub("hint") },
                )
            }
        }

        item {
            GroupCard(title = stringResource(R.string.group_feel)) {
                SliderPreference(
                    title = stringResource(R.string.pref_drag_ratio),
                    summary = stringResource(R.string.pref_drag_ratio_summary),
                    startAction = { PrefIcon(Icons.Rounded.Swipe) },
                    value = dragRatio,
                    onValueChange = { dragRatio = it },
                    valueText = "${(dragRatio * 100).toInt()}%",
                    valueRange = 0.3f..1f,
                    enabled = enabled && parallax,
                )
                SliderPreference(
                    title = stringResource(R.string.pref_handle_alpha),
                    summary = stringResource(R.string.pref_handle_alpha_summary),
                    startAction = { PrefIcon(Icons.Rounded.Tune) },
                    value = handleAlpha,
                    onValueChange = { handleAlpha = it },
                    valueText = "${(handleAlpha * 100).toInt()}%",
                    valueRange = 0.1f..1f,
                    enabled = enabled && showHandle,
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_release_return),
                    summary = stringResource(R.string.pref_release_return_summary),
                    startAction = { PrefIcon(Icons.Rounded.Refresh) },
                    enabled = enabled && parallax,
                    checked = releaseReturn,
                    onCheckedChange = { releaseReturn = it },
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_haptic),
                    summary = stringResource(R.string.pref_haptic_summary),
                    startAction = { PrefIcon(Icons.Rounded.Vibration) },
                    enabled = enabled,
                    checked = haptic,
                    onCheckedChange = { haptic = it },
                )
            }
        }

        item {
            GroupCard(title = stringResource(R.string.group_arc)) {
                SwitchPreference(
                    title = stringResource(R.string.pref_arc_enabled),
                    summary = stringResource(R.string.pref_arc_enabled_summary),
                    startAction = { PrefIcon(Icons.Rounded.ColorLens) },
                    enabled = enabled,
                    checked = arcEnabled,
                    onCheckedChange = { arcEnabled = it },
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_arc_dim),
                    summary = stringResource(R.string.pref_arc_dim_summary),
                    startAction = { PrefIcon(Icons.Rounded.DarkMode) },
                    enabled = enabled && arcEnabled,
                    checked = arcDim,
                    onCheckedChange = { arcDim = it },
                )
                SliderPreference(
                    title = stringResource(R.string.pref_dim_strength),
                    summary = stringResource(R.string.pref_dim_strength_summary),
                    startAction = { PrefIcon(Icons.Rounded.BrightnessLow) },
                    value = dimStrength,
                    onValueChange = { dimStrength = it },
                    valueText = "${(dimStrength * 100).toInt()}%",
                    valueRange = 0.05f..1f,
                    enabled = enabled && arcEnabled && arcDim,
                )
                SliderPreference(
                    title = stringResource(R.string.pref_arc_width),
                    summary = stringResource(R.string.pref_arc_width_summary),
                    startAction = { PrefIcon(Icons.Rounded.Tune) },
                    value = arcWidth,
                    onValueChange = { arcWidth = it },
                    valueText = "${arcWidth.toInt()} dp",
                    valueRange = 1f..10f,
                    // 描边宽度只对传统描边有意义；压暗模式压的是弧线以下区域，没有"宽度"
                    enabled = enabled && arcEnabled && !arcDim,
                )
                SliderPreference(
                    title = stringResource(R.string.pref_arc_alpha),
                    summary = stringResource(R.string.pref_arc_alpha_summary),
                    startAction = { PrefIcon(Icons.Rounded.Wallpaper) },
                    value = arcAlpha,
                    onValueChange = { arcAlpha = it },
                    valueText = "${(arcAlpha * 100).toInt()}%",
                    valueRange = 0.1f..1f,
                    // 压暗模式的深浅改由「压暗强度」条单独控制
                    enabled = enabled && arcEnabled && !arcDim,
                )
                ArrowPreference(
                    title = stringResource(R.string.pref_arc_color),
                    summary = stringResource(R.string.pref_arc_color_summary) + " · " + hexOf(arcColorValue),
                    startAction = { PrefIcon(Icons.Rounded.ColorLens) },
                    // 压暗模式固定用黑，取色器只对描边有意义
                    enabled = enabled && arcEnabled && !arcDim,
                    onClick = {
                        pendingArcColor = arcColorValue
                        showArcColor = true
                    },
                    endActions = {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(Color(arcColorValue), CircleShape),
                        )
                    },
                )
            }
        }

        item { DebugCard() }
    }

    ColorDialog(
        show = showArcColor,
        color = pendingArcColor,
        title = stringResource(R.string.pref_arc_color),
        onColorChange = { pendingArcColor = it },
        onDismiss = commitArcColor,
    )
}
