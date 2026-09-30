package com.lockbar.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockbar.app.LockBarApp
import com.lockbar.app.Prefs
import com.lockbar.app.R
import com.lockbar.app.SystemUiRestarter
import com.lockbar.app.ui.component.BottomTab
import com.lockbar.app.ui.component.LockBottomBar
import com.lockbar.app.ui.theme.LocalThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 描边取色器里的预设颜色，同时写进 [Prefs.KEY_ARC_COLOR]。 */
private val ArcColors = intArrayOf(
    0xFFFFFFFF.toInt(),
    0xFF3482FF.toInt(),
    0xFF4FD8C8.toInt(),
    0xFFF5C451.toInt(),
    0xFFB78CFF.toInt(),
)

/** 小横条文字的字体索引 → 预览用的 Compose 字体族。 */
private fun previewFontFamily(index: Int): FontFamily = when (index) {
    1 -> FontFamily.Serif
    2 -> FontFamily.Monospace
    3 -> FontFamily.Cursive
    // 4 = 窄体（sans-serif-condensed），Compose 没有对应项，预览只能用普通无衬线近似
    else -> FontFamily.SansSerif
}

/** 二级页面的路由 key。 */
private const val ROUTE_HINT = "hint"
private const val ROUTE_THEME = "theme"
private const val ROUTE_ABOUT = "about"
private const val ROUTE_LOG = "log"

/** 二级页面的路由 key（KernelSU 同款：交给 miuix-nav 的 [NavDisplay] 渲染）。 */
private sealed interface Route : NavKey {
    data object Main : Route
    data object Hint : Route
    data object Theme : Route
    data object About : Route
    data object Credits : Route
    data object Log : Route
}

/**
 * 模块设置主界面：首页 / 参数 / 设置 三个标签 + 二级页面。
 *
 * 转场动画**照搬 KernelSU**：`top.yukonga.miuix.kmp.nav` 的 [NavDisplay]（MiuixDefault）——
 * 新页面从右侧滑入、旧页面同速左移并压暗，入口处按屏幕圆角裁切，松手/返回用同一条 spring 收合，
 * 系统返回与侧滑返回由 NavDisplay 自己接管（`PredictiveBackHandlerWithSessions`），
 * 所以这里**不再挂 BackHandler**，只有退栈到只剩首页时才交给系统退出。
 */
@Composable
fun LockBarScreen() {
    val backStack = remember { navBackStackOf(Route.Main) }
    // 标签提到最外层：进二级页时 MainShell 被压在下层，留在里面返回就丢标签了
    var tab by rememberSaveable { mutableIntStateOf(0) }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        effects = NavDisplayEffects(
            cornerClipRadius = rememberNavSystemCornerRadius(),
            backdropColor = MiuixTheme.colorScheme.background,
        ),
        content = {
            entry<Route.Main> {
                MainShell(
                    tab = tab,
                    onTabChange = { tab = it },
                    onOpenSub = { name ->
                        when (name) {
                            ROUTE_HINT -> backStack.add(Route.Hint)
                            ROUTE_THEME -> backStack.add(Route.Theme)
                            ROUTE_ABOUT -> backStack.add(Route.About)
                            ROUTE_LOG -> backStack.add(Route.Log)
                        }
                    },
                )
            }
            entry<Route.Hint> { HintTextScreen(onBack = { backStack.removeLastOrNull() }) }
            entry<Route.Theme> { ThemeSettingsScreen(onBack = { backStack.removeLastOrNull() }) }
            // 关于页 → 引用与致谢：三层页，push 进去返回键/侧滑由 NavDisplay 自己接管
            entry<Route.About> {
                AboutScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onOpenCredits = { backStack.add(Route.Credits) },
                )
            }
            entry<Route.Credits> { CreditsScreen(onBack = { backStack.removeLastOrNull() }) }
            // 模块日志：排查“不生效”用，装完直接发截图/复制文本给测试者
            entry<Route.Log> { LogScreen(onBack = { backStack.removeLastOrNull() }) }
        },
    )
}

