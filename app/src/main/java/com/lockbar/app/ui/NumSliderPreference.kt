package com.lockbar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.lockbar.app.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlinx.coroutines.delay

/** 超出范围时输入框下方那行字：MiuiX 色板没有 error 槽位，用一个固定的醒目红。 */
private val NumErrorRed = Color(0xFFE53935)

/**
 * 参数值 → 显示文本，**滑条右侧、对话框初值、确认后回写用的都是它**。
 *
 * <p>这一条是整个组件存在的理由：从前每个滑条各写各的
 * (`"${(v * 100).toInt()}%"` / `String.format("%.2f", v)` …)，显示和输入对不上时
 * 表现为「条上写 95、对话框里默认是 96」，肉眼看着像写错了值其实只是两处算法不同。
 *
 * @param digits 0 = 整数（与旧的 `.toInt()` 逐位相同），>0 = 固定小数位。
 */
private fun numText(v: Float, digits: Int): String =
    if (digits > 0) String.format(Locale.ROOT, "%.${digits}f", v) else v.toInt().toString()

/**
 * 数值参数条：点条弹出输入框，可以直接敲数字，不必慢慢拖。
 *
 * <p>四件事都在这里做完，调用方只负责把 value / onValueChange 递进来：
 *
 * 1. **点击弹窗交给库** —— 用 `SliderPreference(onClick = …)`，不是自己在外面套一层
 *    `detectTapGestures`。`onClick != null` 时库会在右端画出箭头（`showArrow`）、
 *    自己处理按压态（`holdDownState`）和「点标题区 vs 拖滑块」的手势分工。
 *    这就是 miuix 官方 example 里 Arrow 那一节的写法；自己接管手势是多余的，
 *    一旦库内部有自己的 clickable 就会抢走事件，表现为点了没反应。
 * 2. **范围校验** —— 敲进去的数落在 `valueRange * scale` 之外，输入框下方出红字、
 *    「确认」置灰，不写盘。比较时留 0.01 的容差：`0.3f * 100f` 实际是
 *    30.0000012，不留容差的话合法的最小值 30 会被自己判成越界。
 * 3. **三个按钮一行、等宽** —— 恢复默认只把默认值**填进输入框**不落盘，按「确认」才生效，
 *    按「取消」等于什么都没发生。三个键不分主次：宽度 1:1:1，确认也不上主色背景，
 *    能不能按只看 `enabled`。"恢复默认"四个字最长，**但不靠拉权重解决**（一拉开三个键
 *    就不是一个系列的了），改收它自己的 `insideMargin`：库默认每边 16dp，等宽分到约
 *    98dp 减掉 32dp 只剩 66dp，四个字差一点装不下才换行；内容是居中的，收了外观不变。
 * 4. **贴底而非居中** —— `largeScreen` 不传（默认 null，交给库按窗口尺寸判断），
 *    手机上走贴底分支，而 `DialogContentLayout` 早就给容器加了 `imePadding()`，
 *    输入法一弹弹窗就跟着贴上去。把这个参数填成 `true` 会把弹窗钉在屏幕正中，
 *    键盘一起底下必然空出一块，怎么调都对不齐。
 *
 * <p>**底栏要让开，而不是给它让出一条缝**：卡片贴底正好落进底栏那一格，本来就该这样。
 * 但底栏画在 `MainShell` **外层 Box 的最后**，和住在 Scaffold 里的弹窗压根不是同一个父
 * Box，`zIndex` 跨不了层 —— 所以是**底栏盖住弹窗**，不是弹窗压住底栏；而且它不在 Scaffold
 * 里、拿不到那层全屏 `windowDimming`，该暗的地方它不暗。既定做法是**弹窗期间让底栏不在场**
 * （`RestartDialog` 与参数页取色器都接了 `MainShell` 那个开关），本组件再用 `onInputChange`
 * 把状态报给页面、并进同一个开关。此前按 `bottomBarHeight` 做高度避让，卡片和底栏之间空出
 * 72dp、弹窗像飘在半空，反而不协调 —— 避让整段（`outsideMargin` 覆盖、`imeUp` 开关、
 * 跟着键盘算的补齐量）已全部删除。
 *
 * <p>state 刻意留在调用方而不在这里 `remember`：玻璃页那十条还要被页尾的
 * 「恢复默认」整组按钮一起赋值；字重那条存的又是 Int（远端配置按类型原样存，
 * 改成 float 会让 `LockConfig` 读取抛 ClassCastException、整个模块 0 效果）。
 */
