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
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lockbar.app.Prefs
import com.lockbar.app.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 锁屏小组件参数页：开关 / 大小 / 相对时钟的下移量。 */
@Composable
internal fun LockWidgetScreen(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()

    var size by rememberPrefFloat(Prefs.KEY_LOCK_WIDGET_SIZE, 81f)
    var offsetY by rememberPrefFloat(Prefs.KEY_LOCK_WIDGET_Y, 8f)
    var offsetX by rememberPrefFloat(Prefs.KEY_LOCK_WIDGET_X, 0f)

    val reset: () -> Unit = {
        size = 81f
        offsetY = 8f
        offsetX = 0f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.pref_lock_widget),
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
            item {
                SmallTitle(text = stringResource(R.string.pref_lock_widget))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        NumSliderPreference(
                            value = size,
                            onValueChange = { size = it },
                            defaultValue = 81f,
                            valueRange = 36f..200f,
                            title = "大小",
                            startAction = { PrefIcon(Icons.Rounded.Tune) },
                            unit = " dp",
                            digits = 0,
                        )
                        NumSliderPreference(
                            value = offsetY,
                            onValueChange = { offsetY = it },
                            defaultValue = 8f,
                            valueRange = -400f..900f,
                            title = "位置（下）",
                            startAction = { PrefIcon(Icons.Rounded.Tune) },
                            unit = " dp",
                            digits = 0,
                        )
                        NumSliderPreference(
                            value = offsetX,
                            onValueChange = { offsetX = it },
                            defaultValue = 0f,
                            valueRange = -400f..400f,
                            title = "位置（右）",
                            startAction = { PrefIcon(Icons.Rounded.Tune) },
                            unit = " dp",
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
