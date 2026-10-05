package com.lockbar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurCircular
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Texture
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lockbar.app.Prefs
import com.lockbar.app.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 玻璃参数页（1.0.22）：上滑玻璃的实时材质参数，共十条。
 *
 * 这些值走远端配置 → [com.lockbar.app.xposed.LockConfig] →
 * `LockGlassStrip.setGlassTuning`，**改完立刻生效**，不用重启 SystemUI。
 *
 * 三条必须守住的事实，改这个文件前先读：
 *
 * 1. **默认值必须与 `LockGlassStrip.K_*` 逐字相同**。K_* 是 Java 的包内常量，
 *    跨包拿不到，所以这里只能写字面量 —— 一旦两边对不上，表现是「界面显示 0.13、
 *    实际在跑 0.16」，不崩溃、不报错，`_verify.ps1` 里有断言兜底。
 * 2. **滑条上限就是安全线**：折射 0.90 与边缘折射 0.50 是成对的（见 [noteText]），
 *    不能为了让「更明显」而放宽，放宽会把背景对折出重复纹理。
 * 3. 玻璃总开关「液态玻璃实时渲染弧形描边」在 参数 → 弧形描边，**不在本页** ——
 *    这里不放开关是为了避免同一个键被两处 `remember` 各记一份、切回来时显示过期。
 */
