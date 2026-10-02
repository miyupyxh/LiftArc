package com.lockbar.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

import io.github.libxposed.service.XposedService;

/**
 * 配置存储。
 *
 * App 进程写入本地 SharedPreferences，同时同步到 LSPosed 远端配置；
 * SystemUI 进程通过 XposedModule#getRemotePreferences 读取同一组数据。
 */
public final class Prefs {

    /** 远端配置分组名，两个进程必须一致。 */
    public static final String GROUP = "lockbar_settings";

    /**
     * 诊断数据的<b>独立</b>存储文件（SystemUI 经 {@code DiagProvider} 回传）。
     *
     * <p>刻意不放进 {@link #GROUP}：{@link #syncToFramework} 只遍历 `lockbar_settings`，
     * 分开放就永远不会被同步冲掉 —— 旧设计把 `debug_*` 混在同一组里，才那么脆。
     */
    public static final String DIAG_GROUP = "lockbar_debug";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SHOW_HANDLE = "show_handle";
    public static final String KEY_PARALLAX = "parallax";
    public static final String KEY_HANDLE_ALPHA = "handle_alpha";
    public static final String KEY_DRAG_RATIO = "drag_ratio";
    public static final String KEY_ARC_ENABLED = "arc_enabled";
    public static final String KEY_ARC_DIM = "arc_dim";
    /** 压暗强度（只作用于压暗模式）。 */
    public static final String KEY_DIM_STRENGTH = "dim_strength";
    public static final String KEY_ARC_WIDTH = "arc_width";
    public static final String KEY_ARC_ALPHA = "arc_alpha";
    public static final String KEY_ARC_COLOR = "arc_color";
    public static final String KEY_HAPTIC = "haptic";
    public static final String KEY_RELEASE_RETURN = "release_return";
    public static final String KEY_INTERCEPT_SWIPE = "intercept_swipe";
    /** 扩大触控范围（向两边扩展）。 */
    public static final String KEY_HANDLE_WIDE = "handle_wide";
    public static final String KEY_HINT_TEXT = "hint_text";

    /** 小横条上方文字的样式（独立设置页）。 */
    public static final String KEY_HINT_SIZE = "hint_size";
    public static final String KEY_HINT_WEIGHT = "hint_weight";
    public static final String KEY_HINT_FONT = "hint_font";
    public static final String KEY_HINT_COLOR = "hint_color";
    public static final String KEY_HINT_GAP = "hint_gap";
    public static final String KEY_HINT_OFFSET_X = "hint_offset_x";

    /**
     * 安全模式退出请求：App 写时间戳，hook 侧（{@code SafetyGate}）读到就清零状态文件。
     *
     * <p>走远端配置而不是 {@link DiagProvider} —— 远端配置本来就单向够用（App 能写、hook 能读），
     * 而且 hook 侧的 {@code OnSharedPreferenceChangeListener} 会立刻被叫醒，不用轮询。
     *
     * <p><b>类型红线</b>：写入必须是 Long（见 {@link #putLong}），hook 侧读 long。
     * 两侧不一致就是 v1.0.15 那种 {@code ClassCastException} 的翻版。
     */
    public static final String KEY_SAFETY_EXIT = "safety_exit";

    /**
     * 一键清空模块日志（App 写时间戳 → hook 清环形缓冲）。
     *
     * <p>与 {@link #KEY_SAFETY_EXIT} 同款「App 能写、hook 能读」的远端配置反向通道；
     * 展示副本由 App 直接删 {@link #DIAG_GROUP} 里的 {@link #KEY_DEBUG_LOG}。
     * 类型红线：Long（走 {@link #syncToFramework} 的 Long 分支 → hook 侧 getLng）。
     */
    public static final String KEY_LOG_CLEAR = "log_clear";

    /** SystemUI 写回来的诊断信息（不是配置，不要 syncToFramework 冲掉）。 */
    public static final String KEY_DEBUG_STATUS = "debug_status";
    public static final String KEY_DEBUG_VIEWS = "debug_views";
    /** 拦截系统上滑的计数（单独一条，免得覆盖掉 unlock=）。 */
    public static final String KEY_DEBUG_BLOCK = "debug_block";
    /**
     * 模块运行日志（SystemUI 写回的时间线），同样不是配置 ——
     * 千万别让它进 {@link #syncToFramework}，否则会被本地的同名键冲掉。
     */
    public static final String KEY_DEBUG_LOG = "debug_log";
    /**
     * 是否处于安全模式（{@code "1"} / {@code "0"}），SystemUI 回传。
     *
     * <p>单独一条而不是让首页去解析 {@link #KEY_DEBUG_STATUS} 的正文 —— 靠文本前缀判断
     * “要不要显示退出按钮”太脆，改一句话就失效。
     */
    public static final String KEY_DEBUG_SAFETY = "debug_safety";

    private static SharedPreferences local;
    private static SharedPreferences diag;

    private Prefs() {
    }

    /**
     * 取本地配置。
     *
     * <p>注意：不能用 {@code context.getApplicationContext()} —— 在
     * {@code Application#attachBaseContext} 里它还返回 null（LoadedApk 要等 attach 之后
     * 才回填 Application），直接调会 NPE，导致“一点开就闪退”。这里对它做空保护。
     */
    public static synchronized void init(Context context) {
        if (local != null || context == null) {
            return;
        }
        Context app = null;
        try {
            app = context.getApplicationContext();
        } catch (Throwable ignored) {
            // 部分 ROM 在 attach 阶段直接抛，退化用传进来的 context
        }
        if (app == null) {
            app = context;
        }
        local = app.getSharedPreferences(GROUP, Context.MODE_PRIVATE);
        diag = app.getSharedPreferences(DIAG_GROUP, Context.MODE_PRIVATE);
    }

