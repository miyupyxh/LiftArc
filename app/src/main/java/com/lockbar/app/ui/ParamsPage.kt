package com.lockbar.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BlurCircular
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
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
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 参数页：常规 / 手感 / 弧形描边 / 玻璃 / 诊断。
 *
 * **牵连关系一律「隐藏」，不给灰掉的禁用态**，而且隐藏**带收起 / 展开动画** —— 照 miuix
 * 官方文档《SwitchPreference · 带动画显示/隐藏的内容》那一节写：直接用 [AnimatedVisibility]
 * （单行包一行，多行再套一层 [Column]），而不是用 `if (…)` 让行凭空消失，也不是把 `enabled`
 * 往下传让行变灰：
 *
 * - 灰掉的行仍然占位，页面看着一堆死控件，用户也不知道它们为什么点不动；
 * - `if (…)` 虽然不占位，行却是「啪」一下不见的，跟 miuix 的动效不搭；
 * - [AnimatedVisibility] 收起时高度逐帧塌下去，底下的行跟着往上补位，开关一拨就有反馈。
 *
 * 最外层（手感 / 弧形描边 / 玻璃三组）同样用 [AnimatedVisibility] 包住：总开关一关，三组
 * 一起塌掉，页面只剩总开关自己。
 *
 * **组与组的 12dp 间距改放在各组内容底部的 padding 里**（原先靠 LazyColumn 的 spacedBy）：
 * spacedBy 按「条目槽位」算间距，组塌成 0 高度后槽位还在，屏上会多出几段 12dp 的空档；
 * 挪进内容里才能跟着一起塌。
 *
 * - 弧形描边只管它那一组（裁切 + 描边 / 压暗）；**玻璃是独立的一组**，不挂在弧形描边下面 ——
 *   xposed 侧把 clearClips 拆成 clearArcClips，关掉描边只收裁切与描边壳，玻璃条照样跟手，
 *   两件事从 UI 到 hook 彻底分家。
 */
