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

        // 自动检查更新：只在真正的冷启动查一次。
        // savedInstanceState != null 说明是旋转 / 深色切换之类的重建，重查一遍纯属浪费流量。
        if (savedInstanceState == null && Prefs.getBoolean(Prefs.KEY_AUTO_UPDATE, true)) {
            UpdateChecker.check()
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            LockBarTheme {
                LockBarScreen()
            }
        }
    }
}
