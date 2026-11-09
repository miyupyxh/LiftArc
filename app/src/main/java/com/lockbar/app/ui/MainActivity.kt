package com.lockbar.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.lockbar.app.Prefs
import com.lockbar.app.ui.theme.LockBarTheme
import com.lockbar.app.update.UpdateChecker

/** 模块设置界面宿主 Activity（MiuiX 风格，布局参考 KernelSU 设置页）。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            LockBarTheme {
                // LockBarScreen 外层的 Miuix Scaffold 统一承载更新弹窗。
                LockBarScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次打开或回到 App 都检查；用户关闭自动检查后不联网。
        if (Prefs.getBoolean(Prefs.KEY_AUTO_UPDATE, true)) {
            UpdateChecker.check()
        }
    }
}
