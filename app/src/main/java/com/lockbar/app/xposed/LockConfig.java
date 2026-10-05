package com.lockbar.app.xposed;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedInterface;

/**
 * SystemUI 侧的配置读取（{@code Prefs.GROUP} 远端配置）。
 *
 * 每次改动都整组重读，保证 App 里改的开关能立刻作用到锁屏。
 */
final class LockConfig implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String GROUP = "lockbar_settings";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SHOW_HANDLE = "show_handle";
    private static final String KEY_PARALLAX = "parallax";
    private static final String KEY_HANDLE_ALPHA = "handle_alpha";
    private static final String KEY_DRAG_RATIO = "drag_ratio";
    /** 速度解锁（1.0.22）：松手时上滑速度达标就直接进解锁。 */
    private static final String KEY_FLICK_UNLOCK = "flick_unlock";
    /** 速度解锁的触发阈值（dp/s）。 */
    private static final String KEY_FLICK_VELOCITY = "flick_velocity";
    /** 解锁动画时长（ms）：触发解锁后条从松手位置飞到屏幕顶所用的时间。 */
    private static final String KEY_UNLOCK_MS = "unlock_ms";
    private static final String KEY_ARC_ENABLED = "arc_enabled";
    /** true = iOS 式压暗（压弧线以下露出的区域，靠明暗对比出弧线），false = 传统描边光边。 */
    private static final String KEY_ARC_DIM = "arc_dim";
    /** 压暗强度 0..1（固定值，不随上滑进度递增；只作用于压暗模式），0.57 = 旧版固定 145/255 那档。 */
    private static final String KEY_DIM_STRENGTH = "dim_strength";
    /** 压暗哪一半：false = 弧线下方（默认）、true = 弧线上方的锁屏内容。 */
    private static final String KEY_DIM_UPPER = "dim_upper";
    /** 液态玻璃实时渲染弧形描边（1.0.22）。 */
    private static final String KEY_GLASS_LIVE = "glass_live";
    /** 玻璃带厚度（dp）：0 = 跟随 arc_width（默认），>0 = 以弧线为轴的独立宽度，白描边不受影响。 */
    private static final String KEY_GLASS_THICK = "glass_thick";
    /** 上滑时背景整体高斯模糊（1.0.22）。 */
    private static final String KEY_BLUR_ENABLED = "blur_enabled";
    /** 模糊程度 0..1：随上滑进度连续渐强，满强度 = 20dp 半径。 */
    private static final String KEY_BLUR_STRENGTH = "blur_strength";
    // 「玻璃参数」页的九项。默认值不在这里写死 —— 统一取 LockGlassStrip.K_*，
    // 那组常量同时是玻璃 strip 的字段初值，所以界面显示的默认值和实际下发的
    // 默认值永远是同一个数，不存在两处各写一份、改了一处忘了另一处的可能。
    private static final String KEY_GLASS_DIFFUSE = "glass_diffuse";
    private static final String KEY_GLASS_BLUR = "glass_blur";
    private static final String KEY_GLASS_REFRACT = "glass_refract";
    private static final String KEY_GLASS_EDGE_REF = "glass_edge_ref";
    private static final String KEY_GLASS_REFRACT_W = "glass_refract_w";
    private static final String KEY_GLASS_FRESNEL = "glass_fresnel";
    private static final String KEY_GLASS_HIGHLIGHT = "glass_highlight";
    private static final String KEY_GLASS_HL_WIDTH = "glass_hl_width";
    private static final String KEY_GLASS_OPACITY = "glass_opacity";
    private static final String KEY_ARC_WIDTH = "arc_width";
    private static final String KEY_ARC_ALPHA = "arc_alpha";
    private static final String KEY_ARC_COLOR = "arc_color";
    private static final String KEY_HAPTIC = "haptic";
    private static final String KEY_RELEASE_RETURN = "release_return";
    private static final String KEY_INTERCEPT_SWIPE = "intercept_swipe";
    /** 扩大触控范围：触控盒向左右扩展到屏幕两边。 */
    private static final String KEY_HANDLE_WIDE = "handle_wide";
    private static final String KEY_HINT_TEXT = "hint_text";
    /** 小横条上方文字的样式（独立设置页）。 */
    private static final String KEY_HINT_SIZE = "hint_size";
    private static final String KEY_HINT_WEIGHT = "hint_weight";
    private static final String KEY_HINT_FONT = "hint_font";
    private static final String KEY_HINT_COLOR = "hint_color";
    private static final String KEY_HINT_GAP = "hint_gap";
    private static final String KEY_HINT_OFFSET_X = "hint_offset_x";

    /**
     * 安全模式退出请求（App 写时间戳 → hook 读）。
     *
     * <p><b>这是唯一一条 hook 侧需要“反向感知”的配置</b>，所以走远端配置而不是 Provider：
     * 远端配置本来就是 App 能写、hook 能读的通道（{@code getRemotePreferences} 对 hook 进程
     * 只读，恰好单向够用），而且 {@link #onSharedPreferenceChanged} 会立刻把它推给 listener，
     * 不用轮询。
     *
     * <p><b>类型红线</b>：App 侧 {@code Prefs.putLong}，这里读 {@link #getLng} —— 两侧必须
     * 都是 Long，否则就是 v1.0.15 那种 {@code ClassCastException} 的翻版。
     */
    private static final String KEY_SAFETY_EXIT = "safety_exit";

    /**
     * 一键清空模块日志（App 写时间戳 → hook 清 {@link ModuleLog} 环形缓冲）。
     *
     * <p>与 {@link #KEY_SAFETY_EXIT} 完全同款的反向通道：远端配置 App 能写、hook 能读，
     * {@link #onSharedPreferenceChanged} 值一变就叫醒，不用轮询。展示副本（{@code debug_log}）
     * 由 App 侧自己删，这边只负责进程内缓冲 —— 两头都清才算一键清空。
     *
     * <p><b>类型红线</b>：App 侧 {@code Prefs.putLong}，这里不读值（事件即指令），但
     * {@code syncToFramework} 的 Long 分支要求两侧类型一致，故仍约定 Long。
     */
    private static final String KEY_LOG_CLEAR = "log_clear";

    /** 文字颜色默认值：与旧版本保持一致。 */
    static final int DEFAULT_HINT_COLOR = 0xD9FFFFFF;

    /**
     * 是否处于安全模式（{@code "1"} / {@code "0"}），写给 App 首页判断要不要显示退出按钮。
     *
     * <p>与其它 {@code debug_} 键一样只进不回：以 {@code debug_} 开头，不会触发整组重载。
     */
    static final String KEY_DEBUG_SAFETY = "debug_safety";

    /** SystemUI 写回 App 展示的诊断信息（不是配置，改动时不能触发重载）。 */
    static final String KEY_DEBUG_STATUS = "debug_status";
    static final String KEY_DEBUG_VIEWS = "debug_views";
    /** 拦截系统上滑的计数；单独一条 key，免得把 unlock= 那条覆盖掉。 */
    static final String KEY_DEBUG_BLOCK = "debug_block";
    /**
     * 模块运行日志（时间线），App 的「模块日志」页读这一条。
     *
     * <p>必须以 {@code debug_} 开头：{@link #onSharedPreferenceChanged} 靠这个前缀忽略
     * 自己写回去的诊断数据，否则每写一行日志就触发一次整组 reload，会变成无限重载。
     */
    static final String KEY_DEBUG_LOG = "debug_log";

    /**
     * 诊断数据回传通道的 authority。App 侧 {@code DiagProvider} 必须与之一致。
     *
     * <p>见 {@link #putDebug}：远端配置对 hook 进程是只读的，这条走不通。
     */
    private static final String DIAG_AUTHORITY = "io.github.miyupyxh.liftarc.diag";
    private static final String DIAG_EXTRA_VALUE = "value";

    /**
     * 回传诊断数据专用的单线程。
     *
     * <p>两个理由：① {@code ContentResolver.call} 在目标进程没起来时会阻塞等它冷启动，
     * 而第一次 flush 正好落在 SystemUI 主线程的 {@code attachBaseContext} 里，同步调
     * 等于拿 SystemUI 启动时间去等另一个 App；② 单线程保证整段日志按提交顺序落盘不乱序。
     */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LockBar-diag");
        t.setDaemon(true);
        return t;
    });

    /**
     * 把任务丢到上面那个线程上。
     *
     * <p>{@link SafetyGate} 的心跳写盘也复用它 —— 安全模式本身是“出事以后”的状态，
     * 再为它单开一条线程只会让“闭嘴”这件事变贵。
     */
    static void io(Runnable r) {
        try {
            IO.execute(r);
        } catch (Throwable ignored) {
            // 线程池拒收只意味着少落一次盘，不影响功能
        }
    }

    /** 可选字体数（系统默认 / 衬线 / 等宽 / 手写 / 窄体）。 */
    static final int HINT_FONT_COUNT = 5;

    interface Listener {
        void onConfigChanged(LockConfig config);
    }

    private final XposedInterface module;
    private SharedPreferences prefs;
    private Listener listener;
    /**
     * 上一次成功写出去的诊断值；远端读不到（只读），只能自己记。
     *
     * <p>必须是并发容器：读在调用线程（主线程），写在 {@link #IO} 线程，
     * 裸 HashMap 两边同时碰有损坏风险。
     */
    private final Map<String, String> lastDebug = new ConcurrentHashMap<>();

    boolean enabled = true;
    boolean showHandle = true;
    boolean parallax = true;
    boolean haptic = true;
    boolean releaseReturn = true;
    boolean arcEnabled = true;
    /** iOS 式压暗（新默认）；关掉退回只画描边光边。 */
    boolean arcDim = true;
    /** 拦截系统自己的“上滑解锁”，只让小白条接管上滑。 */
    boolean interceptSwipe;
    /** 扩大触控范围：触控盒向左右扩展到屏幕两边。 */
    boolean handleWide;
    /** 小白条上方的自定义文字，空 = 不显示。 */
    String hintText = "";
    float handleAlpha = 1f;
    float dragRatio = 0.85f;
    /** 速度解锁：松手时上滑够快就直接进解锁，与「进度 ≥ 0.5」并列，互不取代。 */
    boolean flickUnlock = true;
    /** 速度解锁的触发阈值（dp/s）。 */
    float flickVelocity = 1200f;
    /** 解锁动画时长（ms）。默认 340 = 上一轮写死的那个值，现改成可调。 */
    float unlockMs = 340f;
    float arcWidth = 3f;
    float arcAlpha = 1f;
    /** 压暗强度 0..1（固定值直接套用，不随进度递增；只作用于压暗模式）。 */
    float dimStrength = 0.57f;
    /** 压暗哪一半：false = 弧线下方露出的区域（默认），true = 弧线上方的锁屏内容。 */
    boolean dimUpper;
    /** 液态玻璃实时渲染弧形描边：true = 沿弧线交给系统 MiGlass 实时渲染（1.0.22）。 */
    boolean glassLive;
    /** 玻璃带厚度（dp）：1..40，缺省 20，与 arcWidth 完全无关。 */
    float glassThick;
    /** 上滑时背景模糊：true = 上滑过程给内容树挂高斯 RenderEffect（1.0.22）。 */
    boolean blurEnabled;
    /** 模糊程度 0..1：随上滑进度连续渐强，满强度 = 20dp 半径。 */
    float blurStrength = 0.7f;
    // ------------------------------------------------「玻璃参数」页的九项
    // 顺序必须与 LockGlassStrip.setGlassTuning 的形参顺序一致：
    // diffuse / blurPx / refract / edgeRef / refractW / fresnel / highlight / hlWidth / opacity
    /** 漫反射 diffuseStrength 0..1。 */
    float glassDiffuse = LockGlassStrip.K_DIFFUSE;
    /** 背景模糊基准 blurRadius（物理像素）0..20。 */
    float glassBlur = LockGlassStrip.K_BLUR_PX;
    /** 折射强度 refractionStrength 0..0.90 —— 上限卡在 0.90，见下。 */
    float glassRefract = LockGlassStrip.K_REFRACT;
    /** 边缘折射 edgeRefraction 0..0.50 —— 与上面那个上限成对，两者不能一起拉满。 */
    float glassEdgeRef = LockGlassStrip.K_EDGE_REFRACT;
    /** 折射范围 refractionWidth 0..1。 */
    float glassRefractW = LockGlassStrip.K_REFRACT_W;
    /** 菲涅尔 fresnelStrength 0..1。 */
    float glassFresnel = LockGlassStrip.K_FRESNEL;
    /** 边缘高光 highlightIntensity 0..1。 */
    float glassHighlight = LockGlassStrip.K_HIGHLIGHT;
    /** 高光宽度 highlightWidth 0.30..1.50。 */
    float glassHlWidth = LockGlassStrip.K_HL_WIDTH;
    /** 玻璃整体不透明度 opacity 0.20..1 —— 下限 0.20，调 0 等于关掉玻璃。 */
    float glassOpacity = LockGlassStrip.K_OPACITY;
    int arcColor = Color.WHITE;
    /** 小横条上方文字：大小(sp) / 粗细 / 字体索引 / 颜色 / 与条的间距(dp) / 水平偏移(dp)。 */
    float hintSize = 12f;
    int hintWeight = 400;
    int hintFont;
    int hintColor = DEFAULT_HINT_COLOR;
    float hintGap = 10f;
    float hintOffsetX;
    /** 安全模式退出请求：App 写下的时间戳，{@code 0} = 没有请求。 */
    long safetyExit;

    LockConfig(XposedInterface module) {
        this.module = module;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 安全模式退出请求的时间戳；{@code 0} = 没有请求。见 {@link #KEY_SAFETY_EXIT}。 */
    long safetyExit() {
        return safetyExit;
    }

    void load() {
        try {
            prefs = module.getRemotePreferences(GROUP);
            if (prefs != null) {
                prefs.registerOnSharedPreferenceChangeListener(this);
            }
        } catch (Throwable t) {
            module.log(5, "LockBar", "failed to load remote prefs", t);
            prefs = null;
        }
        reload();
        if (prefs != null) {
            Listener l = listener;
            if (l != null) {
                l.onConfigChanged(this);
            }
        }
    }

    /** 远端配置是否已经拿到（LSPosed 未就绪时会返回 false，需要稍后重试）。 */
    boolean isReady() {
        return prefs != null;
    }

    private void reload() {
        SharedPreferences p = prefs;
        if (p == null) {
            // 退化到默认值
            enabled = true;
            showHandle = true;
            parallax = true;
            haptic = true;
            releaseReturn = true;
            arcEnabled = true;
            arcDim = true;
            interceptSwipe = false;
            handleWide = false;
            hintText = "";
            handleAlpha = 1f;
            dragRatio = 0.85f;
            flickUnlock = true;
            flickVelocity = 1200f;
            unlockMs = 340f;
            arcWidth = 3f;
            arcAlpha = 1f;
            dimStrength = 0.57f;
            dimUpper = false;
            glassLive = false;
            blurEnabled = false;
            blurStrength = 0.7f;
            glassThick = 20f;
            glassDiffuse = LockGlassStrip.K_DIFFUSE;
            glassBlur = LockGlassStrip.K_BLUR_PX;
            glassRefract = LockGlassStrip.K_REFRACT;
            glassEdgeRef = LockGlassStrip.K_EDGE_REFRACT;
            glassRefractW = LockGlassStrip.K_REFRACT_W;
            glassFresnel = LockGlassStrip.K_FRESNEL;
            glassHighlight = LockGlassStrip.K_HIGHLIGHT;
            glassHlWidth = LockGlassStrip.K_HL_WIDTH;
            glassOpacity = LockGlassStrip.K_OPACITY;
            arcColor = Color.WHITE;
            hintSize = 12f;
            hintWeight = 400;
            hintFont = 0;
            hintColor = DEFAULT_HINT_COLOR;
            hintGap = 10f;
            hintOffsetX = 0f;
            safetyExit = 0L;
            return;
        }
        // 全部走下面那组容错 getter：远端配置是**原样存类型**的，
        // SharedPreferences 遇到类型不符会直接抛 ClassCastException —— 而 install() 就卡在
        // 这一步，一旦抛出去 = 一个 hook 都装不上 = 整个模块 0 效果（v1.0.15 真机就是这么挂的）。
        try {
            enabled = getBool(p, KEY_ENABLED, true);
            showHandle = getBool(p, KEY_SHOW_HANDLE, true);
            parallax = getBool(p, KEY_PARALLAX, true);
            haptic = getBool(p, KEY_HAPTIC, true);
            releaseReturn = getBool(p, KEY_RELEASE_RETURN, true);
            arcEnabled = getBool(p, KEY_ARC_ENABLED, true);
            arcDim = getBool(p, KEY_ARC_DIM, true);
            interceptSwipe = getBool(p, KEY_INTERCEPT_SWIPE, false);
            handleWide = getBool(p, KEY_HANDLE_WIDE, false);
            hintText = value(getStr(p, KEY_HINT_TEXT, ""));
            handleAlpha = clamp(getFlt(p, KEY_HANDLE_ALPHA, 1f), 0.05f, 1f);
            dragRatio = clamp(getFlt(p, KEY_DRAG_RATIO, 0.85f), 0.2f, 1f);
            flickUnlock = getBool(p, KEY_FLICK_UNLOCK, true);
            flickVelocity = clamp(getFlt(p, KEY_FLICK_VELOCITY, 1200f), 300f, 3000f);
            // 下限 150：再短就比一帧帧的手指反应还快，观感是「瞬移」；上限 800 已经很拖沓
            unlockMs = clamp(getFlt(p, KEY_UNLOCK_MS, 340f), 150f, 800f);
            arcWidth = clamp(getFlt(p, KEY_ARC_WIDTH, 3f), 0.5f, 20f);
            arcAlpha = clamp(getFlt(p, KEY_ARC_ALPHA, 1f), 0.05f, 1f);
            dimStrength = clamp(getFlt(p, KEY_DIM_STRENGTH, 0.57f), 0.05f, 1f);
            dimUpper = getBool(p, KEY_DIM_UPPER, false);
            glassLive = getBool(p, KEY_GLASS_LIVE, false);
            blurEnabled = getBool(p, KEY_BLUR_ENABLED, false);
            blurStrength = clamp(getFlt(p, KEY_BLUR_STRENGTH, 0.7f), 0f, 1f);
            // 必须走 getFlt：远端存类型原样，直接 getString 会 ClassCastException 整模块报废
            glassThick = clamp(getFlt(p, KEY_GLASS_THICK, 20f), 1f, 40f);
            // 「玻璃参数」页九项。
            //
            // 折射强度上限 0.90 与边缘折射上限 0.50 是**成对**的一条安全线：
            // 采样雅可比 = 1 - refractK * (0.82 * (1 + 0.36 * edgeRef) + 0.10)，
            // 两条都拉满时 = 1 - 0.90 * 1.0676 = 0.039 > 0，恰好不折叠；
            // 再往上背景就会对折过来，表现为重复 / 幽灵纹理（1.0.21 排查过的那个）。
            // 所以这里宁可让滑条到顶也绝不越界，而不是相信「用户不会两个都拉满」。
            glassDiffuse = clamp(getFlt(p, KEY_GLASS_DIFFUSE, LockGlassStrip.K_DIFFUSE), 0f, 1f);
            glassBlur = clamp(getFlt(p, KEY_GLASS_BLUR, LockGlassStrip.K_BLUR_PX), 0f, 20f);
            glassRefract = clamp(getFlt(p, KEY_GLASS_REFRACT, LockGlassStrip.K_REFRACT), 0f, 0.90f);
            glassEdgeRef = clamp(getFlt(p, KEY_GLASS_EDGE_REF, LockGlassStrip.K_EDGE_REFRACT), 0f, 0.50f);
            glassRefractW = clamp(getFlt(p, KEY_GLASS_REFRACT_W, LockGlassStrip.K_REFRACT_W), 0f, 1f);
            glassFresnel = clamp(getFlt(p, KEY_GLASS_FRESNEL, LockGlassStrip.K_FRESNEL), 0f, 1f);
            glassHighlight = clamp(getFlt(p, KEY_GLASS_HIGHLIGHT, LockGlassStrip.K_HIGHLIGHT), 0f, 1f);
            glassHlWidth = clamp(getFlt(p, KEY_GLASS_HL_WIDTH, LockGlassStrip.K_HL_WIDTH), 0.30f, 1.50f);
            glassOpacity = clamp(getFlt(p, KEY_GLASS_OPACITY, LockGlassStrip.K_OPACITY), 0.20f, 1f);
            arcColor = getIn(p, KEY_ARC_COLOR, Color.WHITE);
            hintSize = clamp(getFlt(p, KEY_HINT_SIZE, 12f), 6f, 40f);
            // hint_weight 由 App 侧 rememberPrefInt 写入 → 远端存的是 Integer，
            // 所以这里读 int（原来读 float，正是 v1.0.15 那次整模块报废的元凶）
            hintWeight = Math.round(clamp(getIn(p, KEY_HINT_WEIGHT, 400), 100f, 900f) / 100f) * 100;
            hintFont = Math.max(0, Math.min(HINT_FONT_COUNT - 1, getIn(p, KEY_HINT_FONT, 0)));
            hintColor = getIn(p, KEY_HINT_COLOR, DEFAULT_HINT_COLOR);
            hintGap = clamp(getFlt(p, KEY_HINT_GAP, 10f), 0f, 96f);
            hintOffsetX = clamp(getFlt(p, KEY_HINT_OFFSET_X, 0f), -200f, 200f);
            // 控制类键，不参与界面：类型两侧都约定为 Long（见 KEY_SAFETY_EXIT 注释）
            safetyExit = getLng(p, KEY_SAFETY_EXIT, 0L);
        } catch (Throwable t) {
            // 兜底：下面那几个 getter 已保证单键不抛，这里防的是将来有人加新读法忘了走 helper。
            // 无论发生什么都不能让配置读取把 install() 顶掉 —— 那等于整个模块 0 效果。
            module.log(5, "LockBar", "reload failed, keep whatever we got: " + t, t);
        }
    }

    /**
     * 读原始值，配合下面几个 getter 做类型降级。
     *
     * <p>用 {@link SharedPreferences#getAll()} 是因为它一次性把整张表拿出来（内部有缓存），
     * 类型不符时也不会抛，正好用来在 {@code getFloat} 炸掉之后接管。
     */
    private static Object raw(SharedPreferences p, String key) {
        try {
            return p.getAll().get(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 读浮点；历史版本/其它写入方把值存成 Integer、Long 时按 {@link Number} 降级转换。
     *
     * <p>绝不向外抛 —— 这是整个模块能不能装上的前提。
     */
    private static float getFlt(SharedPreferences p, String key, float def) {
        try {
            return p.getFloat(key, def);
        } catch (Throwable ignored) {
            // 落到下面的类型降级
        }
        Object v = raw(p, key);
        return v instanceof Number ? ((Number) v).floatValue() : def;
    }

    /** 读整型；值被存成 Float/Long 时降级转换。绝不向外抛。 */
    private static int getIn(SharedPreferences p, String key, int def) {
        try {
            return p.getInt(key, def);
        } catch (Throwable ignored) {
            // 落到下面的类型降级
        }
        Object v = raw(p, key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    /** 读布尔；类型不符时退回默认值。绝不向外抛。 */
    private static boolean getBool(SharedPreferences p, String key, boolean def) {
        try {
            return p.getBoolean(key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    /** 读 long；值被存成 Integer/Double 时降级转换。绝不向外抛。 */
    private static long getLng(SharedPreferences p, String key, long def) {
        try {
            return p.getLong(key, def);
        } catch (Throwable ignored) {
            // 落到下面的类型降级
        }
        Object v = raw(p, key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    /** 读字符串；类型不符时退回默认值。绝不向外抛。 */
    private static String getStr(SharedPreferences p, String key, String def) {
        try {
            String s = p.getString(key, def);
            return s != null ? s : def;
        } catch (Throwable ignored) {
            // 落到下面的类型降级
        }
        Object v = raw(p, key);
        return v instanceof String ? (String) v : def;
    }

    private static String value(String s) {
        return s == null ? "" : s;
    }

    /**
     * 把 SystemUI 侧的诊断信息写回 App，App 里能直接看到。
     *
     * <p><b>为什么不写远端配置了</b>：libxposed 官方 javadoc 明确写着
     * {@code getRemotePreferences} 「<b>read-only in hooked apps</b>」——
     * hook 进程里 {@code prefs.edit()} 会直接抛
     * {@code UnsupportedOperationException: Read only implementation}。
     * 也就是说「SystemUI 写回远端 → App 读」这条反向通道从 API 契约上就不成立，
     * 过去的 {@code debug_status} / {@code debug_log} 全都是写了个寂寞（真机日志里
     * 连续 5 次 {@code failed to write debug_log}）。现在改走 App 导出的 ContentProvider。
     *
     * <p>只在内容变化时才写：远端值读不到了，改成自己记一份上一次的值。
     * 拿不到 {@link Context}（{@code Application#attach} 还没回来）时直接跳过，
     * 等 {@code ModuleLog.setContext} 到手会补一次 flush。
     */
    void putDebug(String key, String text) {
        String v = value(text);
        try {
            if (v.equals(lastDebug.get(key))) {
                return;
            }
            if (ModuleLog.context() == null) {
                // 还没有 Context：不记 lastDebug，等 setContext 到手补 flush 时会重来
                return;
            }
            // 必须丢到自己的线程上：ContentResolver.call 在目标进程没起来时会**阻塞**
            // 去等它冷启动，而第一次 flush 正好发生在 SystemUI 主线程的 attachBaseContext 里
            // —— 同步调等于拿 SystemUI 启动时间去等另一个 App 冷启动。
            final String value = v;
            IO.execute(() -> {
                try {
                    Context ctx = ModuleLog.context();
                    if (ctx == null) {
                        return;
                    }
                    Bundle extras = new Bundle();
                    extras.putString(DIAG_EXTRA_VALUE, value);
                    ctx.getContentResolver().call(
                            Uri.parse("content://" + DIAG_AUTHORITY), key, null, extras);
                    lastDebug.put(key, value);
                } catch (Throwable t) {
                    module.log(4, "LockBar", "failed to write " + key, t);
                }
            });
        } catch (Throwable t) {
            module.log(4, "LockBar", "failed to write " + key, t);
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key != null && key.startsWith("debug_")) {
            return;   // 诊断信息是我们自己写回去的，不是配置
        }
        if (KEY_LOG_CLEAR.equals(key)) {
            // 一键清空：App 已删展示副本，这里清进程内缓冲（不清则下次 flush 又灌回去）
            ModuleLog.clear();
        }
        reload();
        Listener l = listener;
        if (l != null) {
            l.onConfigChanged(this);
        }
    }
}