// ----------------------------------------------------------------------- 主框架

/** 重启 SystemUI 的过程状态。 */
private sealed interface RestartStep {
    data object Running : RestartStep
    data object Done : RestartStep
    data class Failed(val message: String) : RestartStep
}

@Composable
private fun MainShell(
    tab: Int,
    onTabChange: (Int) -> Unit,
    onOpenSub: (String) -> Unit,
) {
    val themeStore = LocalThemeStore.current
    val density = LocalDensity.current
    val scrollBehavior = MiuixScrollBehavior()
    val restartScope = rememberCoroutineScope()

    var barHeightPx by remember { mutableIntStateOf(0) }
    var restart by remember { mutableStateOf<RestartStep?>(null) }
    /** 参数页取色器是否在显示；同样要让悬浮底栏退场。 */
    var arcColorDialog by remember { mutableStateOf(false) }

    // 只有浮动 + 玻璃 + 模糊都开着才录制背景层，否则底栏直接画纯色
    val glassActive = themeStore.floatingBar && themeStore.glass && themeStore.blur
    val backdrop: LayerBackdrop = rememberLayerBackdrop()

    val tabs = listOf(
        BottomTab(stringResource(R.string.tab_home), Icons.Rounded.Home),
        BottomTab(stringResource(R.string.tab_params), Icons.Rounded.Tune),
        BottomTab(stringResource(R.string.tab_settings), Icons.Rounded.Settings),
    )
    val title = when (tab) {
        0 -> stringResource(R.string.app_name)
        1 -> stringResource(R.string.tab_params)
        else -> stringResource(R.string.tab_settings)
    }

    val bottomBarHeight: Dp = with(density) { barHeightPx.toDp() }

    fun startRestart() {
        restart = RestartStep.Running
        restartScope.launch(Dispatchers.IO) {
            val err = try {
                SystemUiRestarter.restart()
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            withContext(Dispatchers.Main) {
                restart = if (err == null) RestartStep.Done else RestartStep.Failed(err)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (glassActive) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = title,
                        scrollBehavior = scrollBehavior,
                        actions = {
                            IconButton(onClick = { startRestart() }) {
                                Icon(
                                    imageVector = Icons.Rounded.RestartAlt,
                                    contentDescription = stringResource(R.string.action_restart_systemui),
                                )
                            }
                        },
                    )
                },
                contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
            ) { innerPadding ->
                // KernelSU 是 HorizontalPager + pagerState.springAnimateToPage：拖完底栏松手时整页
                // 用 spring 滑过去。这里用 AnimatedContent 等价还原（不动结构，只补上那一下滑动），
                // 新页从右侧滑入、旧页同速向左滑出。
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally { it * dir } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it * dir } + fadeOut())
                    },
                    label = "tab",
                ) { current ->
                    when (current) {
                        0 -> HomePage(
                            innerPadding = innerPadding,
                            bottomBarHeight = bottomBarHeight,
                            scrollBehavior = scrollBehavior,
                        )

                        1 -> ParamsPage(
                            innerPadding = innerPadding,
                            bottomBarHeight = bottomBarHeight,
                            scrollBehavior = scrollBehavior,
                            onOpenSub = onOpenSub,
                            onModalChange = { arcColorDialog = it },
                        )

                        else -> SettingsPage(
                            innerPadding = innerPadding,
                            bottomBarHeight = bottomBarHeight,
                            scrollBehavior = scrollBehavior,
                            onOpenSub = onOpenSub,
                        )
                    }
                }

                // MiuiX 的弹窗宿主由 Scaffold 提供（LocalRootDialogStates），必须放在 Scaffold 里面
                RestartDialog(step = restart, onDismiss = { restart = null })
            }
        }

        // 弹窗是模态的。底栏画在外层 Box 的最后，z 序压住弹窗，而且它不在 Scaffold 里、
        // 拿不到弹窗的整屏遮罩（该暗的地方它不暗），所以弹窗期间直接让它退场。
        // 三个模态出口：重启弹窗、参数页取色器（文字页取色器所在页面本就没有底栏）。
        // onGloballyPositioned 在节点移出组合时不会再回调，barHeightPx 保留上次的值，
        // 内容区留白不会塌；下面的 >0 只是再兜一层底。
        if (restart == null && !arcColorDialog) {
            LockBottomBar(
                tabs = tabs,
                selectedIndex = tab,
                onSelect = onTabChange,
                floating = themeStore.floatingBar,
                glass = themeStore.glass && themeStore.blur,
                backdrop = backdrop,
                onHeightChanged = { height -> if (height > 0) barHeightPx = height },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 重启 SystemUI 的类 MiuiX 提示弹窗。 */
@Composable
private fun RestartDialog(step: RestartStep?, onDismiss: () -> Unit) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    OverlayDialog(
        show = step != null,
        title = stringResource(R.string.action_restart_systemui),
        onDismissRequest = { if (step !is RestartStep.Running) onDismiss() },
        // 手机上 OverlayDialog 默认「贴底滑出」（DialogContentLayout 里
        // `if (isLargeScreen) Center else BottomCenter`），位置正好和悬浮底栏重合。
        // 强制走居中的方框形态：避开底栏，圆角也换成 DialogDefaults.CornerRadius。
        largeScreen = true,
    ) {
        when (step) {
            null -> Unit

            RestartStep.Running -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(size = 44.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.action_restart_started),
                    style = textStyles.subtitle,
                    color = colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }

            RestartStep.Done -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF36D167),
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.action_restart_done),
                    style = textStyles.subtitle,
                    color = colorScheme.onBackground,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
                }
            }

            is RestartStep.Failed -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = colorScheme.error,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.action_restart_failed),
                    style = textStyles.title4,
                    color = colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = step.message,
                    style = textStyles.footnote2,
                    color = colorScheme.onBackground.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- 小横条文字设置页

