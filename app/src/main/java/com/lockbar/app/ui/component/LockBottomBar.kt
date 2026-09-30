package com.lockbar.app.ui.component

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 底栏的一个标签。 */
data class BottomTab(
    val label: String,
    val icon: ImageVector,
)

/**
 * 底栏（**整段照搬 KernelSU 的实现**，见 `ui/component/FloatingBottomBar.kt`）。
 *
 * [floating] 开：KernelSU 的液态玻璃浮动胶囊 [FloatingBottomBar] —— 整条栏是**一个拖拽面**，
 * 按下就起 `pressProgress`（胶囊 1.2× 放大 + 镜面高光 + 折射镜头），拖动时 `DampedDragAnimation`
 * 用 spring 跟手在几个标签之间滑，松手四舍五入落位；指示器是**先录进 backdrop、再按胶囊裁出来**
 * 的一块，所以选中项的图标文字会跟着一起变成强调色。
 * [floating] 关：贴底整条 [NavigationBar]。
 *
 * @param onHeightChanged 报告自身实际高度（含导航栏内边距），供内容区留白。
 */
@Composable
fun LockBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    floating: Boolean,
    glass: Boolean,
    backdrop: LayerBackdrop,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { onHeightChanged(it.size.height) },
    ) {
        if (floating) {
            FloatingBottomBar(
                // KernelSU 同款：先把胶囊周围空白区的点击吞掉，再留出边距
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(
                        start = 28.dp,
                        end = 28.dp,
                        bottom = if (navBottom != 0.dp) 8.dp + navBottom else 28.dp,
                    ),
                selectedIndex = selectedIndex,
                onSelected = onSelect,
                backdrop = backdrop,
                tabsCount = tabs.size,
                isBlurEnabled = glass,
            ) { activateTab ->
                tabs.forEachIndexed { index, tab ->
                    FloatingBottomBarItem(
                        selected = selectedIndex == index,
                        onClick = { activateTab(index) },
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                    ) {
                        // 图标与文字取 LocalContentColor；指示器里那份强调色副本由 backdrop 裁出来
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                        )
                        Text(
                            text = tab.label,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                        )
                    }
                }
            }
        } else {
            FlatBar(
                tabs = tabs,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 贴底的整条导航栏（“浮动底栏”关掉时的形态）。 */
@Composable
private fun FlatBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        color = MiuixTheme.colorScheme.surface,
        showDivider = true,
        content = {
            tabs.forEachIndexed { index, tab ->
                NavigationBarItem(
                    modifier = Modifier.weight(1f),
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    icon = tab.icon,
                    label = tab.label,
                )
            }
        },
    )
}
