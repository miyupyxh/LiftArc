package com.lockbar.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockbar.app.R
import com.lockbar.app.ui.theme.KeyColorNames
import com.lockbar.app.ui.theme.KeyColorValues
import com.lockbar.app.ui.theme.LocalThemeStore
import com.lockbar.app.ui.theme.PaletteStyleNames
import com.lockbar.app.ui.theme.ThemeStore
import com.lockbar.app.ui.theme.isDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主题设置页：UI 布局搬自 KernelSU 的主题设置（预览卡 + 三项 Tab + 取色卡 + 底栏卡），
 * 名称换成本模块的文案，所有开关都接到 [ThemeStore]，改了立刻生效。
 */
@Composable
internal fun ThemeSettingsScreen(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val store = LocalThemeStore.current
    val colorScheme = MiuixTheme.colorScheme

    val specNames = listOf(
        stringResource(R.string.theme_spec_2021),
        stringResource(R.string.theme_spec_2025),
    )
    val keyColorIndex = KeyColorValues.indexOf(store.keyColor).coerceAtLeast(0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_theme),
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
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ThemePreviewCard() }

            item {
                TabRow(
                    tabs = listOf(
                        stringResource(R.string.theme_mode_system),
                        stringResource(R.string.theme_mode_light),
                        stringResource(R.string.theme_mode_dark),
                    ),
                    selectedTabIndex = store.mode.coerceIn(0, 2),
                    onTabSelected = { store.updateMode(it) },
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = stringResource(R.string.theme_dynamic),
                        summary = stringResource(R.string.theme_dynamic_summary),
                        startAction = { PrefIcon(Icons.Rounded.Wallpaper) },
                        checked = store.dynamic,
                        onCheckedChange = { store.updateDynamic(it) },
                    )
                    AnimatedVisibility(visible = store.dynamic) {
                        Column {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.theme_key_color),
                                startAction = { PrefIcon(Icons.Rounded.Colorize) },
                                items = KeyColorNames,
                                selectedIndex = keyColorIndex,
                                onSelectedIndexChange = { store.updateKeyColor(KeyColorValues[it]) },
                            )
                            AnimatedVisibility(visible = store.keyColor != 0) {
                                Column {
                                    OverlayDropdownPreference(
                                        title = stringResource(R.string.theme_color_style),
                                        startAction = { PrefIcon(Icons.Rounded.Style) },
                                        items = PaletteStyleNames,
                                        selectedIndex = store.palette.coerceIn(0, PaletteStyleNames.lastIndex),
                                        onSelectedIndexChange = { store.updatePalette(it) },
                                    )
                                    OverlayDropdownPreference(
                                        title = stringResource(R.string.theme_color_spec),
                                        startAction = { PrefIcon(Icons.Rounded.DesignServices) },
                                        items = specNames,
                                        selectedIndex = store.spec.coerceIn(0, 1),
                                        onSelectedIndexChange = { store.updateSpec(it) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = stringResource(R.string.theme_blur),
                        summary = stringResource(R.string.theme_blur_summary),
                        startAction = { PrefIcon(Icons.Rounded.BlurOn) },
                        checked = store.blur,
                        onCheckedChange = { store.updateBlur(it) },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.theme_floating),
                        summary = stringResource(R.string.theme_floating_summary),
                        startAction = { PrefIcon(Icons.Rounded.CallToAction) },
                        checked = store.floatingBar,
                        onCheckedChange = { store.updateFloatingBar(it) },
                    )
                    AnimatedVisibility(visible = store.floatingBar) {
                        SwitchPreference(
                            title = stringResource(R.string.theme_glass),
                            summary = stringResource(R.string.theme_glass_summary),
                            startAction = { PrefIcon(Icons.Rounded.WaterDrop) },
                            enabled = store.blur,
                            checked = store.glass,
                            onCheckedChange = { store.updateGlass(it) },
                        )
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.settings_theme_summary),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/**
 * 顶部的迷你界面预览卡：直接用当前生效的配色画一张“缩小版首页”，
 * 主题一改这里立刻跟着变。
 */
@Composable
private fun ThemePreviewCard() {
    val store = LocalThemeStore.current
    val colorScheme = MiuixTheme.colorScheme
    val dark = isDarkTheme()

    val bg = colorScheme.background
    val onBg = colorScheme.onBackground
    val cardColor = colorScheme.surfaceContainerHighest
    val accentCard = when {
        dark -> Color(0xFF1A3825)
        else -> Color(0xFFDFFAE4)
    }
    val accentText = if (dark) Color(0xFFE8FFF1) else Color(0xFF10291A)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.58f)
                .aspectRatio(0.62f)
                .clip(RoundedCornerShape(22.dp))
                .background(bg)
                .border(1.dp, colorScheme.outline, RoundedCornerShape(22.dp)),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = onBg,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                )
                Spacer(Modifier.height(8.dp))

                // 迷你状态卡（对应首页的大绿框）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentCard),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(26.dp)
                            .padding(end = 5.dp, bottom = 5.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (dark) Color(0xFF36D167) else Color(0xFF36D167).copy(alpha = 0.9f)),
                    )
                    Text(
                        text = stringResource(R.string.status_activated),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentText,
                        modifier = Modifier.padding(start = 8.dp, top = 7.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardColor),
                )
                Spacer(Modifier.height(8.dp))

                // 杩蜂綘搴曟爮
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (store.floatingBar) 24.dp else 30.dp)
                        .clip(RoundedCornerShape(if (store.floatingBar) 12.dp else 8.dp))
                        .background(colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(colorScheme.primary.copy(alpha = 0.25f)),
                    )
                }
            }
        }
    }
}

