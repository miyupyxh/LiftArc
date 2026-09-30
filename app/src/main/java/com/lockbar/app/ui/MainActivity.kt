package com.lockbar.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.lockbar.app.ui.theme.LockBarTheme

/** 模块设置界面宿主 Activity（MiuiX 风格，布局参考 KernelSU 设置页）。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            LockBarTheme {
                LockBarScreen()
            }
        }
    }
}