@Composable
internal fun ParamsPage(
    innerPadding: PaddingValues,
    bottomBarHeight: Dp,
    scrollBehavior: ScrollBehavior,
    onOpenSub: (String) -> Unit,
    /** 取色器的可见性：MainShell 据此让开悬浮底栏，否则底栏会盖在弹窗之上。 */
    onModalChange: (Boolean) -> Unit,
    /**
     * 数值输入弹窗的可见性 —— **只转发，不落地存状态**（MainShell 的 `if` 才是它的状态）。
     * 在这里存的话没有组合内的读取者，写进去不触发重组，开关永远传不到。
     */
    onInputChange: (Boolean) -> Unit,
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
    // 速度解锁：松手够快就进解锁，与「进度过半」并列的第二条路
    var flickUnlock by rememberPrefBool(Prefs.KEY_FLICK_UNLOCK, true)
    var flickVelocity by rememberPrefFloat(Prefs.KEY_FLICK_VELOCITY, 1200f)
    // 解锁动画时长：条从松手位置飞到屏幕顶用多久，飞完这一段才拉解锁界面
    var unlockMs by rememberPrefFloat(Prefs.KEY_UNLOCK_MS, 340f)
    var handleAlpha by rememberPrefFloat(Prefs.KEY_HANDLE_ALPHA, 1f)
    var arcWidth by rememberPrefFloat(Prefs.KEY_ARC_WIDTH, 3f)
    var arcAlpha by rememberPrefFloat(Prefs.KEY_ARC_ALPHA, 1f)
    var dimStrength by rememberPrefFloat(Prefs.KEY_DIM_STRENGTH, 0.57f)
    var dimUpper by rememberPrefBool(Prefs.KEY_DIM_UPPER, false)
    var glassLive by rememberPrefBool(Prefs.KEY_GLASS_LIVE, false)
    // 上滑背景模糊（1.0.22）：开关 + 一条程度滑条；强度随进度连续渐强，不搞分级阶梯
    var blurOn by rememberPrefBool(Prefs.KEY_BLUR_ENABLED, false)
    var blurStrength by rememberPrefFloat(Prefs.KEY_BLUR_STRENGTH, 0.7f)
    // 玻璃厚度已随其余九项一起搬到「玻璃参数」页（GlassScreen），这里不再持有它的状态
    var arcColorValue by rememberPrefInt(Prefs.KEY_ARC_COLOR, 0xFFFFFFFF.toInt())

    // 取色器：拖动过程中只改本地状态，关掉对话框时才落盘 + 同步（避免每帧跨进程写）
    var showArcColor by remember { mutableStateOf(false) }
    var pendingArcColor by remember { mutableStateOf(arcColorValue) }

    // 取色器是模态的：显示时通知 MainShell 撤掉悬浮底栏。
    // 离开组合时补一次 false —— 免得切标签的瞬间把底栏永久留在隐藏态。
    // 数值输入弹窗**不在这里存状态**：本页没有人在组合里读它，写进去不会触发重组，
    // SideEffect 就永远跑不到（第一版就是这么哑掉的）。它走 onInputChange 直通
    // MainShell 的 if 条件，那儿有人读，写完立刻生效。
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
        // 这里刻意不放 verticalArrangement 的条目间距：组塌陷（AnimatedVisibility 收起）后
        // 槽位仍然占着 12dp，屏上会留空档。间距改由每个 item 的内容自己带 12dp 底 padding，
        // 收起时连间距一起塌。最后一个 item（诊断）是末尾，不带，底部仍是内容内边距。
    ) {
        item {
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                GroupCard(title = stringResource(R.string.group_general)) {
                    // 总开关自己不受牵连。下面五条只在开着时渲染 —— 灰掉它们没有意义，
                    // 反正总开关一关整页都停了；包一层是为了收起时有动画而不是瞬间消失。
                    SwitchPreference(
                        title = stringResource(R.string.pref_enabled),
                        summary = stringResource(R.string.pref_enabled_summary),
                        startAction = { PrefIcon(Icons.Rounded.AutoAwesome) },
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                    AnimatedVisibility(visible = enabled) {
                        Column {
                            SwitchPreference(
                                title = stringResource(R.string.pref_show_handle),
                                summary = stringResource(R.string.pref_show_handle_summary),
                                startAction = { PrefIcon(Icons.Rounded.Backup) },
                                checked = showHandle,
                                onCheckedChange = { showHandle = it },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.pref_parallax),
                                summary = stringResource(R.string.pref_parallax_summary),
                                startAction = { PrefIcon(Icons.Rounded.Wallpaper) },
                                checked = parallax,
                                onCheckedChange = { parallax = it },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.pref_intercept_swipe),
                                summary = stringResource(R.string.pref_intercept_swipe_summary),
                                startAction = { PrefIcon(Icons.Rounded.Swipe) },
                                checked = interceptSwipe,
                                onCheckedChange = { interceptSwipe = it },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.pref_handle_wide),
                                summary = stringResource(R.string.pref_handle_wide_summary),
                                startAction = { PrefIcon(Icons.Rounded.SwapHoriz) },
                                checked = handleWide,
                                onCheckedChange = { handleWide = it },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.pref_hint_page),
                                summary = stringResource(R.string.pref_hint_page_summary),
                                startAction = { PrefIcon(Icons.Rounded.TextFields) },
                                onClick = { onOpenSub("hint") },
                            )
                        }
                    }
                }
            }
        }

        // 下面三组只在总开关打开时存在，而且是「塌下去」不是直接从组合里摘掉
        item {
            AnimatedVisibility(visible = enabled) {
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    GroupCard(title = stringResource(R.string.group_feel)) {
                        // 「联动上滑」是这一组的根：关掉它，跟着它走的条目依次收起
                        AnimatedVisibility(visible = parallax) {
                            NumSliderPreference(
                                onInputChange = onInputChange,
                                title = stringResource(R.string.pref_drag_ratio),
                                summary = stringResource(R.string.pref_drag_ratio_summary),
                                startAction = { PrefIcon(Icons.Rounded.Swipe) },
                                value = dragRatio,
                                onValueChange = { dragRatio = it },
                                defaultValue = 0.85f,
                                valueRange = 0.3f..1f,
                                scale = 100f,
                                unit = "%",
                            )
                        }
                        AnimatedVisibility(visible = showHandle) {
                            NumSliderPreference(
                                onInputChange = onInputChange,
                                title = stringResource(R.string.pref_handle_alpha),
                                summary = stringResource(R.string.pref_handle_alpha_summary),
                                startAction = { PrefIcon(Icons.Rounded.Tune) },
                                value = handleAlpha,
                                onValueChange = { handleAlpha = it },
                                defaultValue = 1f,
                                valueRange = 0.1f..1f,
                                scale = 100f,
                                unit = "%",
                            )
                        }
                        AnimatedVisibility(visible = parallax) {
                            Column {
                                SwitchPreference(
                                    title = stringResource(R.string.pref_release_return),
                                    summary = stringResource(R.string.pref_release_return_summary),
                                    startAction = { PrefIcon(Icons.Rounded.Refresh) },
                                    checked = releaseReturn,
                                    onCheckedChange = { releaseReturn = it },
                                )
                                SwitchPreference(
                                    title = stringResource(R.string.pref_flick_unlock),
                                    summary = stringResource(R.string.pref_flick_unlock_summary),
                                    startAction = { PrefIcon(Icons.Rounded.FlashOn) },
                                    checked = flickUnlock,
                                    onCheckedChange = { flickUnlock = it },
                                )
                            }
                        }
                        // 速度解锁的阈值只在「速度解锁」本身开着时才有意义
                        AnimatedVisibility(visible = parallax && flickUnlock) {
                            NumSliderPreference(
                                onInputChange = onInputChange,
                                title = stringResource(R.string.pref_flick_velocity),
                                summary = stringResource(R.string.pref_flick_velocity_summary),
                                startAction = { PrefIcon(Icons.Rounded.Speed) },
                                value = flickVelocity,
                                onValueChange = { flickVelocity = it },
                                defaultValue = 1200f,
                                valueRange = 300f..3000f,
                                unit = " dp/s",
                            )
                        }
                        AnimatedVisibility(visible = parallax) {
                            NumSliderPreference(
                                onInputChange = onInputChange,
                                title = stringResource(R.string.pref_unlock_ms),
                                summary = stringResource(R.string.pref_unlock_ms_summary),
                                startAction = { PrefIcon(Icons.Rounded.Timer) },
                                value = unlockMs,
                                onValueChange = { unlockMs = it },
                                defaultValue = 340f,
                                valueRange = 150f..800f,
                                unit = " ms",
                            )
                        }
                        SwitchPreference(
                            title = stringResource(R.string.pref_haptic),
                            summary = stringResource(R.string.pref_haptic_summary),
                            startAction = { PrefIcon(Icons.Rounded.Vibration) },
                            checked = haptic,
                            onCheckedChange = { haptic = it },
                        )
                    }
                }
            }
        }

        item {
            AnimatedVisibility(visible = enabled) {
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    GroupCard(title = stringResource(R.string.group_arc)) {
                        // 「启用弧形描边」是本组的根，只管**裁切 + 描边/压暗**这一套。
                        // 玻璃不在它下面（见下面单独的「玻璃」组）。
                        SwitchPreference(
                            title = stringResource(R.string.pref_arc_enabled),
                            summary = stringResource(R.string.pref_arc_enabled_summary),
                            startAction = { PrefIcon(Icons.Rounded.ColorLens) },
                            checked = arcEnabled,
                            onCheckedChange = { arcEnabled = it },
                        )
                        AnimatedVisibility(visible = arcEnabled) {
                            SwitchPreference(
                                title = stringResource(R.string.pref_arc_dim),
                                summary = stringResource(R.string.pref_arc_dim_summary),
                                startAction = { PrefIcon(Icons.Rounded.DarkMode) },
                                checked = arcDim,
                                onCheckedChange = { arcDim = it },
                            )
                        }
                        // 压暗模式：分界线以下压暗，参数是「压暗方向 + 压暗强度」。
                        // 与下面的传统描边分支互斥，两个都写成同一层的条件，切换时一收一放。
                        AnimatedVisibility(visible = arcEnabled && arcDim) {
                            Column {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.pref_dim_side),
                                    items = listOf(
                                        stringResource(R.string.pref_dim_side_upper),
                                        stringResource(R.string.pref_dim_side_lower),
                                    ),
                                    selectedIndex = if (dimUpper) 0 else 1,
                                    onSelectedIndexChange = { dimUpper = it == 0 },
                                    startAction = { PrefIcon(Icons.Rounded.SwapHoriz) },
                                    // 传 null 触发无 summary 布局（这个下拉条目短，留副标题会空一行）
                                    summary = null,
                                )
                                NumSliderPreference(
                                    onInputChange = onInputChange,
                                    title = stringResource(R.string.pref_dim_strength),
                                    summary = stringResource(R.string.pref_dim_strength_summary),
                                    startAction = { PrefIcon(Icons.Rounded.BrightnessLow) },
                                    value = dimStrength,
                                    onValueChange = { dimStrength = it },
                                    defaultValue = 0.57f,
                                    valueRange = 0.05f..1f,
                                    scale = 100f,
                                    unit = "%",
                                )
                            }
                        }
                        // 传统描边：宽度 / 透明度 / 颜色，压暗时这三个都没意义
                        AnimatedVisibility(visible = arcEnabled && !arcDim) {
                            Column {
                                NumSliderPreference(
                                    onInputChange = onInputChange,
                                    title = stringResource(R.string.pref_arc_width),
                                    summary = stringResource(R.string.pref_arc_width_summary),
                                    startAction = { PrefIcon(Icons.Rounded.Tune) },
                                    value = arcWidth,
                                    onValueChange = { arcWidth = it },
                                    defaultValue = 3f,
                                    valueRange = 1f..10f,
                                    unit = " dp",
                                )
                                NumSliderPreference(
                                    onInputChange = onInputChange,
                                    title = stringResource(R.string.pref_arc_alpha),
                                    summary = stringResource(R.string.pref_arc_alpha_summary),
                                    startAction = { PrefIcon(Icons.Rounded.Wallpaper) },
                                    value = arcAlpha,
                                    onValueChange = { arcAlpha = it },
                                    defaultValue = 1f,
                                    valueRange = 0.1f..1f,
                                    scale = 100f,
                                    unit = "%",
                                )
                                ArrowPreference(
                                    title = stringResource(R.string.pref_arc_color),
                                    summary = stringResource(R.string.pref_arc_color_summary) + " · " + hexOf(arcColorValue),
                                    startAction = { PrefIcon(Icons.Rounded.ColorLens) },
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
                    }
                }
            }
        }

        // 模糊自己一组：上滑时只把弧线以下露出的壁纸（盖层按同源弧线裁到分界）挂高斯，
        // 弧线以上透出真实壁纸，景深与时钟通知一律清晰。
        // 只有开关 + 一条程度滑条：强度随上滑进度连续渐强，不搞分级阶梯。
        item {
            AnimatedVisibility(visible = enabled) {
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    GroupCard(title = stringResource(R.string.group_blur)) {
                        SwitchPreference(
                            title = stringResource(R.string.pref_blur_on),
                            summary = stringResource(R.string.pref_blur_on_summary),
                            startAction = { PrefIcon(Icons.Rounded.BlurOn) },
                            checked = blurOn,
                            onCheckedChange = { blurOn = it },
                        )
                        // 程度归开关管：关掉时滑条跟着收起，同样是动画不是灰掉
                        AnimatedVisibility(visible = blurOn) {
                            NumSliderPreference(
                                onInputChange = onInputChange,
                                title = stringResource(R.string.pref_blur_strength),
                                summary = stringResource(R.string.pref_blur_strength_summary),
                                startAction = { PrefIcon(Icons.Rounded.BlurCircular) },
                                value = blurStrength,
                                onValueChange = { blurStrength = it },
                                defaultValue = 0.7f,
                                // 最小 1%：0 与「开关关掉」等效，滑条不再给这一档（副文本同步删掉该说明）
                                valueRange = 0.01f..1f,
                                scale = 100f,
                                unit = "%",
                            )
                        }
                    }
                }
            }
        }

        // 玻璃自己一组：不看「启用弧形描边」的脸色，只看总开关。
        // xposed 侧对应地把 clearClips() 拆成 clearArcClips() —— 关掉描边只收裁切与
        // 描边壳，strip（玻璃条）照常跟手，显隐由下面这个 glassLive 自己说了算。
        item {
            AnimatedVisibility(visible = enabled) {
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    GroupCard(title = stringResource(R.string.group_glass)) {
                        SwitchPreference(
                            title = stringResource(R.string.pref_glass_live),
                            summary = stringResource(R.string.pref_glass_live_summary),
                            startAction = { PrefIcon(Icons.Rounded.AutoAwesome) },
                            checked = glassLive,
                            onCheckedChange = { glassLive = it },
                        )
                        // 玻璃的十个实时材质旋钮单独成页（漫反射 / 模糊 / 折射 / 菲涅尔 / 高光 /
                        // 不透明度 / 厚度）。这一组放不下十条，硬塞进来会把这组撑爆。
                        // 玻璃本身关掉时这个入口跟着收起 —— 同一页的开关牵连自己这一条，
                        // 照样是 AnimatedVisibility 而不是灰掉（灰掉等于摆个点不动的死件）。
                        AnimatedVisibility(visible = glassLive) {
                            ArrowPreference(
                                title = stringResource(R.string.pref_glass_page),
                                summary = stringResource(R.string.pref_glass_page_summary),
                                startAction = { PrefIcon(Icons.Rounded.Layers) },
                                onClick = { onOpenSub("glass") },
                            )
                        }
                    }
                }
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