/** 小横条上方文字的独立设置页：内容 / 预览 / 样式 / 位置。 */
@Composable
private fun HintTextScreen(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    var hintText by rememberPrefString(Prefs.KEY_HINT_TEXT, "")
    var hintSize by rememberPrefFloat(Prefs.KEY_HINT_SIZE, 12f)
    var hintWeight by rememberPrefInt(Prefs.KEY_HINT_WEIGHT, 400)
    var hintFont by rememberPrefInt(Prefs.KEY_HINT_FONT, 0)
    var hintColorValue by rememberPrefInt(Prefs.KEY_HINT_COLOR, 0xD9FFFFFF.toInt())
    var hintGap by rememberPrefFloat(Prefs.KEY_HINT_GAP, 10f)
    var hintOffsetX by rememberPrefFloat(Prefs.KEY_HINT_OFFSET_X, 0f)

    var showHintColor by remember { mutableStateOf(false) }
    var pendingHintColor by remember { mutableStateOf(hintColorValue) }
    val commitHintColor: () -> Unit = {
        showHintColor = false
        if (pendingHintColor != hintColorValue) {
            hintColorValue = pendingHintColor
        }
    }

    // 只初始化一次：用 remember(hintText) 会在每次按键后重建 TextFieldValue，光标被弹回开头
    var field by remember {
        mutableStateOf(
            TextFieldValue(text = hintText, selection = TextRange(hintText.length))
        )
    }

    val fontNames = listOf(
        stringResource(R.string.font_default),
        stringResource(R.string.font_serif),
        stringResource(R.string.font_mono),
        stringResource(R.string.font_cursive),
        stringResource(R.string.font_condensed),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.hint_title),
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
            item {
                GroupCard(title = stringResource(R.string.hint_group_preview)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .background(colorScheme.secondaryContainer),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(bottom = 26.dp),
                        ) {
                            if (hintText.isNotBlank()) {
                                Text(
                                    text = hintText,
                                    color = Color(hintColorValue),
                                    fontSize = hintSize.sp,
                                    fontWeight = FontWeight(hintWeight.coerceIn(100, 900)),
                                    fontFamily = previewFontFamily(hintFont),
                                    maxLines = 1,
                                    modifier = Modifier
                                        .offset(x = hintOffsetX.dp)
                                        .padding(bottom = hintGap.dp),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(128.dp)
                                    .height(4.dp)
                                    .background(Color(0xCCFFFFFF), CircleShape),
                            )
                        }
                    }
                }
            }

            item {
                GroupCard(title = stringResource(R.string.hint_group_content)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.TextFields,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                                tint = colorScheme.onBackground,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.pref_hint_text),
                                    style = textStyles.title4,
                                    color = colorScheme.onBackground,
                                )
                                Text(
                                    text = stringResource(R.string.pref_hint_text_summary),
                                    style = textStyles.footnote1,
                                    color = colorScheme.onBackground.copy(alpha = 0.6f),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = field,
                            onValueChange = {
                                field = it
                                hintText = it.text
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.pref_hint_text_label),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            textStyle = textStyles.main.copy(color = colorScheme.onBackground),
                        )
                    }
                }
            }

            item {
                GroupCard(title = stringResource(R.string.hint_group_style)) {
                    SliderPreference(
                        title = stringResource(R.string.pref_hint_size),
                        summary = stringResource(R.string.pref_hint_size_summary),
                        startAction = { PrefIcon(Icons.Rounded.Tune) },
                        value = hintSize,
                        onValueChange = { hintSize = it.coerceIn(6f, 40f) },
                        valueText = "${hintSize.toInt()} sp",
                        valueRange = 6f..40f,
                        steps = 33,
                    )
                    SliderPreference(
                        title = stringResource(R.string.pref_hint_weight),
                        summary = stringResource(R.string.pref_hint_weight_summary),
                        startAction = { PrefIcon(Icons.Rounded.Tune) },
                        value = hintWeight.toFloat(),
                        onValueChange = { hintWeight = it.toInt().coerceIn(100, 900) },
                        valueText = "$hintWeight",
                        valueRange = 100f..900f,
                        steps = 7,
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.pref_hint_font),
                        summary = stringResource(R.string.pref_hint_font_summary),
                        startAction = { PrefIcon(Icons.Rounded.TextFields) },
                        items = fontNames,
                        selectedIndex = hintFont,
                        onSelectedIndexChange = { hintFont = it },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.pref_hint_color),
                        summary = stringResource(R.string.pref_hint_color_summary) + " · " + hexOf(hintColorValue),
                        startAction = { PrefIcon(Icons.Rounded.ColorLens) },
                        onClick = {
                            pendingHintColor = hintColorValue
                            showHintColor = true
                        },
                        endActions = {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(Color(hintColorValue), CircleShape),
                            )
                        },
                    )
                }
            }

            item {
                GroupCard(title = stringResource(R.string.hint_group_position)) {
                    SliderPreference(
                        title = stringResource(R.string.pref_hint_gap),
                        summary = stringResource(R.string.pref_hint_gap_summary),
                        startAction = { PrefIcon(Icons.Rounded.Swipe) },
                        value = hintGap,
                        onValueChange = { hintGap = it.coerceIn(0f, 96f) },
                        valueText = "${hintGap.toInt()} dp",
                        valueRange = 0f..96f,
                    )
                    SliderPreference(
                        title = stringResource(R.string.pref_hint_offset_x),
                        summary = stringResource(R.string.pref_hint_offset_x_summary),
                        startAction = { PrefIcon(Icons.Rounded.Swipe) },
                        value = hintOffsetX,
                        onValueChange = { hintOffsetX = it.coerceIn(-120f, 120f) },
                        valueText = "${hintOffsetX.toInt()} dp",
                        valueRange = -120f..120f,
                    )
                }
            }
        }

        OverlayDialog(
            show = showHintColor,
            title = stringResource(R.string.pref_hint_color),
            onDismissRequest = commitHintColor,
            // 同 RestartDialog：手机上默认贴底，改成居中方框形态
            largeScreen = true,
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ColorPicker(
                        color = Color(pendingHintColor),
                        onColorChanged = { pendingHintColor = it.toArgb() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = commitHintColor) {
                            Text(text = stringResource(R.string.action_done))
                        }
                    }
                }
            },
        )
    }
}

