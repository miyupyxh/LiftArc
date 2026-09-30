package com.lockbar.app.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 主题配置的持久化。
 *
 * 只影响模块 App 自己的界面，**不下发**给 SystemUI（跟 [com.lockbar.app.Prefs] 那份配置分开，
 * 免得每改一次主题就跨进程写一次 LSPosed 远端配置）。
 */
class ThemeStore(context: Context) {

    private val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 0 = 跟随系统，1 = 浅色，2 = 深色。 */
    var mode by mutableStateOf(sp.getInt(KEY_MODE, MODE_SYSTEM))
        private set

    /** 动态取色（Monet）。 */
    var dynamic by mutableStateOf(sp.getBoolean(KEY_DYNAMIC, true))
        private set

    /** 关键色 ARGB，0 = 用系统壁纸取色。 */
    var keyColor by mutableStateOf(sp.getInt(KEY_KEY_COLOR, 0))
        private set

    /** [ThemePaletteStyle] 的下标。 */
    var palette by mutableStateOf(sp.getInt(KEY_PALETTE, 0))
        private set

    /** 0 = 2021 规范，1 = 2025 规范。 */
    var spec by mutableStateOf(sp.getInt(KEY_SPEC, 0))
        private set

    /** 模糊效果（玻璃底栏的总开关）。 */
    var blur by mutableStateOf(sp.getBoolean(KEY_BLUR, true))
        private set

    /** 浮动（悬浮）底栏；关掉就是贴底的整条导航栏。 */
    var floatingBar by mutableStateOf(sp.getBoolean(KEY_FLOATING, true))
        private set

    /** 玻璃效果（浮动底栏是否真磨砂）。 */
    var glass by mutableStateOf(sp.getBoolean(KEY_GLASS, true))
        private set

    fun updateMode(value: Int) {
        mode = value
        sp.edit().putInt(KEY_MODE, value).apply()
    }

    fun updateDynamic(value: Boolean) {
        dynamic = value
        sp.edit().putBoolean(KEY_DYNAMIC, value).apply()
    }

    fun updateKeyColor(value: Int) {
        keyColor = value
        sp.edit().putInt(KEY_KEY_COLOR, value).apply()
    }

    fun updatePalette(value: Int) {
        palette = value
        sp.edit().putInt(KEY_PALETTE, value).apply()
    }

    fun updateSpec(value: Int) {
        spec = value
        sp.edit().putInt(KEY_SPEC, value).apply()
    }

    fun updateBlur(value: Boolean) {
        blur = value
        sp.edit().putBoolean(KEY_BLUR, value).apply()
    }

    fun updateFloatingBar(value: Boolean) {
        floatingBar = value
        sp.edit().putBoolean(KEY_FLOATING, value).apply()
    }

    fun updateGlass(value: Boolean) {
        glass = value
        sp.edit().putBoolean(KEY_GLASS, value).apply()
    }

    companion object {
        private const val NAME = "lockbar_theme"

        const val MODE_SYSTEM = 0
        const val MODE_LIGHT = 1
        const val MODE_DARK = 2

        private const val KEY_MODE = "theme_mode"
        private const val KEY_DYNAMIC = "theme_dynamic"
        private const val KEY_KEY_COLOR = "theme_key_color"
        private const val KEY_PALETTE = "theme_palette"
        private const val KEY_SPEC = "theme_spec"
        private const val KEY_BLUR = "theme_blur"
        private const val KEY_FLOATING = "theme_floating"
        private const val KEY_GLASS = "theme_glass"
    }
}

/** 当前主题配置。 */
val LocalThemeStore = staticCompositionLocalOf<ThemeStore> { error("ThemeStore 未提供") }

/** 界面实际生效的深浅色（把「跟随系统」折算成具体结果），给需要写死配色的组件用。 */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
@ReadOnlyComposable
fun isDarkTheme(): Boolean = LocalDarkTheme.current

/** 动态取色的关键色候选值，0 表示“跟随壁纸 / 系统”。顺序与 [KeyColorNames] 一致。 */
val KeyColorValues: List<Int> = listOf(
    0,
    0xFFF44336.toInt(),
    0xFFE91E63.toInt(),
    0xFF9C27B0.toInt(),
    0xFF673AB7.toInt(),
    0xFF3F51B5.toInt(),
    0xFF2196F3.toInt(),
    0xFF00BCD4.toInt(),
    0xFF009688.toInt(),
    0xFF4CAF50.toInt(),
    0xFFFFEB3B.toInt(),
    0xFFFFC107.toInt(),
    0xFFFF9800.toInt(),
    0xFF795548.toInt(),
    0xFF607D8B.toInt(),
    0xFFFFB6C1.toInt(),
)

/** 关键色的中文名，跟 [KeyColorValues] 一一对应。 */
val KeyColorNames: List<String> = listOf(
    "跟随壁纸",
    "绯红",
    "樱粉",
    "紫罗兰",
    "深紫",
    "靛蓝",
    "天蓝",
    "青色",
    "碧绿",
    "草绿",
    "柠檬黄",
    "琥珀",
    "橙色",
    "棕褐",
    "蓝灰",
    "樱花粉",
)

/** 色彩风格（Material palette style）的中文名，顺序与 [ThemePaletteStyle] 一致。 */
val PaletteStyleNames: List<String> = listOf(
    "柔和 TonalSpot",
    "中性 Neutral",
    "鲜艳 Vibrant",
    "表现 Expressive",
    "彩虹 Rainbow",
    "果拼 FruitSalad",
    "单色 Monochrome",
    "保真 Fidelity",
    "取图 Content",
)

/**
 * App 主题：把 [ThemeStore] 里存的主题设置接到 MiuiX 的 [ThemeController] 上，
 * 设置页改一项这里立刻重建配色（主题设置内容必须生效）。
 */
@Composable
fun LockBarTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ThemeStore(context.applicationContext) }

    val systemDark = isSystemInDarkTheme()
    val dark = when (store.mode) {
        ThemeStore.MODE_LIGHT -> false
        ThemeStore.MODE_DARK -> true
        else -> systemDark
    }

    val controller = remember(
        store.mode, store.dynamic, store.keyColor, store.palette, store.spec, dark,
    ) {
        ThemeController(
            colorSchemeMode = when {
                !store.dynamic -> if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light
                store.mode == ThemeStore.MODE_SYSTEM -> ColorSchemeMode.MonetSystem
                dark -> ColorSchemeMode.MonetDark
                else -> ColorSchemeMode.MonetLight
            },
            keyColor = store.keyColor.takeIf { it != 0 }?.let { Color(it) },
            colorSpec = if (store.spec == 1) ThemeColorSpec.Spec2025 else ThemeColorSpec.Spec2021,
            paletteStyle = ThemePaletteStyle.entries.getOrElse(store.palette) { ThemePaletteStyle.TonalSpot },
            isDark = dark,
        )
    }

    CompositionLocalProvider(
        LocalThemeStore provides store,
        LocalDarkTheme provides dark,
    ) {
        MiuixTheme(controller = controller, content = content)
    }
}
