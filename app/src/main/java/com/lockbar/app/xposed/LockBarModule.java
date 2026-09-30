package com.lockbar.app.xposed;

import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Xposed 入口，由 LSPosed 通过 META-INF/xposed/java_init.list 发现并加载。
 *
 * 该类（以及 com.lockbar.app.xposed 包下的所有类）会运行在 SystemUI 进程中，
 * 因此严禁引用任何 Compose / MiuiX / AppCompat 代码，只允许使用 Android Framework API。
 */
public class LockBarModule extends XposedModule {

    private static LockBarModule sInstance;

    public static LockBarModule getInstance() {
        return sInstance;
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        sInstance = this;
        // 第一时间挂上日志：后面所有排查都从这一行开始
        ModuleLog.init(this);
        ModuleLog.d("模块加载成功，进程=" + param.getProcessName()
                + "，API=" + getApiVersion()
                + "，框架=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (!"com.android.systemui".equals(param.getPackageName())) {
            // 作用域没勾 com.android.systemui 时，这条日志压根不会出现 —— 它缺席本身就是结论
            return;
        }
        ModuleLog.d("SystemUI 包已加载（作用域正确），开始装 hook");
        try {
            LockBarHooks.install(this, param.getDefaultClassLoader(), dataDirOf(param));
            ModuleLog.d("hook 全部安装完成");
        } catch (Throwable t) {
            ModuleLog.e("hook 安装失败，模块不会生效：" + t, t);
        } finally {
            // install 中途抛异常时，末尾那句 flush 走不到，这里保证一定落盘
            ModuleLog.flush();
        }
    }

    /**
     * 宿主的 {@code dataDir}：安全模式状态文件的落点。
     *
     * <p>之所以能在 {@code onPackageLoaded} 就拿到 —— 这正是安全模式能排在“装任何 hook
     * 之前”的前提。拿不到一律返回 {@code null}，由调用方退化成“不启用安全模式”。
     */
    private static String dataDirOf(PackageLoadedParam param) {
        try {
            ApplicationInfo ai = param.getApplicationInfo();
            return ai != null ? ai.dataDir : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