/** 取色对话框（MiuiX 自带的 ColorPicker）。 */
@Composable
internal fun ColorDialog(
    show: Boolean,
    color: Int,
    title: String,
    onColorChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        // 同 RestartDialog：手机上默认贴底，会压在悬浮底栏上，改成居中方框形态
        largeScreen = true,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ColorPicker(
                    color = Color(color),
                    onColorChanged = { onColorChange(it.toArgb()) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) {
                        Text(text = stringResource(R.string.action_done))
                    }
                }
            }
        },
    )
}

/** #RRGGBB，给颜色项当 summary 用。 */
internal fun hexOf(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

// ------------------------------------------------------------------------- 通用组件

@Composable
internal fun GroupCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        SmallTitle(text = title)
        Card(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
internal fun PrefIcon(imageVector: ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground,
    )
}

/** 页面统一的内容区内边距：抵掉顶栏 + 底栏高度。 */
internal fun pagePadding(innerPadding: PaddingValues, bottomBarHeight: Dp): PaddingValues =
    PaddingValues(
        start = 12.dp,
        end = 12.dp,
        top = innerPadding.calculateTopPadding() + 4.dp,
        bottom = innerPadding.calculateBottomPadding() + bottomBarHeight + 20.dp,
    )

/** 页面统一的 LazyColumn 修饰符（滚动联动顶栏收起）。 */
internal fun Modifier.pageScroll(scrollBehavior: ScrollBehavior): Modifier =
    fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)