    public static SharedPreferences local() {
        return local;
    }

    /**
     * 诊断数据存储（{@code DiagProvider} 写、{@link #readDebug} / {@link #readLog} 读）。
     *
     * <p>Provider 那边一定有 Context，而 {@link #init} 可能还没跑到，
     * 所以允许用传进来的 Context 现取 —— SharedPreferences 按文件名在进程内有缓存，
     * 取到的一定是同一个实例。
     */
    public static SharedPreferences debug(Context context) {
        SharedPreferences d = diag;
        return d != null ? d : context.getSharedPreferences(DIAG_GROUP, Context.MODE_PRIVATE);
    }

    public static boolean getBoolean(String key, boolean def) {
        return local != null && local.getBoolean(key, def);
    }

    public static int getInt(String key, int def) {
        return local != null ? local.getInt(key, def) : def;
    }

    public static float getFloat(String key, float def) {
        return local != null ? local.getFloat(key, def) : def;
    }

    public static void putFloat(String key, float value) {
        if (local != null) {
            local.edit().putFloat(key, value).apply();
        }
    }

    public static String getString(String key, String def) {
        String v = local != null ? local.getString(key, null) : null;
        return v != null ? v : def;
    }

    public static void putString(String key, String value) {
        if (local != null) {
            local.edit().putString(key, value).apply();
        }
    }

    public static void putBoolean(String key, boolean value) {
        if (local != null) {
            local.edit().putBoolean(key, value).apply();
        }
    }

    /**
     * 写 long。{@link #syncToFramework} 里有对应的 {@code instanceof Long} 分支，
     * 所以这个键会原样推到 LSPosed 远端配置，hook 侧读到的也是 Long。
     */
    public static void putLong(String key, long value) {
        if (local != null) {
            local.edit().putLong(key, value).apply();
        }
    }

    public static void putInt(String key, int value) {
        if (local != null) {
            local.edit().putInt(key, value).apply();
        }
    }

    /**
     * 读 SystemUI 写回来的诊断信息。
     *
     * <p><b>数据源已换</b>：不再走 {@code XposedService#getRemotePreferences}，
     * 因为那条对 hook 进程是只读的（见 {@link DiagProvider}），SystemUI 根本写不进去。
     * 现在读的是 Provider 落盘的 {@link #DIAG_GROUP}。
     *
     * @return {@code [运行状态 + 拦截计数, 上滑时的视图树快照]}，拿不到就都是空串
     */
    public static String[] readDebug() {
        SharedPreferences d = diag;
        if (d == null) {
            return new String[]{"", ""};
        }
        try {
            String s = d.getString(KEY_DEBUG_STATUS, "");
            String b = d.getString(KEY_DEBUG_BLOCK, "");
            String status = s != null ? s : "";
            if (b != null && !b.isEmpty()) {
                status = status.isEmpty() ? b : status + "\n" + b;
            }
            String v = d.getString(KEY_DEBUG_VIEWS, "");
            return new String[]{status, v != null ? v : ""};
        } catch (Throwable ignored) {
            return new String[]{"", ""};
        }
    }

    /**
     * 读 SystemUI 写回来的模块运行日志。
     *
     * <p>与 {@link #readDebug} 分开：日志是一整段多行文本，诊断卡只需要状态摘要，
     * 混在一起会让首页那张卡被日志刷满。数据源同上，见 {@link DiagProvider}。
     *
     * @return 日志文本；还没有日志时返回空串
     */
    public static String readLog() {
        SharedPreferences d = diag;
        if (d == null) {
            return "";
        }
        try {
            String s = d.getString(KEY_DEBUG_LOG, "");
            return s != null ? s : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * SystemUI 是否处于安全模式。
     *
     * @return {@code true} = 模块已自动闭嘴，首页要给出退出入口
     */
    public static boolean readSafety() {
        SharedPreferences d = diag;
        if (d == null) {
            return false;
        }
        try {
            return "1".equals(d.getString(KEY_DEBUG_SAFETY, "0"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把本地配置整体推送到 LSPosed 远端配置。 */
    public static void syncToFramework(XposedService service) {
        if (service == null || local == null) {
            return;
        }
        try {
            SharedPreferences remote = service.getRemotePreferences(GROUP);
            if (remote == null) {
                return;
            }
            SharedPreferences.Editor editor = remote.edit();
            Map<String, ?> all = local.getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Boolean) {
                    editor.putBoolean(e.getKey(), (Boolean) v);
                } else if (v instanceof Integer) {
                    editor.putInt(e.getKey(), (Integer) v);
                } else if (v instanceof Float) {
                    editor.putFloat(e.getKey(), (Float) v);
                } else if (v instanceof String) {
                    editor.putString(e.getKey(), (String) v);
                } else if (v instanceof Long) {
                    editor.putLong(e.getKey(), (Long) v);
                }
            }
            editor.apply();
        } catch (Throwable ignored) {
            // LSPosed 未就绪时忽略，下次绑定会重试
        }
    }
}