@Composable
internal fun GlassScreen(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()

    // 只读：用来判断本页是否该灰掉。开关本身在 参数 → 弧形描边。
    var enabled by rememberPrefBool(Prefs.KEY_ENABLED, true)
    var glassLive by rememberPrefBool(Prefs.KEY_GLASS_LIVE, false)

    // ---------------------------------------------------------- 玻璃材质（4）
    var glassThick by rememberPrefFloat(Prefs.KEY_GLASS_THICK, 20f)
    var glassOpacity by rememberPrefFloat(Prefs.KEY_GLASS_OPACITY, 0.96f)
    var glassDiffuse by rememberPrefFloat(Prefs.KEY_GLASS_DIFFUSE, 0.05f)
    var glassBlur by rememberPrefFloat(Prefs.KEY_GLASS_BLUR, 1f)
    // ---------------------------------------------------------- 折射（4）
    var glassRefract by rememberPrefFloat(Prefs.KEY_GLASS_REFRACT, 0.85f)
    var glassEdgeRef by rememberPrefFloat(Prefs.KEY_GLASS_EDGE_REF, 0.40f)
    var glassRefractW by rememberPrefFloat(Prefs.KEY_GLASS_REFRACT_W, 0.45f)
    var glassFresnel by rememberPrefFloat(Prefs.KEY_GLASS_FRESNEL, 0.22f)
    // ---------------------------------------------------------- 高光（2）
    var glassHighlight by rememberPrefFloat(Prefs.KEY_GLASS_HIGHLIGHT, 0.46f)
    var glassHlWidth by rememberPrefFloat(Prefs.KEY_GLASS_HL_WIDTH, 0.85f)

    val live = enabled && glassLive

    // 十条一起回到默认值：与 LockGlassStrip.K_* 逐字一致（见类注释第 1 条）
    val reset: () -> Unit = {
        glassThick = 20f
        glassOpacity = 0.96f
        glassDiffuse = 0.05f
        glassBlur = 1f
        glassRefract = 0.85f
        glassEdgeRef = 0.40f
        glassRefractW = 0.45f
        glassFresnel = 0.22f
        glassHighlight = 0.46f
        glassHlWidth = 0.85f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.pref_glass_page),
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
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!live) {
                item {
                    Text(
                        text = stringResource(R.string.pref_glass_page_off),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }

            item {
                GroupCard(title = stringResource(R.string.group_glass_mat)) {
                    NumSliderPreference(
                        title = stringResource(R.string.pref_glass_thick),
                        summary = stringResource(R.string.pref_glass_thick_summary),
                        startAction = { PrefIcon(Icons.Rounded.AutoAwesome) },
                        value = glassThick,
                        onValueChange = { glassThick = it },
                        defaultValue = 20f,
                        // 1..40dp，与描边宽度完全无关（「跟随描边」档已移除）
                        valueRange = 1f..40f,
                        unit = " dp",
                        enabled = live,
                    )
                    NumSliderPreference(
                        title = stringResource(R.string.pref_glass_opacity),
                        summary = stringResource(R.string.pref_glass_opacity_summary),
                        startAction = { PrefIcon(Icons.Rounded.Opacity) },
                        value = glassOpacity,
                        onValueChange = { glassOpacity = it },
                        defaultValue = 0.96f,
                        // 下限 0.20：拉到 0 等于关掉玻璃，那是开关的活，不是滑条的活
                        valueRange = 0.20f..1f,
                        scale = 100f,
                        unit = "%",
                        enabled = live,
                    )
                    NumSliderPreference(
                        title = stringResource(R.string.pref_glass_diffuse),
                        summary = stringResource(R.string.pref_glass_diffuse_summary),
                        startAction = { PrefIcon(Icons.Rounded.LightMode) },
                        value = glassDiffuse,
                        onValueChange = { glassDiffuse = it },
                        defaultValue = 0.05f,
                        valueRange = 0f..1f,
                        scale = 100f,
                        unit = "%",
                        enabled = live,
                    )
                    NumSliderPreference(
                        title = stringResource(R.string.pref_glass_blur),
                        summary = stringResource(R.string.pref_glass_blur_summary),
                        startAction = { PrefIcon(Icons.Rounded.BlurOn) },
                        value = glassBlur,
                        onValueChange = { glassBlur = it },
                        defaultValue = 1f,
                        valueRange = 0f..20f,
                        unit = " px",
                        enabled = live,
                    )
                }
            }

            item {
                GroupCard(title = stringResource(R.string.group_glass_refract)) {
                    NumSliderPreference(
                        title = stringResource(R.string.pref_glass_refract),
                        summary = stringResource(R.string.pref_glass_refract_summary),
                        startAction = { PrefIcon(Icons.Rounded.Texture) },
                        value = glassRefract,
                        onValueChange = { glassRefract = it },
                        defaultValue = 0.85f,
                        // 上限 0.90 是安全线，和边缘折射的 0.50 成对，见 pref_glass_note
                        valueRange = 0f..0.90f,
                        scale = 100f,
                        unit = "%",
                        enabled = live,
                    )
                    NumSliderPreference(
                        title = stringResource(R.string.pref_glass_edge_ref),
                        summary = stringResource(R.string.pref_glass_edge_ref_summary),
                        startAction = { PrefIcon(Icons.Rounded.Tune) },
                        value = glassEdgeRef,
                        onValueChange = { glassEdgeRef = it },
                        defaultValue = 0.40f,
                        valueRange = 0f..0.50f,
                        scale = 100f,
                        unit = "%",
                        enabled = live,
                    )
                    NumSliderPreference(
                        title = stringResource(R.string.pref_glass_refract_w),
                        summary = stringResource(R.string.pref_glass_refract_w_summary),
                        startAction = { PrefIcon(Icons.Rounded.BlurCircular) },
                        value = glassRefractW,
                        onValueChange = { glassRefractW = it },
                        defaultValue = 0.45f,
                        valueRange = 0f..1f,
                        scale = 100f,
                        unit = "%",
                        enabled = live,
                    )
                    NumSliderPreference(
                        title = stringResource(R.string.pref_glass_fresnel),
                        summary = stringResource(R.string.pref_glass_fresnel_summary),
                        startAction = { PrefIcon(Icons.Rounded.WaterDrop) },
                        value = glassFresnel,
                        onValueChange = { glassFresnel = it },
                        defaultValue = 0.22f,
                        valueRange = 0f..1f,
                        scale = 100f,
                        unit = "%",
                        enabled = live,
                    )
                }
            }

            item {
                GroupCard(title = stringResource(R.string.group_glass_light)) {
                    NumSliderPreference(
                        title = stringResource(R.string.pref_glass_highlight),
                        summary = stringResource(R.string.pref_glass_highlight_summary),
                        startAction = { PrefIcon(Icons.Rounded.BrightnessLow) },
                        value = glassHighlight,
                        onValueChange = { glassHighlight = it },
                        defaultValue = 0.46f,
                        valueRange = 0f..1f,
                        scale = 100f,
                        unit = "%",
                        enabled = live,
                    )
                    NumSliderPreference(
                        title = stringResource(R.string.pref_glass_hl_width),
                        summary = stringResource(R.string.pref_glass_hl_width_summary),
                        startAction = { PrefIcon(Icons.Rounded.Adjust) },
                        value = glassHlWidth,
                        onValueChange = { glassHlWidth = it },
                        defaultValue = 0.85f,
                        valueRange = 0.30f..1.50f,
                        // 唯一一条不带单位、且要两位小数的：倍率没有量纲
                        digits = 2,
                        enabled = live,
                    )
                }
            }

            item {
                GroupCard(title = stringResource(R.string.group_glass_reset)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Button(onClick = reset) {
                            Text(stringResource(R.string.pref_glass_reset))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.pref_glass_note),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.pref_glass_const),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}