/**
 * 安全模式卡：显示安全模式状态与「退出安全模式」按钮。
 *
 * **平时整张卡不渲染**，只有进入安全模式才出现 —— 这张卡是全 App 唯一能找到退出按钮的地方，
 * 所以它必须留在参数页底部，不能连着诊断内容一起删。
 *
 * 原先这里还有「运行状态」与「上滑 35% 视图树快照」两块诊断，已按需求移除；
 * [rememberDebugInfo] 仍照常轮询，因为安全模式的正文就存在 `debug_status` 里。
 */
@Composable
internal fun DebugCard() {
    val info = rememberDebugInfo()
    val safe = rememberSafety()
    if (!safe) {
        return
    }
    GroupCard(title = stringResource(R.string.group_debug)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.safety_mode_title),
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = info.first.ifBlank { stringResource(R.string.safety_mode_hint) },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { requestSafetyExit() }) {
                Text(stringResource(R.string.safety_mode_exit))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.safety_mode_exit_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
    }
}

// ------------------------------------------------------------------------- 配置记忆

/** 记忆一个布尔配置；变化时写入本地并同步到 LSPosed 远端配置。 */
@Composable
internal fun rememberPrefBool(key: String, def: Boolean): MutableState<Boolean> {
    val state = remember { mutableStateOf(Prefs.getBoolean(key, def)) }
    val value = state.value
    LaunchedEffect(value) {
        if (Prefs.getBoolean(key, def) != value) {
            Prefs.putBoolean(key, value)
            sync()
        }
    }
    return state
}

/** 记忆一个浮点配置；变化时写入本地并同步到 LSPosed 远端配置。 */
@Composable
internal fun rememberPrefFloat(key: String, def: Float): MutableState<Float> {
    val state = remember { mutableStateOf(Prefs.getFloat(key, def)) }
    val value = state.value
    LaunchedEffect(value) {
        if (Prefs.getFloat(key, def) != value) {
            Prefs.putFloat(key, value)
            sync()
        }
    }
    return state
}

