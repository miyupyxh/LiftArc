package com.lockbar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lockbar.app.Prefs
import com.lockbar.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 锁屏右上方小白条参数页：长度 / 粗细 / 位置 / 弹性 / 透明度。 */
@Composable
internal fun TopRightHandleScreen(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()

    var barLength by rememberPrefFloat(Prefs.KEY_TOP_RIGHT_BAR_LENGTH, 48f)
    var barThick by rememberPrefFloat(Prefs.KEY_TOP_RIGHT_BAR_THICK, 6f)
    var barX by rememberPrefFloat(Prefs.KEY_TOP_RIGHT_BAR_X, 18f)
    var barY by rememberPrefFloat(Prefs.KEY_TOP_RIGHT_BAR_Y, 0f)
    var barElastic by rememberPrefFloat(Prefs.KEY_TOP_RIGHT_BAR_ELASTIC, 0.6f)
    var barOpacity by rememberPrefFloat(Prefs.KEY_TOP_RIGHT_BAR_OPACITY, 0.85f)

    // 真机实测几何（状态栏顶部 / 信号塔位置）：只给「对齐到信号塔下方」用
    val metrics = rememberLockMetrics()

    val reset: () -> Unit = {
        barLength = 48f
        barThick = 6f
        barX = 18f
        barY = 0f
        barElastic = 0.6f
        barOpacity = 0.85f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.pref_top_right_handle_params),
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
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (metrics != null && metrics.hasTower) {
                // 真机实测到了信号塔：一键把横条放到塔正下方，省得各机型自己试数值
                val gap = 1f
                val target = (metrics.towerBottomDp - metrics.statusBarTopDp + gap)
                    .coerceIn(0f, 240f)
                item {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { barY = (target * 10f).toInt() / 10f },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("对齐到信号塔下方（${fmt1(target)} dp）")
                    }
                }
            }
            item {
                SmallTitle(text = stringResource(R.string.pref_top_right_handle_params))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        NumSliderPreference(
                            value = barLength,
                            onValueChange = { barLength = it },
                            defaultValue = 48f,
                            valueRange = 16f..200f,
                            title = "长度",
                            startAction = { PrefIcon(Icons.Rounded.Tune) },
                            unit = " dp",
                            digits = 0,
                        )
                        NumSliderPreference(
                            value = barThick,
                            onValueChange = { barThick = it },
                            defaultValue = 6f,
                            valueRange = 2f..24f,
                            title = "粗细",
                            startAction = { PrefIcon(Icons.Rounded.Tune) },
                            unit = " dp",
                            digits = 0,
                        )
                        NumSliderPreference(
                            value = barX,
                            onValueChange = { barX = it },
                            defaultValue = 18f,
                            valueRange = 0f..160f,
                            title = "位置（右）",
                            startAction = { PrefIcon(Icons.Rounded.Tune) },
                            unit = " dp",
                            digits = 0,
                        )
                        NumSliderPreference(
                            value = barY,
                            onValueChange = { barY = it },
                            defaultValue = 0f,
                            valueRange = 0f..240f,
                            title = "位置（上）",
                            startAction = { PrefIcon(Icons.Rounded.Tune) },
                            unit = " dp",
                            digits = 0,
                        )
                        NumSliderPreference(
                            value = barElastic,
                            onValueChange = { barElastic = it },
                            defaultValue = 0.6f,
                            valueRange = 0f..1f,
                            title = "弹性",
                            startAction = { PrefIcon(Icons.Rounded.Tune) },
                            scale = 100f,
                            unit = "%",
                            digits = 0,
                        )
                        NumSliderPreference(
                            value = barOpacity,
                            onValueChange = { barOpacity = it },
                            defaultValue = 0.85f,
                            valueRange = 0f..1f,
                            title = "透明度",
                            startAction = { PrefIcon(Icons.Rounded.Tune) },
                            scale = 100f,
                            unit = "%",
                            digits = 0,
                        )
                    }
                }
            }
            item {
                androidx.compose.material3.Button(
                    onClick = reset,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MiuixTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("恢复默认")
                }
            }
        }
    }
}

// --------------------------------------------------------------- 真机几何（SystemUI 回传）

/**
 * 锁屏状态栏 / 信号塔的真机几何，单位是<b>窗口坐标 px</b>。
 *
 * <p>由 SystemUI 侧 `LockBarController#publishLayoutMetrics` 实测后经 DiagProvider 回传，
 * 参数页拿它算「对齐到信号塔下方」的落点 —— 换机型也不用改代码。
 */
private data class LockMetrics(
    val w: Int,
    val h: Int,
    val dpi: Int,
    val sbT: Int,
    val sbH: Int,
    val tL: Int,
    val tT: Int,
    val tR: Int,
    val tB: Int,
    val cls: String,
) {
    /** px → dp 的换算因子（dpi/160）。 */
    val density: Float get() = if (dpi > 0) dpi / 160f else 3f

    val hasTower: Boolean get() = tB > tT && tR > tL

    val statusBarTopDp: Float get() = sbT / density
    val towerBottomDp: Float get() = tB / density
}

/** 解析 `w=..;h=..;dpi=..;sbT=..;...`；字段缺/非法就返回 null。 */
private fun parseLockMetrics(raw: String): LockMetrics? {
    if (raw.isBlank()) {
        return null
    }
    val map = raw.split(';').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
    }.toMap()
    fun int(key: String, def: Int = 0): Int = map[key]?.toIntOrNull() ?: def
    val w = int("w")
    val h = int("h")
    if (w <= 0 || h <= 0) {
        return null
    }
    return LockMetrics(
        w = w,
        h = h,
        dpi = int("dpi", 480),
        sbT = int("sbT"),
        sbH = int("sbH"),
        tL = int("tL"),
        tT = int("tT"),
        tR = int("tR"),
        tB = int("tB"),
        cls = map["cls"] ?: "",
    )
}

/**
 * 每 1.5 秒读一次真机几何。
 *
 * <p>不缓存成永久值：用户可能在设置页停留很久，期间重启过 SystemUI 或换过锁屏样式，
 * 重新量一次的成本只是读一个 SharedPreferences 键。
 */
@Composable
private fun rememberLockMetrics(): LockMetrics? {
    var metrics by remember { mutableStateOf(parseLockMetrics(Prefs.readLayout())) }
    LaunchedEffect(Unit) {
        while (true) {
            val next = withContext(Dispatchers.IO) { parseLockMetrics(Prefs.readLayout()) }
            if (next != metrics) {
                metrics = next
            }
            delay(1500)
        }
    }
    return metrics
}

private fun fmt1(v: Float): String {
    val r = (v * 10f).toInt()
    return if (r % 10 == 0) (r / 10).toString() else "${r / 10}.${r % 10}"
}
