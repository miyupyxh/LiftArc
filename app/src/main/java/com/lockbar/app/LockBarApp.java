package com.lockbar.app;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 进程入口。
 *
 * 通过 libxposed service 与 LSPosed 建立连接，配置写在本地 SharedPreferences 的同时
 * 会同步到 LSPosed 的远端配置里，SystemUI 进程通过 {@code getRemotePreferences} 读到同一份数据。
 */
public class LockBarApp extends Application implements XposedServiceHelper.OnServiceListener {

    private static volatile XposedService service;

    public static XposedService getService() {
        return service;
    }

    public static boolean isServiceBound() {
        return service != null;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        installCrashLog();
        // 这里不能用 getApplicationContext()：attach 阶段它还是 null，会 NPE 闪退
        Prefs.init(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);   // 兜底：确保配置一定拿得到
        XposedServiceHelper.registerListener(this);
    }

    /** 把未捕获异常写进 files/crash.log，方便定位“一点开就闪退”这类问题。 */
    private void installCrashLog() {
        try {
            Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try (PrintWriter w = new PrintWriter(new FileWriter(
                        new File(getFilesDir(), "crash.log"), false))) {
                    throwable.printStackTrace(w);
                } catch (Throwable ignored) {
                    // 写不了就只打 log
                }
                Log.e("LockBar", "uncaught in " + thread.getName(), throwable);
                if (prev != null) {
                    prev.uncaughtException(thread, throwable);
                }
            });
        } catch (Throwable ignored) {
            // 安装失败不影响功能
        }
    }

    @Override
    public void onServiceBind(XposedService service) {
        LockBarApp.service = service;
        // 首次连接时把本地配置推送到 LSPosed，保证 SystemUI 立即生效
        Prefs.syncToFramework(service);
    }

    @Override
    public void onServiceDied(XposedService service) {
        if (LockBarApp.service == service) {
            LockBarApp.service = null;
        }
    }
}
