# 保留 Xposed 入口与被反射调用的类
-keep class com.lockbar.app.xposed.** { *; }
-keep class io.github.libxposed.** { *; }

# MiuiX / Compose 交给默认规则
-dontwarn top.yukonga.miuix.**
