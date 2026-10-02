package com.lockbar.app.xposed;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;

/**
 * 模块运行日志：SystemUI 进程里的时间线缓冲，回传给 App 的「模块日志」页展示。
 *
 * <p>存在的意义是排查“模块不生效”：用户只说没反应，光靠 LSPosed 日志（要连电脑、要会
 * 看 logcat）门槛太高。这里把关键节点留在进程内，App 一打开就能看到模块到底加载没有、
 * 作用域对不对、远端配置拿到没有、哪些 hook 挂失败了。
 *
 * <p>两条出口，同一条缓冲：
 * <ul>
 *   <li>{@link XposedInterface#log} —— 同步进 LSPosed 的日志页，不依赖任何 Context，永远可用；</li>
 *   <li>{@link #flush()} —— 通过 App 导出的 {@code DiagProvider} 写进 App 私有的
 *       {@code lockbar_debug}，App 侧轮询读取。</li>
 * </ul>
 *
 * <p><b>为什么不能写远端配置</b>：libxposed 的 {@code getRemotePreferences} 在被 hook 的
 * 应用里是<b>只读</b>的（官方 javadoc 原话），{@code edit()} 必抛
 * {@code UnsupportedOperationException}。历史版本就是栽在这里 —— 日志根本送不出去。
 *
 * <p><b>为什么要等 Context</b>：ContentProvider 调用必须有 {@link Context}，而
 * {@code onPackageLoaded} 阶段还没有 —— 只能挂 {@code ContextWrapper#attachBaseContext}
 * 等 SystemUI 的 Application attach 上来。所以这里先把日志攒着，
 * {@link #setContext(Context)} 一到就补一次 {@link #flush()}。
 *
 * <p><b>红线</b>：本类运行在 SystemUI 进程，严禁引用任何 Compose / MiuiX / AppCompat 代码。
 */
final class ModuleLog {

    private static final String TAG = "LockBar";

    /** 环形缓冲行数上限，防止长时间运行把远端配置撑爆。 */
    private static final int MAX_LINES = 300;
    /** 单条远端字符串的长度上限（留足余量，SP 单值不宜过大）。 */
    private static final int MAX_CHARS = 12000;

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile XposedInterface module;
    /** 落盘目标；在 {@code LockBarHooks.install} 里最早绑定，保证中途抛异常也写得出去。 */
    private static volatile LockConfig sink;
    /**
     * SystemUI 的 Context，回传日志用。{@code onPackageLoaded} 时还没有，
     * 由 {@code LockBarHooks} 挂的 {@code attachBaseContext} 回调填进来。
     */
    private static volatile Context context;

    private ModuleLog() {
    }

    /** 记录 LSPosed 句柄；必须在任何 log 调用之前执行，否则缓冲之外什么也留不下。 */
    static void init(XposedInterface m) {
        module = m;
    }

    /**
     * 拿到 SystemUI 的 Context。这是日志能送进 App 的<b>唯一前提</b>。
     *
     * <p>到达时立刻补一次 {@link #flush()} —— 安装阶段积压的那几条
     * （尤其“hook 安装失败”）当时还没有 Context，只能等这里。
     */
    static void setContext(Context c) {
        if (c == null || context != null) {
            return;   // 已经有了就别反复刷
        }
        context = c;
        flush();
    }

    static Context context() {
        return context;
    }

    /** 记录一条普通信息（level 3，等同 logcat 的 INFO）。 */
    static void d(String msg) {
        append(msg, null, 3);
    }

    /** 记录一条警告（level 4）：功能降级，但模块还在跑。 */
    static void w(String msg, Throwable t) {
        append(msg, t, 4);
    }

    /** 记录一条错误（level 5）：hook 挂失败，很可能就是“不生效”的直接原因。 */
    static void e(String msg, Throwable t) {
        append(msg, t, 5);
    }

    /** 绑定落盘目标。要在建好 {@link LockConfig} 之后立刻调，越早越好。 */
    static void bind(LockConfig config) {
        sink = config;
    }

    private static void append(String msg, Throwable t, int level) {
        String time;
        // SimpleDateFormat 不是线程安全的
        synchronized (FMT) {
            time = FMT.format(new Date());
        }
        String line = time + " " + msg;
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
        XposedInterface m = module;
        if (m != null) {
            try {
                if (t != null) {
                    m.log(level, TAG, msg, t);
                } else {
                    m.log(level, TAG, msg);
                }
            } catch (Throwable ignored) {
                // LSPosed 日志写不进去不能影响缓冲
            }
        }
    }

    /**
     * 「一键清空」：App 侧清完展示副本后，把进程内的环形缓冲也清掉。
     *
     * <p>不主动 {@link #flush()} —— 空缓冲 {@code flush} 本来就会早退，展示副本由 App 自己
     * 清；不清这里的话下一次 {@code flush} 会把老行原样灌回去，清空就白点了。
     */
    static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    /**
     * 把缓冲整体回传给 App。
     *
     * <p>只在内容变化时才真正落盘（{@link LockConfig#putDebug} 自带比对），所以多调几次没有开销。
     * 没绑定过目标、或还没有 {@link Context}，就直接返回 —— 后者等 {@link #setContext} 到手会补。
     */
    static void flush() {
        LockConfig target = sink;
        if (target == null) {
            return;
        }
        String text;
        synchronized (LINES) {
            if (LINES.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder(LINES.size() * 48);
            for (String line : LINES) {
                sb.append(line).append('\n');
            }
            text = sb.toString();
        }
        if (text.length() > MAX_CHARS) {
            // 太长就砍掉最老的行，只保留尾巴
            text = text.substring(text.length() - MAX_CHARS);
        }
        target.putDebug(LockConfig.KEY_DEBUG_LOG, text);
    }
}
