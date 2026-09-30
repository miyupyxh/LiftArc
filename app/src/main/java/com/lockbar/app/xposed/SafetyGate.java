package com.lockbar.app.xposed;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * 安全模式：系统界面反复崩溃时，本模块主动闭嘴。
 *
 * <p><b>为什么要有</b>：Xposed 模块的故障形态是“拖着宿主一起死”。宿主（SystemUI）一旦进
 * 崩溃循环，用户面对的是一台解不了锁的手机，而定位元凶要连电脑翻日志 —— 这时候最该做的是
 * <b>让模块先消失</b>，而不是继续“努力生效”。宁可模块白装一次，也不能让系统界面起不来。
 *
 * <h2>判据：进程活得够不够久</h2>
 *
 * 状态存在<b>宿主自己的 files 目录</b>（{@code <dataDir>/files/lockbar_safety.properties}）。
 * hook 进程与宿主同 uid，读写这个文件不需要任何权限、不需要 {@link android.content.Context}、
 * 不需要跨进程 —— {@code onPackageLoaded} 阶段就能从 {@code PackageLoadedParam#getApplicationInfo()}
 * 拿到 {@code dataDir} 直接读，这正是它能排在“装任何 hook 之前”的原因。
 *
 * <p>每次启动记两个时刻：本次启动时间、最后一次<b>主线程</b>心跳时间。下次启动算
 * {@code 上次存活 = lastAlive - bootTime}：
 * <ul>
 *   <li>活满 {@link #ALIVE_OK_MS}（3 分钟）→ 上次是正常会话，连击清零；</li>
 *   <li>没活满 → 上次“刚起来就没了”，连击 +1；</li>
 *   <li>连击到 {@link #SAFE_BOOTS}（4）次 → 进安全模式。</li>
 * </ul>
 *
 * <p><b>心跳故意跑在主线程</b>：真出事的时候（2026-09-30 真机那次）主线程卡在类加载递归里
 * 出不来，心跳一断，{@code lastAlive} 就停在启动后不久 —— 正好把“卡死”记成“短命”。
 * 这恰恰是我们要抓的形态。心跳写盘走 {@link LockConfig#io}，主线程只记时间戳。
 *
 * <h2>安全模式下做什么</h2>
 *
 * 只留 {@code captureContext}（拿 Context 用来回传日志、接收退出请求），
 * <b>其余 hook 一个都不装</b>：不碰视图、不碰触摸、不碰配置变更。
 * 模块表现得像没装过，系统界面自己就能起来。
 *
 * <h2>怎么退出</h2>
 *
 * 状态文件在宿主私有目录，App 写不进去；退出请求走<b>远端配置</b>（App 能写、hook 能读），
 * hook 侧收到后清零文件，日志里写明“<b>重启系统界面生效</b>”。
 *
 * <h2>失败即放行</h2>
 *
 * 本类里任何一步出错（拿不到 dataDir、读坏、写失败）都一律返回“不进安全模式”——
 * 绝不能因为“安全检查本身坏了”就把模块干掉，那等于用一个假故障换掉真功能。
 *
 * <p><b>红线</b>：本类运行在 SystemUI 进程，严禁引用任何 Compose / MiuiX / AppCompat 代码。
 */
final class SafetyGate {

    private static final String TAG = "LockBar";
    private static final String FILE_NAME = "lockbar_safety.properties";

    /** 上次进程活过这么久就算一次正常会话，连击清零。 */
    private static final long ALIVE_OK_MS = 3 * 60_000L;
    /** 连续这么多次“短命启动”就判定为崩溃循环。 */
    private static final int SAFE_BOOTS = 4;
    /** 主线程心跳间隔。 */
    private static final long BEAT_MS = 30_000L;

    // ---- 运行状态（只在本进程内用，每次启动都会被 evaluate 覆盖） ----
    private static volatile boolean safe;
    private static volatile String reason = "";
    private static volatile long safeSince;
    private static volatile long lastAlive;

    /** 本次启动时刻；只算连击用，不对外。 */
    private static long bootTime;
    /** 连续短命启动次数。 */
    private static int boots;
    /** 状态文件；dataDir 拿不到时保持 null。 */
    private static File file;
    /** evaluate 是否成功跑完。没跑成就一律视为“未触发”。 */
    private static boolean ready;

    private SafetyGate() {
    }

    /**
     * 启动时判定一次。<b>必须在装任何功能 hook 之前调。</b>
     *
     * @param dataDir 宿主的 {@code ApplicationInfo.dataDir}，可为 null（拿不到就放行）
     * @return {@code true} = 已进入安全模式，本次不要装 hook
     */
    static synchronized boolean evaluate(String dataDir) {
        try {
            if (dataDir == null || dataDir.isEmpty()) {
                return false;
            }
            File dir = new File(dataDir, "files");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            file = new File(dir, FILE_NAME);
            Properties p = load(file);

            long now = System.currentTimeMillis();
            long prevBoot = num(p, "bootTime", 0L);
            long prevAlive = num(p, "lastAlive", 0L);
            boots = (int) num(p, "boots", 0L);
            boolean wasSafe = "1".equals(p.getProperty("safe"));
            safeSince = num(p, "safeSince", 0L);

            // 有没有历史 —— 只有「文件不存在 / bootTime 没记上」才算首次安装。
            // 这里必须和下面的 lived 分开判断，否则会踩一个专抓崩溃循环的坑（见 lived 注释）。
            boolean hasHistory = prevBoot > 0L;

            // 上次进程活了多久。
            //
            //  ⚠ 陷阱：首次运行时 evaluate 会把 lastAlive 与 bootTime 写成同一个值，
            //  而崩溃循环的典型形态（failed to complete startup）恰恰是**进程 20~60 秒就死**、
            //  一个 30s 心跳都没来得及打 —— 于是 prevAlive == prevBoot，差值是 0。
            //  如果把 0 当成「没有历史」，boots 永远清零、连击永远累不起来，安全模式形同虚设。
            //  所以：0 表示「一个心跳都没打就死了」，这是**最确凿的短命证据**。
            long lived;
            if (!hasHistory) {
                lived = -1L;                                   // 首次安装，没有历史
            } else if (prevAlive >= prevBoot) {
                lived = prevAlive - prevBoot;                  // 0 也是短命，不是“没有历史”
            } else {
                lived = 0L;                                    // 数据倒挂，按最坏算
            }

            if (wasSafe) {
                // 上次就已经闭嘴了，系统还是这样 → 元凶不在本模块，保持闭嘴等人工处理
                reason = value(p.getProperty("reason"));
            } else if (!hasHistory || lived >= ALIVE_OK_MS) {
                boots = 1;                       // 首次 / 上次活得够久 → 正常会话
            } else {
                boots = boots + 1;               // 上次刚起来就没了 → 连击
            }

            boolean tripped = wasSafe || boots >= SAFE_BOOTS;
            if (tripped && !wasSafe) {
                safeSince = now;
                reason = "系统界面疑似崩溃循环：上次进程仅存活 "
                        + Math.max(0L, lived / 1000L) + "s，已连续第 " + boots + " 次";
            }
            safe = tripped;

            bootTime = now;
            lastAlive = now;
            p.setProperty("bootTime", String.valueOf(now));
            p.setProperty("lastAlive", String.valueOf(now));
            p.setProperty("boots", String.valueOf(boots));
            p.setProperty("safe", safe ? "1" : "0");
            p.setProperty("safeSince", String.valueOf(safeSince));
            p.setProperty("reason", reason);
            // 同步写：进程可能马上就没了，异步会把这一次的记录丢掉，连击就永远累不起来
            save(file, p);
            ready = true;
            return safe;
        } catch (Throwable t) {
            // 判据本身出任何问题都不能影响模块
            return false;
        }
    }

    /** 本次该不该装 hook。evaluate 没跑成时恒为 true（退化成“没有安全模式”）。 */
    static boolean enabled() {
        return !safe;
    }

    static boolean isSafe() {
        return safe;
    }

    static long safeSince() {
        return safeSince;
    }

    /** 给 App 和 LSPosed 日志看的一句话。 */
    static String statusText() {
        if (!safe) {
            return "安全模式：未触发（进程存活判定正常）";
        }
        return "安全模式已启用：本模块本次未安装任何 hook。原因 —— "
                + reason
                + "。在 App 里点「退出安全模式」，再重启系统界面即可恢复。";
    }

    /**
     * 主线程心跳，只在非安全模式下打。
     *
     * <p>安全模式下模块已经闭嘴，进程死活不再反映本模块的行为，记了也没意义 ——
     * 而且 {@code wasSafe} 分支本来就不看存活时长。
     */
    static void startHeartbeat() {
        if (!ready || safe) {
            return;
        }
        try {
            final Handler h = new Handler(Looper.getMainLooper());
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (safe) {
                        return;   // 中途进了安全模式就停
                    }
                    lastAlive = System.currentTimeMillis();
                    persistAlive();
                    h.postDelayed(this, BEAT_MS);
                }
            });
        } catch (Throwable ignored) {
            // 心跳挂不上只是判据退化，不影响功能
        }
    }

    /** 把心跳时间戳落到状态文件；丢到 {@link LockConfig#io}，主线程不碰磁盘。 */
    private static void persistAlive() {
        final long alive = lastAlive;
        final File f = file;
        if (f == null) {
            return;
        }
        LockConfig.io(() -> {
            synchronized (SafetyGate.class) {
                try {
                    Properties p = load(f);
                    p.setProperty("lastAlive", String.valueOf(alive));
                    save(f, p);
                } catch (Throwable ignored) {
                    // 心跳写失败无所谓：最多让下一次存活判定偏保守
                }
            }
        });
    }

    /**
     * 处理 App 发来的退出请求（走远端配置，由 {@code LockConfig} 的监听转进来）。
     *
     * @param request App 写下的时间戳
     * @return {@code true} = 本次已退出（<b>要重启系统界面才会把 hook 装回去</b>）
     */
    static synchronized boolean tryExit(long request) {
        if (!safe || request <= 0L || request <= safeSince) {
            return false;
        }
        safe = false;
        reason = "";
        boots = 0;
        safeSince = 0;
        try {
            if (file != null) {
                Properties p = load(file);
                p.setProperty("safe", "0");
                p.setProperty("boots", "0");
                p.setProperty("safeSince", "0");
                p.setProperty("reason", "");
                save(file, p);
            }
        } catch (Throwable ignored) {
            // 文件写失败也照样以内存状态为准退出；最坏是下次启动重新判一次
        }
        return true;
    }

    // ------------------------------------------------------------------ 文件

    /** 读不出来就当没有历史：从 0 开始记，绝不因为读坏而拒绝启动。 */
    private static Properties load(File f) {
        Properties p = new Properties();
        try {
            if (f.isFile()) {
                try (InputStream in = new FileInputStream(f)) {
                    p.load(in);
                }
            }
        } catch (Throwable ignored) {
            // 读坏 = 无历史
        }
        return p;
    }

    private static void save(File f, Properties p) throws Exception {
        try (OutputStream out = new FileOutputStream(f)) {
            p.store(out, "LockBar safety state");
        }
    }

    private static long num(Properties p, String key, long def) {
        try {
            String s = p.getProperty(key);
            return s == null ? def : Long.parseLong(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String value(String s) {
        return s == null ? "" : s;
    }
}