/** 记忆一个整型配置；变化时写入本地并同步到 LSPosed 远端配置。 */
@Composable
internal fun rememberPrefInt(key: String, def: Int): MutableState<Int> {
    val state = remember { mutableStateOf(Prefs.getInt(key, def)) }
    val value = state.value
    LaunchedEffect(value) {
        if (Prefs.getInt(key, def) != value) {
            Prefs.putInt(key, value)
            sync()
        }
    }
    return state
}

/** 记忆一个字符串配置；变化时写入本地并同步到 LSPosed 远端配置。 */
@Composable
internal fun rememberPrefString(key: String, def: String): MutableState<String> {
    val state = remember { mutableStateOf(Prefs.getString(key, def)) }
    val value = state.value
    LaunchedEffect(value) {
        if (Prefs.getString(key, def) != value) {
            Prefs.putString(key, value)
            sync()
        }
    }
    return state
}

/**
 * 每 2 秒读一次 SystemUI 写回来的诊断信息（[Prefs.DIAG_GROUP]）。
 *
 * **不看 LSPosed 服务连没连**：数据现在是本地的，服务没连上时恰恰最需要它 ——
 * “远端配置未就绪”本身就是一种结论，把读取挂在 service != null 上等于把结论读没了。
 */
@Composable
internal fun rememberDebugInfo(): Pair<String, String> {
    var info by remember { mutableStateOf("" to "") }
    LaunchedEffect(Unit) {
        while (true) {
            val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val r = Prefs.readDebug()
                r[0] to r[1]
            }
            if (next != info) {
                info = next
            }
            kotlinx.coroutines.delay(2000)
        }
    }
    return info
}

/**
 * 每 2 秒读一次「是否处于安全模式」（SystemUI 写回来的 [Prefs.KEY_DEBUG_SAFETY]）。
 *
 * **不跟 [rememberDebugInfo] 合并**：那段正文随时会被 controller 覆盖，而“要不要显示
 * 退出按钮”是个二值状态 —— 靠解析正文里有没有“安全模式”四个字来判断太脆，改一句话就失效。
 * 同上，读取不依赖 LSPosed 服务。
 */
@Composable
internal fun rememberSafety(): Boolean {
    var safe by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Prefs.readSafety()
            }
            if (next != safe) {
                safe = next
            }
            kotlinx.coroutines.delay(2000)
        }
    }
    return safe
}

/**
 * 每 2 秒读一次模块运行日志（SystemUI 进程写回来的时间线）。
 *
 * <p>与 [rememberDebugInfo] 分开轮询：诊断卡只在首页出现，日志只在日志页出现，
 * 混在一起会让首页那张卡被日志刷满。同上，读取不依赖 LSPosed 服务。
 */
@Composable
internal fun rememberModuleLog(): String {
    var log by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Prefs.readLog()
            }
            if (next != log) {
                log = next
            }
            kotlinx.coroutines.delay(2000)
        }
    }
    return log
}

/** LSPosed 服务是否已连上（模块激活状态）。 */
@Composable
internal fun rememberServiceBound(): Boolean {
    var bound by remember { mutableStateOf(LockBarApp.isServiceBound()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LockBarApp.isServiceBound()
            if (now != bound) bound = now
            kotlinx.coroutines.delay(1000)
        }
    }
    return bound
}

internal fun sync() {
    Prefs.syncToFramework(LockBarApp.getService())
}

/**
 * 请 SystemUI 退出安全模式。
 *
 * 走远端配置而不是 [com.lockbar.app.DiagProvider]：hook 侧本来就有
 * `OnSharedPreferenceChangeListener`，值一变就被叫醒，不用轮询 —— 而且远端配置恰好是
 * App 能写、hook 能读的通道（`getRemotePreferences` 对 hook 进程只读，单向正好够用）。
 *
 * 时间戳必须**递增**：hook 侧拿它和「进入安全模式的时刻」比大小，停在旧值上不作数。
 * 这也保证了退出过的旧请求不会误清掉下一次新触发的安全模式。
 */
internal fun requestSafetyExit() {
    Prefs.putLong(Prefs.KEY_SAFETY_EXIT, System.currentTimeMillis())
    sync()
}