@Composable
internal fun NumSliderPreference(
    value: Float,
    onValueChange: (Float) -> Unit,
    /** 恢复默认按钮填进输入框的值，必须与 `rememberPrefXxx(key, def)` 的 def 相同。 */
    defaultValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    title: String,
    summary: String,
    startAction: @Composable () -> Unit,
    /** 单位原文，紧跟数值：`"%"` → 85%、`" dp"` → 3 dp；空串则不显示也不占位。 */
    unit: String = "",
    /** 显示值 = 存储值 × scale。百分比条传 100f，其余传 1f。 */
    scale: Float = 1f,
    /** 显示与输入的小数位数；0 = 整数。 */
    digits: Int = 0,
    enabled: Boolean = true,
    /** 有离散步进的条才传（字重 7、字号 33）；null 就是库的默认 0（连续）。 */
    steps: Int? = null,
    /**
     * 输入弹窗是否在显示 —— 页面拿它让开悬浮底栏（底栏画在 MainShell 外层 Box 的最后，
     * 压根盖在弹窗上面，而且它不在 Scaffold 里、拿不到弹窗的整屏遮罩）。
     * 传 null 表示这一页没有底栏可让：玻璃页 / 提示页是二级页，底栏本来就被压在下层。
     */
    onInputChange: ((Boolean) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }

    // 弹出即自动聚焦：焦点给到输入框、输入法跟着起来，光标落在末尾（openDialog 里设的）
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val lo = valueRange.start * scale
    val hi = valueRange.endInclusive * scale
    val unitText = unit.trim()

    // 开关弹窗的唯一出口：状态和「让底栏」的通知一起发。四条关闭路径（确认 / 取消 /
    // 点外面 / 系统返回）都必须走这里，漏掉一条底栏就永远回不来。
    // **不能只靠 SideEffect 转发** —— 那个 lambda 在组合之外执行，读状态不注册失效源：
    // 第一版把 showInputDialog 只写在 SideEffect 里，页面压根不会重组，开关传不到
    // MainShell，底栏纹丝不动。通知直接同步发出去，不经过任何重组。
    val setOpen: (Boolean) -> Unit = { v ->
        if (open != v) {
            open = v
            onInputChange?.invoke(v)
        }
    }

    // 每次重组都重取最新 value：onClick 捕获的是闭包，不重取的话拖完再点开会
    // 拿到按下瞬间的旧值。
    val openDialog by rememberUpdatedState {
        val t = numText(value * scale, digits)
        field = TextFieldValue(text = t, selection = TextRange(t.length))
        setOpen(true)
    }
    val fillDefault by rememberUpdatedState {
        val t = numText(defaultValue * scale, digits)
        field = TextFieldValue(text = t, selection = TextRange(t.length))
    }

    val raw = field.text.trim()
    val parsed = raw.toFloatOrNull()
    val error: String? = if (parsed == null) {
        stringResource(R.string.numdlg_invalid)
    } else if (parsed < lo - 0.01f || parsed > hi + 0.01f) {
        // 范围数字已经在副标题里了，这里只喊一声，不重复
        stringResource(R.string.numdlg_out_of_range)
    } else {
        null
    }

    val commit: () -> Unit = {
        // 吸附在**显示尺度**上做，再除回 scale：先除 scale 会让「85」变成 0.85，
        // 而 Math.round(0.85f) = 1 —— 一步就毁掉整条
        if (parsed != null && error == null) {
            val factor = Math.pow(10.0, digits.toDouble()).toFloat()
            val snapped = Math.round(parsed * factor) / factor / scale
            onValueChange(snapped.coerceIn(valueRange.start, valueRange.endInclusive))
            setOpen(false)
        }
    }

    // 弹出后自动聚焦并唤起输入法。OverlayDialog 有滑入动画，content 得先挂上才可聚焦，
    // 太早 requestFocus 会抛；第一拍不成就等第二拍。全都失败也只是不自动弹键盘。
    LaunchedEffect(open) {
        if (open) {
            for (wait in longArrayOf(80L, 260L)) {
                delay(wait)
                try {
                    focusRequester.requestFocus()
                    keyboard?.show()
                    break
                } catch (ignored: Throwable) {
                    // 还没挂上，等下一拍
                }
            }
        }
    }

    // 输入弹窗是模态的：显示期间页面要让开悬浮底栏。底栏画在 MainShell 外层 Box 的**最后**，
    // 跟住在 Scaffold 里的弹窗不是同一个父 Box，zIndex 跨不了层 —— 实测就是底栏盖住弹窗；
    // 而且它不在 Scaffold 里、拿不到那层全屏 windowDimming，该暗的地方它不暗。
    // 既定做法是弹窗期间让底栏不在场（重启弹窗、参数页取色器接的是同一个开关）。
    // SideEffect 每次重组都推当前 open（幂等，键盘弹起引起重组也不会推错），离组补一次 false。
    SideEffect { onInputChange?.invoke(open) }
    DisposableEffect(onInputChange) { onDispose { onInputChange?.invoke(false) } }

    // 副标题由库渲染（居中、12dp 间距都归它管），对应参考图里输入框上方那行 Enter 0-100。
    val rangeText = stringResource(R.string.numdlg_range) + " " +
        numText(lo, digits) + " ~ " + numText(hi, digits)

    OverlayDialog(
        show = open,
        title = title,
        summary = rangeText,
        onDismissRequest = { setOpen(false) },
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = field,
                        onValueChange = { field = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = MiuixTheme.textStyles.main
                            .copy(color = MiuixTheme.colorScheme.onBackground),
                    )
                    if (unitText.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = unitText,
                            style = MiuixTheme.textStyles.main,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (error != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MiuixTheme.textStyles.footnote2,
                        color = NumErrorRed,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                // 三个按钮等宽。三个键的**宽度**一概 1:1:1，别为了塞字去拉权重 ——
                // 上一版 1.6 / 0.7 / 0.7 让三个键看着不是一个系列的。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.numdlg_restore),
                        onClick = fillDefault,
                        modifier = Modifier.weight(1f),
                        // 只收这一个的内边距：库里默认每边 16dp，等宽分到的约 98dp 减掉
                        // 32dp 只剩 66dp 给文字，"恢复默认"四个字差那么一点装不下才换行的。
                        // 内容是 Arrangement.Center 居中排的，收掉的 padding 只是把空间让给
                        // 文字，按钮宽和观感都不变；取消 / 确认两个字用不满，照旧默认。
                        insideMargin = PaddingValues(horizontal = 4.dp, vertical = 13.dp),
                    )
                    TextButton(
                        text = stringResource(R.string.numdlg_cancel),
                        onClick = { setOpen(false) },
                        modifier = Modifier.weight(1f),
                    )
                    // 不传 colors：确认和另外两个同一个背景，能不能按只看 enabled
                    TextButton(
                        text = stringResource(R.string.numdlg_confirm),
                        onClick = commit,
                        modifier = Modifier.weight(1f),
                        enabled = error == null,
                    )
                }
            }
        },
    )

    SliderPreference(
        title = title,
        summary = summary,
        startAction = startAction,
        value = value,
        onValueChange = onValueChange,
        valueText = numText(value * scale, digits) + unit,
        valueRange = valueRange,
        steps = steps ?: 0,
        enabled = enabled,
        onClick = if (enabled) {
            { openDialog() }
        } else {
            null
        },
        holdDownState = open,
    )
}
