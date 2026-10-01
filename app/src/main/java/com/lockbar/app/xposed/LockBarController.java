package com.lockbar.app.xposed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import io.github.libxposed.api.XposedInterface;

/**
 * 锁屏上滑联动控制器：把 {@code keyguard_root_view} / {@code shared_notification_container}
 * 整体上移，同时按屏幕圆角裁切内容底边、沿底边绘制弧形描边，露出下层壁纸。
 *
 * <p>所有 View 操作都发生在 SystemUI 主线程（hook 的 {@code onAttachedToWindow} /
 * {@code dispatchTouchEvent}、ViewTreeObserver 回调都在线程上），配置变更会先 {@code post} 回主线程。
 *
 * <p>位移与裁切（几何手法沿用 HyperBetter 的 {@code ShadePullGlass}）：
 * 拉满进度时内容上移固定为 {@code height * MAX_SHIFT_RATIO}，<b>与跟手参数无关</b> ——
 * dragRatio 只改行程：{@code 行程 = MAX_SHIFT_RATIO×屏高 / followMul(dragRatio)}，
 * dragRatio=1 时行程恰好等于位移，手指动多少内容动多少（1:1 完全贴手），
 * 调小则行程变短、内容按 {@link #followMul} 倍率跑在手指前面；
 * 拉满行程时位移恒为 MAX_SHIFT_RATIO×屏高，不管 dragRatio 取多小内容都一定滑出屏幕，
 * 不会卡在半屏；
 * 可见底边 {@code edge = height - shift}；「上滑联动」开启时内容整体上移 {@code -shift}，
 * 裁切壳就是内容自身的 bounds + 屏幕圆角，经过 {@code translationY} 后圆角底边正好落在
 * {@code edge} 上；关闭时内容不动，裁切壳的底边直接抬到 {@code edge}，只靠裁切露出壁纸。
 * 描边则始终按窗口坐标下从 {@code edge - height} 到 {@code edge} 的圆角矩形绘制。
 */
final class LockBarController implements LockHandleView.Callback, LockConfig.Listener {

    private static final String TAG = "LockBar";
    private static final String PKG = "com.android.systemui";

    /** 松手回弹时长（与 HyperBetter 的 smoothstep 回弹一致）。 */
    private static final long RETURN_MS = 240L;
    /**
     * 小白条触控区高度（dp）。条本体只有 5dp、离底 7.5dp，
     * 触控区给到 36dp：条下方 7.5dp、上方 23dp、左右各 16dp，远超“条周围 5dp”。
     */
    private static final float HANDLE_HEIGHT_DP = 36f;
    /** 触控区左右比条本体各多出的宽度（dp）。 */
    private static final float HANDLE_H_PAD_DP = 16f;
    /** 松手时进度达到该值就直接进入系统解锁界面（仿 iOS）。 */
    private static final float UNLOCK_THRESHOLD = 0.5f;
    /** 进度到 1 时内容最大上移比例，避免可见区域被裁成 0。 */
    private static final float MAX_SHIFT_RATIO = 0.96f;

    /** {@link #onWindowIntercept}：交给系统原来的逻辑判断。 */
    static final int INTERCEPT_DEFAULT = 0;
    /** {@link #onWindowIntercept}：强制不拦截（保证小白条手势不被窗口抢走）。 */
    static final int INTERCEPT_NO = 1;
    /** {@link #onWindowIntercept}：强制拦截（拦掉系统自己的上滑解锁）。 */
    static final int INTERCEPT_YES = 2;

    /** {@link #onWindowTouchPre}：原样放行事件。 */
    static final int TOUCH_PASS = 0;
    /** {@link #onWindowTouchPre}：吞掉事件（不交给系统，本次上滑在系统眼里不存在）。 */
    static final int TOUCH_SWALLOW = 1;
    /**
     * {@link #onWindowTouchPre}：放行，但改写成 {@link MotionEvent#ACTION_CANCEL} 再放行。
     *
     * <p>只给“吞过 MOVE 的手势”的收尾用：子树收到的是 {@code DOWN → 无 MOVE → UP}，
     * 任何按事件流判手势的观察者（GestureDetector、可点子 View）都会当成一次单击而误触发；
     * 改发 {@code ACTION_CANCEL} 就是标准的“手势被收走”语义，观察者与子 View 都干净复位。
     */
    static final int TOUCH_PASS_AS_CANCEL = 2;
    /** 时间最多能被上移多少：自身高度的一半（仿 iOS 的“露一半”）。 */
    private static final float LIFT_MAX_RATIO = 0.5f;
    /**
     * 量到的“时间本体”高度下限（占屏高）。
     *
     * <p>有些模板的时/分视图在布局里是 0dp，量出来会是 0；卡个下限，保证上限至少是原来的
     * {@code 屏高 × 0.1 × 0.5}。
     */
    private static final float LIFT_SPAN_MIN_RATIO = 0.1f;
    /** 量到的“时间本体”高度上限（占屏高），免得把整屏都当成时间。 */
    private static final float LIFT_SPAN_MAX_RATIO = 0.25f;
    /** 时间回弹动画时长。 */
    private static final long LIFT_RETURN_MS = 460L;
    /** 时间回弹的过冲量（>1 会先越过原位再弹回，就是那个“弹性”）。 */
    private static final float LIFT_OVERSHOOT = 2f;

    private static final Interpolator SMOOTHSTEP = LockGlass::smoothstep;

    private static LockBarController sInstance;

    /** 锁屏状态：-1 未知（还没收到回调），0 不在锁屏，1 在锁屏且未被遮挡。 */
    private static int sKeyguardState = -1;
    /** {@link #resolve()} 的重试上限，避免 id 不存在时每次布局都全树查找。 */
    private static final int MAX_RESOLVE_TRY = 5;
    /** {@link #resolveClock()} 的重试上限（时间容器可能比锁屏其它部分晚出现）。 */
    private static final int MAX_CLOCK_TRY = 200;
    /**
     * 副层时间容器（{@code miui_keyguard_foreground_clock_container}）的重试上限。
     *
     * <p>分层时钟模板里“分钟”会被拆到这一层，而非分层模板它永远是空的，所以要多给几次机会。
     */
    private static final int MAX_CLOCK_SECOND_TRY = 2000;
    /** 远端配置拉取失败时的重试上限。 */
    private static final int MAX_CONFIG_TRY = 10;

    private int resolveTry;
    private int configLoadTry;
    private PowerManager powerManager;

    /** 窗口根（{@code NotificationShadeWindowView}，本质是 FrameLayout）。 */
    private final ViewGroup window;
    private final LockConfig config;
    private final XposedInterface module;

    private FrameLayout overlay;
    private LockRimView rim;
    private LockHandleView handle;
    /** 小白条上方的自定义文字（配置 hint_text 为空时不显示）。 */
    private TextView hint;

    private View keyguardRoot;
    private View sharedNotif;
    private View notificationPanel;
    private View bouncer;

    private LockGlass.Clip clipRoot = new LockGlass.Clip();
    private LockGlass.Clip clipNotif = new LockGlass.Clip();
    private LockGlass.Clip clipPanel = new LockGlass.Clip();

    /** 当前进度 0（复位）..1（拉满）。 */
    private float progress;
    /** 上一次 apply 的进度，用于避免重复写 translationY。 */
    private float lastApplied = Float.NaN;
    private boolean clipInstalled;
    /**
     * 壁纸/景深层的原始 translationY（首次反向补偿时记录，复位时还原）。
     * key=候选 View（身份语义），value=收集时刻它自己的 translationY。
     */
    private final java.util.IdentityHashMap<View, Float> anchorBaseY =
            new java.util.IdentityHashMap<>();
    /** 本轮手势是否已收集过锚定候选（空结果也要置位，避免每帧重扫整棵树）。 */
    private boolean anchorCollected;

    private float rangePx = 1f;
    private float startProgress;
    private int lastTickBucket = -1;

    private float radiusCache;
    private ValueAnimator animator;

    // ---------------------------------------------------------------- 触摸拦截
    /** 本次手势的落点（窗口坐标）。 */
    private float downX;
    private float downY;
    /** 落点是否在小白条触控区里（是就绝不能被窗口拦截）。 */
    private boolean downInHandle;
    /** 已经决定要拦掉系统这次上滑。 */
    private boolean blockSystemSwipe;
    /**
     * 本次手势是否“归通知所有”——<b>只在 DOWN 判一次，整把不再变</b>；true = 这把手永不拦截。
     *
     * <p>以前这个判定挂在每个 MOVE 上重算，列表翻到底的一瞬间判定翻转，拦截在手势<b>中途</b>
     * 才生效，时间从此跟着手指上下蹿（反馈的“翻动通知时间上下滑动”）。现在按下那一刻定死。
     */
    private boolean gestureExempt;
    /** {@link ViewConfiguration#getScaledTouchSlop()}，懒加载。 */
    private int touchSlop;
    /** 本次手势是否已经 dump 过视图树（诊断遮罩用）。 */
    private boolean viewsDumped;
    /** 本窗口已经成功拦截过多少次系统上滑（诊断用）。 */
    private int blockCount;

    // ---------------------------------------------------------------- 时间上移（仿 iOS）
    /** 时间容器（{@code miui_keyguard_clock_container}），上移就是改它的 translationY。 */
    private View clockRoot;
    /**
     * 分层时钟的副层容器（{@code miui_keyguard_foreground_clock_container}）。
     *
     * <p>一部分模板（{@code oversize_a / classic_max / all_in_one / eastern_* …}）会把
     * <b>分钟</b>那半张表拆到另一层，见 {@code MiuiKeyguardUtils#SECONDARY_LAYER_CLOCK_TEMPLATE_ID}。
     * 只抬主层的话就变成“时在动、分不动”——必须两个一起抬。
     */
    private View clockSecond;
    /** 时间本体（{@code clock_container1}），用来算“只能上移一半”的上限。 */
    private View clockTime;
    private int clockTry;
    private int clockSecondTry;
    /** 当前上移距离（px），0 = 归位。 */
    private float lift;
    /** 手指还按着、正在跟手。 */
    private boolean liftTracking;
    /** 时间容器被我们改动前的原始 translationY（系统可能自己也在写）。 */
    private float clockBaseY = Float.NaN;
    private float clockSecondBaseY = Float.NaN;
    private ValueAnimator liftAnimator;

    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
            sync();
        }
    };

    private LockBarController(ViewGroup window, LockConfig config, XposedInterface module) {
        this.window = window;
        this.config = config;
        this.module = module;
    }

    // ------------------------------------------------------------------ 入口

    static void onWindowAttached(Object w, LockConfig config, XposedInterface module) {
        // Context 的第二道兜底：万一 attachBaseContext 那个 hook 在这台 ROM 上没挂住，
        // 只要窗口起来了就还有一次机会（代价是安装阶段那几条日志会晚到）。
        if (w instanceof android.view.View) {
            try {
                ModuleLog.setContext(((android.view.View) w).getContext());
            } catch (Throwable ignored) {
                // 拿不到也只是 App 内看不到日志
            }
        }
        LockBarController c = obtain(w, config, module);
        if (c == null) {
            return;
        }
        c.ensureInstalled();
        c.sync();
    }

    /**
     * {@code NotificationShadeWindowView#dispatchTouchEvent} 前置回调。
     *
     * <p>这是整棵视图树最外层的入口：{@code requestDisallowInterceptTouchEvent} 影响不到它，
     * 所以在这里把事件直接吞掉，系统那条“上滑解锁”就<b>根本不会发生</b> ——
     * 这比在 {@code onInterceptTouchEvent} 里改返回值可靠得多（反馈里“拦截没用”就是卡在那一步）。
     *
     * @return {@link #TOUCH_PASS} 原样放行；{@link #TOUCH_SWALLOW} 吞掉；
     *     {@link #TOUCH_PASS_AS_CANCEL} 改写成 {@link MotionEvent#ACTION_CANCEL} 后放行
     */
    static int onWindowTouchPre(Object w, LockConfig config, MotionEvent ev) {
        if (w == null || ev == null) {
            return TOUCH_PASS;
        }
        LockBarController c = sInstance;
        if (c == null || c.window != w) {
            return TOUCH_PASS;
        }
        return c.preTouch(ev);
    }

    /** {@code NotificationShadeWindowView#dispatchTouchEvent} 后置回调（事件已交给系统）。 */
    static void onWindowTouchPost(Object w, LockConfig config) {
        LockBarController c = obtain(w, config, null);
        if (c == null) {
            return;
        }
        c.ensureInstalled();
        c.sync();
    }

    /**
     * {@code NotificationShadeWindowView#onInterceptTouchEvent} 回调。
     *
     * <p>只做一件事：落点在小白条触控区里 → 强制不拦截，保证这条手势一定由小白条接管。
     * （拦掉系统上滑已经改到 {@link #onWindowTouchPre} 里了。）
     *
     * @return {@link #INTERCEPT_DEFAULT} / {@link #INTERCEPT_NO}
     */
    static int onWindowIntercept(Object w, LockConfig config, MotionEvent ev) {
        if (w == null || ev == null) {
            return INTERCEPT_DEFAULT;
        }
        LockBarController c = sInstance;
        if (c == null || c.window != w) {
            return INTERCEPT_DEFAULT;
        }
        return c.intercept(ev);
    }

    private int intercept(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downInHandle = inHandleBox(downX, downY);
                break;

            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                break;

            default:
                break;
        }
        // 小白条自己的手势：窗口任何时候都不许抢
        return downInHandle ? INTERCEPT_NO : INTERCEPT_DEFAULT;
    }

    /**
     * 事件进入系统之前先过一遍。
     *
     * <ul>
     *   <li>{@code DOWN} 只记落点，永远放行（放行才拿得到后续事件）；</li>
     *   <li>{@code MOVE} 真的成了“从下往上的解锁手势”就从此吞掉，系统那条上滑彻底消失；</li>
     *   <li>吞掉期间同步驱动“时间上移”，到时间高度一半就停住（禁止继续滑动）；</li>
     *   <li>{@code UP/CANCEL} 放行（让系统的状态标记正常清零），时间做弹性回弹。
     *       但本次手势<b>吞过 MOVE</b> 的话，{@code UP} 改写成 {@code ACTION_CANCEL}
     *       再放行：子树此前只收到 {@code DOWN} 和没超 slop 的 MOVE，收 {@code UP}
     *       会被观察事件流的一方（如锁屏歌词模块的 GestureDetector）判成单击而误触发，
     *       改收 {@code CANCEL} 则是标准的“手势被收走”，各方干净复位；</li>
     * </ul>
     *
     * @return {@link #TOUCH_PASS} / {@link #TOUCH_SWALLOW} / {@link #TOUCH_PASS_AS_CANCEL}
     */
    private int preTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downInHandle = inHandleBox(downX, downY);
                blockSystemSwipe = false;
                gestureExempt = resolveGestureExempt();
                // 上一次手势如果还悬着（回弹中途又按下），先把时间放回原位
                clearLift();
                return TOUCH_PASS;

            case MotionEvent.ACTION_MOVE: {
                if (downInHandle) {
                    return TOUCH_PASS;
                }
                // 必须真的是“往上滑”才拦，否则点一下（带点抖动）也会被打断
                if (!blockSystemSwipe
                        && (downY - ev.getY()) > touchSlop()
                        && shouldBlockSystemSwipe()) {
                    blockSystemSwipe = true;
                    liftTracking = true;
                    blockCount++;
                    noteBlock("block#" + blockCount
                            + "@" + Math.round(downY * 100f / Math.max(1, window.getHeight()))
                            + "%");
                }
                if (blockSystemSwipe) {
                    updateLift(ev.getY());
                    return TOUCH_SWALLOW;    // 吞掉：系统这次上滑不存在
                }
                return TOUCH_PASS;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (liftTracking) {
                    liftTracking = false;
                    springLiftBack();
                }
                // 本手势吞过 MOVE：按“手势被收走”收尾，把 UP 改写成 CANCEL 放行（见方法注释）。
                // 否则（普通点击、落在触控区的手势、可滚动区的滑动）UP 原样放过去：
                // 面板只收到 DOWN+UP（没超过 slop 的 MOVE 都还给过它），
                // 既不会开始拖动，系统的 touchActive 等状态标记也能正常清零
                boolean swallowMove = blockSystemSwipe;
                blockSystemSwipe = false;
                if (swallowMove && ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    return TOUCH_PASS_AS_CANCEL;
                }
                return TOUCH_PASS;

            default:
                return TOUCH_PASS;
        }
    }

    private int touchSlop() {
        if (touchSlop <= 0) {
            try {
                touchSlop = android.view.ViewConfiguration
                        .get(window.getContext()).getScaledTouchSlop();
            } catch (Throwable t) {
                touchSlop = 16;
            }
        }
        return touchSlop;
    }

    /** 落点是否在小白条触控区（含跟随上移的 translationY）。 */
    private boolean inHandleBox(float x, float y) {
        if (handle == null || overlay == null || overlay.getVisibility() != View.VISIBLE) {
            return false;
        }
        float left = handle.getLeft() + handle.getTranslationX();
        float top = handle.getTop() + handle.getTranslationY();
        return x >= left && x <= left + handle.getWidth()
                && y >= top && y <= top + handle.getHeight();
    }

    /**
     * 是否该拦掉“系统自己的上滑解锁”。
     *
     * <p>范围是<b>整块锁屏</b>，只有小白条自己的触控区除外（那由 {@link #preTouch} 的
     * {@code downInHandle} 单独放行）——反馈要求“把拖动区域放到整个屏幕，盖掉系统的上滑解锁”，
     * 早前的“起点必须在下半屏 / 不能贴左右边”两条划区判断已按要求删除。
     *
     * <p>例外只有一条，在 {@code DOWN} 时一次性判死（{@link #resolveGestureExempt()}）：
     * 落点<b>压在通知行上</b>——那一把手势整个归通知，我们绝不插手（翻动、点击、侧滑删除
     * 都原样走系统）。空白区 / 时钟区一律拦：真机上那些区域的上滑 NSSL 不消费，
     * 不拦就漏给系统上滑解锁（v1.0.26 反馈）。
     */
    private boolean shouldBlockSystemSwipe() {
        if (!config.interceptSwipe || sKeyguardState == 0) {
            return false;
        }
        // 覆盖层可见 = 「在锁屏上 + 没弹密码盘 + 模块启用 + 屏幕亮着」，
        // 比单看 sKeyguardState 可靠（后者拿不到回调时一直是 -1）
        if (overlay == null || overlay.getVisibility() != View.VISIBLE) {
            return false;
        }
        if (bouncer != null && bouncer.getVisibility() == View.VISIBLE) {
            return false;   // 密码盘上的上滑是系统自己的，别碰
        }
        if (window.getWidth() <= 0 || window.getHeight() <= 0) {
            return false;
        }
        if (handle != null && progress > 0f) {
            return false;   // 我们自己正在拖
        }
        return !gestureExempt;
    }

    /**
     * 本次手势归不归通知所有——<b>只在 {@code DOWN} 判一次，整把手势不再变</b>。
     *
     * <p>判定只剩一条：按下点<b>压在通知行上</b>（整树 DFS 找 {@code ExpandableNotificationRow}）
     * → 这把手势整个归通知，永不拦截。原“底下列表还能翻也不拦”的 {@code hitsScrollable}
     * 豁免在 v1.0.27 删除：真机上<b>空白区</b>的上滑 NSSL 根本不消费，事件会直接漏给系统
     * 上滑解锁（1.0.26 实测“空白区域大概率触发系统的上滑”就是它放的行）；而压在通知上时
     * 列表自己会消费（1.0.26 已验证），行豁免一条就够。
     *
     * <p>判定顺序先便宜后贵：小白条手势 / 功能没开 / 确定不在锁屏 / 窗口没量好 / 覆盖层不可见
     * 都直接判“不拦”（这些情形下 {@link #shouldBlockSystemSwipe()} 本来也过不了）。
     */
    private boolean resolveGestureExempt() {
        if (downInHandle || !config.interceptSwipe || sKeyguardState == 0) {
            return true;
        }
        if (window == null || window.getWidth() <= 0 || window.getHeight() <= 0) {
            return true;   // 窗口还没量好：拿不准，宁可不拦
        }
        if (overlay == null || overlay.getVisibility() != View.VISIBLE) {
            return true;   // 覆盖层不可见 = 不在锁屏，本来就拦不了
        }
        boolean exempt = onNotificationRow(downX, downY);
        if (exempt) {
            // 诊断：走到这里豁免只可能来自“压在通知行上”。空白区若还漏拦，
            // 这条会给出漏拦落点的 Y%，直接判别是不是行判定误报
            ModuleLog.d("DOWN@" + Math.round(downY * 100f / Math.max(1, window.getHeight()))
                    + "%压通知行·不拦");
            ModuleLog.flush();
        }
        return exempt;
    }

    /**
     * 落点是不是压在一条通知上——<b>整棵窗口树按边界裁剪 DFS 找</b>，不走 {@link #topmostAt}。
     *
     * <p>窗口里在通知之上还摞着一层全屏空壳（消息容器、低亮动画、模糊层、控制中心壳、AOD 壳……）：
     * 真机分发遇到“不消费”的壳会掉到下面的兄弟，但 {@code topmostAt} 会卡在壳上把链条截断——
     * 1.0.25 就是这么漏的：链条里既数不到通知行也数不到 NSSL，两个豁免同时落空，
     * 于是通知手势也被拦、时钟跟着手指上下蹿、通知反而滚不动。
     * 这里换成带边界裁剪的 DFS：只要有一条<b>可见</b>通知行盖着落点，
     * 这把手势就归通知，{@link #shouldBlockSystemSwipe()} 永不认领。
     */
    private boolean onNotificationRow(float x, float y) {
        return rowAt(window, x, y, overlay);
    }

    private static boolean rowAt(ViewGroup root, float x, float y, View skip) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View c = root.getChildAt(i);
            if (c == skip || c.getVisibility() != View.VISIBLE || c.getAlpha() <= 0f) {
                continue;
            }
            float l = c.getLeft() + c.getTranslationX();
            float t = c.getTop() + c.getTranslationY();
            if (x < l || x > l + c.getWidth() || y < t || y > t + c.getHeight()) {
                continue;   // 边界裁剪：落点不在这棵子树里
            }
            if (c.getClass().getName().contains("ExpandableNotificationRow")) {
                return true;
            }
            if (c instanceof ViewGroup && rowAt((ViewGroup) c, x - l, y - t, skip)) {
                return true;
            }
        }
        return false;
    }

    // v1.0.27：原 hitsScrollable（“底下列表还能翻 → 整把手势不拦”）已删除。真机上空白区的
    // 上滑 NSSL 并不消费，放行等于直接漏给系统上滑解锁（1.0.26 实测反馈）；压在通知行上时
    // 列表自己会消费，豁免由 resolveGestureExempt 的“行判定”一条承担。不要再加回来——
    // §20 否决的是“上滑盖掉通知滚动”，那个意图由行豁免保证，与本方法无关。

    /** 这个滚动容器还能不能继续往下滑（手指上划）。拿不准就当“能滑”，宁可不拦。 */
    private static boolean canScrollDown(View v) {
        try {
            if (v.canScrollVertically(1)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 走下面的兜底
        }
        try {
            java.lang.reflect.Method own = v.getClass().getMethod("getOwnScrollY");
            java.lang.reflect.Method range = v.getClass().getMethod("getScrollRange");
            Object a = own.invoke(v);
            Object b = range.invoke(v);
            if (a instanceof Integer && b instanceof Integer) {
                return ((Integer) a).intValue() < ((Integer) b).intValue();
            }
        } catch (Throwable ignored) {
            // 不是 NSSL 这类容器
        }
        return false;
    }

    private static View parentOf(View v) {
        return v.getParent() instanceof View ? (View) v.getParent() : null;
    }

    /**
     * 从窗口根往下找落点处“真的会接住事件”的最上层视图（{@code skip} 是我们自己的覆盖层）。
     *
     * <p>与 1.0.25 之前的关键区别：<b>子树里没人接、自己也不是可点/滚动的视图时，
     * 掉到 index 更小的兄弟继续找</b> —— 真机分发里 {@code onTouchEvent} 返回 false 的视图
     * 就是这样被跨过去的。窗口里摞着的全屏空壳（消息容器、低亮动画、模糊层、
     * 控制中心壳、AOD 壳……）全靠这条掉过去；旧实现会卡在壳上、把命中链截断，
     * 两个豁免同时落空 → 通知手势被误拦。
     */
    private static View topmostAt(ViewGroup root, float x, float y, View skip) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View c = root.getChildAt(i);
            if (c == skip || c.getVisibility() != View.VISIBLE || c.getAlpha() <= 0f) {
                continue;
            }
            float l = c.getLeft() + c.getTranslationX();
            float t = c.getTop() + c.getTranslationY();
            if (x < l || x > l + c.getWidth() || y < t || y > t + c.getHeight()) {
                continue;
            }
            if (c instanceof ViewGroup) {
                View deeper = topmostAt((ViewGroup) c, x - l, y - t, skip);
                if (deeper != null) {
                    return deeper;
                }
            }
            if (selfHandles(c)) {
                return c;   // 子树没人接，但它自己会接（可点 / 滚动容器）：就是它
            }
            // 不接就当没看见，继续找下面的兄弟
        }
        return null;
    }

    /** 这个视图会不会自己消费触摸：可点，或者是（内嵌的）滚动容器。 */
    private static boolean selfHandles(View v) {
        if (v.isClickable() || v.isLongClickable() || v.hasOnClickListeners()) {
            return true;
        }
        String n = v.getClass().getName();
        return n.contains("Scroll") || n.contains("Recycler")
                || n.contains("ListView") || n.contains("ViewPager");
    }

    /** {@code KeyguardStateControllerImpl#notifyKeyguardState} 回调。 */
    static void onKeyguardState(boolean showing, boolean occluded) {
        sKeyguardState = (showing && !occluded) ? 1 : 0;
        final LockBarController c = sInstance;
        if (c == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            c.sync();
        } else {
            c.window.post(c::sync);
        }
    }

    private static LockBarController obtain(Object w, LockConfig config, XposedInterface module) {
        if (!(w instanceof ViewGroup)) {
            return null;
        }
        ViewGroup vg = (ViewGroup) w;
        LockBarController c = sInstance;
        if (c == null || c.window != vg) {
            if (module == null) {
                return null; // 还没经过 attach，先不创建
            }
            c = new LockBarController(vg, config, module);
            sInstance = c;
            config.setListener(c);
        }
        return c;
    }

    // ------------------------------------------------------------------ 装配

    private void ensureInstalled() {
        if (overlay != null) {
            return;
        }
        android.content.Context ctx = window.getContext();
        float density = window.getResources().getDisplayMetrics().density;

        overlay = new FrameLayout(ctx);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);

        rim = new LockRimView(ctx);
        rim.configure(config.arcWidth, config.arcAlpha, config.arcColor, config.arcDim,
                config.dimStrength);
        overlay.addView(rim, matchParent());

        handle = new LockHandleView(ctx);
        handle.setCallback(this);
        attachSystemHandle(ctx);

        // 触控区 = 小白条周围一大块：条高 5dp、离底 7.5dp，
        // 触控盒高 36dp（下方 7.5dp、上方 23dp）+ 条宽左右各 16dp。
        // 开了「扩大触控范围」就整条贴满屏宽：条本体由子 View / 自绘居中，外观不变
        int handleWidth = config.handleWide
                ? ViewGroup.LayoutParams.MATCH_PARENT
                : Math.max(1,
                        (int) (handle.systemBarWidth() + 2f * HANDLE_H_PAD_DP * density));
        FrameLayout.LayoutParams handleLp =
                new FrameLayout.LayoutParams(handleWidth,
                        Math.max(1, (int) (HANDLE_HEIGHT_DP * density)),
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        overlay.addView(handle, handleLp);

        // 小白条上方的自定义文字（配置 hint_text 为空时隐藏）
        hint = new TextView(ctx);
        hint.setGravity(Gravity.CENTER);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hint.setTextColor(0xD9FFFFFF);
        hint.setShadowLayer(4f * density, 0f, 0f, 0x66000000);
        hint.setSingleLine(true);
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintLp.bottomMargin = (int) ((HANDLE_HEIGHT_DP + 10f) * density);
        overlay.addView(hint, hintLp);
        updateHint();

        // NotificationShadeWindowView 的父类链是 WindowRootView -> FrameLayout
        FrameLayout.LayoutParams windowLp =
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
        window.addView(overlay, windowLp);

        try {
            window.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        } catch (Throwable t) {
            log("failed to add layout listener", t);
        }
        fadeHandle(0f);
    }

    private static ViewGroup.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /**
     * 用系统自己的手势条类（{@code NavigationHandle}）当小白条本体：
     * 屏宽 32%、高 5dp、离底 7.5dp、颜色取系统 {@code homeHandleColor}，与手势导航那条完全一致。
     *
     * <p>装配流程被拆成互不相干的几步：<b>只要类和构造器拿得到，条就一定装上</b>，
     * 后面配色的每一步各自 try/catch —— 早前版本里任何一步抛异常都会让整段进 catch，
     * 结果永远走自绘 fallback（反馈「小横条依旧是自绘小横条」）。
     * 每步结果都写进诊断信息，App 里能直接看到。
     */
    private void attachSystemHandle(Context ctx) {
        Class<?> cls;
        try {
            cls = Class.forName(
                    "com.android.systemui.navigationbar.gestural.NavigationHandle",
                    false, ctx.getClassLoader());
        } catch (Throwable t) {
            debugStatus("handle=fallback(class:" + brief(t) + ")");
            log("NavigationHandle class missing", t);
            return;
        }

        View nav;
        try {
            java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            nav = (View) ctor.newInstance(ctx);
        } catch (Throwable t) {
            debugStatus("handle=fallback(ctor:" + brief(t) + ")");
            log("NavigationHandle ctor failed", t);
            return;
        }

        // 从这里开始，任何一步失败都只影响配色，不再影响装配
        String note = "ok";
        try {
            // 0 = 浅色档，锁屏上就是那根白色小白条
            cls.getMethod("setDarkIntensity", float.class).invoke(nav, 0f);
        } catch (Throwable t) {
            note = "darkIntensity:" + brief(t);
            log("setDarkIntensity failed", t);
        }
        try {
            Object color = cls.getMethod("getHandleColor").invoke(nav);
            if (color instanceof Integer && ((Integer) color) >>> 24 < 8) {
                // 系统主题里没解析到 homeHandleColor（会是全透明），换成我们自己解析到的系统色
                Object paint = cls.getField("mPaint").get(nav);
                if (paint instanceof android.graphics.Paint) {
                    ((android.graphics.Paint) paint).setColor(handle.systemHandleColor());
                    note = "ok(paint-override)";
                }
            }
        } catch (Throwable t) {
            note = "color:" + brief(t);
            log("handle color failed", t);
        }

        handle.attachSystemHandle(nav);
        debugStatus("handle=system(" + note + ")");
    }

    /**
     * 上滑到 35% 时把窗口直系子视图快照写进诊断信息。
     *
     * <p>反馈里“上滑过程中依旧有一小块灰色/黑色遮罩”一直没有新截图，
     * 只能靠这份清单看清楚：裁切边以下、我们描边之外，还盖着哪几层（背景色 / 透明度 / 高度）。
     */
    private void dumpViews() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("p=").append(Math.round(progress * 100f))
                    .append("% radius=").append(Math.round(radius()))
                    .append(" clip=").append(clipInstalled)
                    .append(" keyguard#").append(indexOf(keyguardRoot))
                    .append(" notif#").append(indexOf(sharedNotif))
                    .append(" panel#").append(indexOf(notificationPanel))
                    .append('\n');
            int count = window.getChildCount();
            for (int i = 0; i < count && sb.length() < 4000; i++) {
                View c = window.getChildAt(i);
                if (c == overlay || !c.isShown()) {
                    continue;
                }
                sb.append('#').append(i).append(' ').append(idName(c))
                        .append('/').append(c.getClass().getSimpleName())
                        .append(" y=").append(Math.round(c.getTop() + c.getTranslationY()))
                        .append("..").append(Math.round(c.getBottom() + c.getTranslationY()))
                        .append(" a=").append(Math.round(c.getAlpha() * 100f) / 100f);
                android.graphics.drawable.Drawable bg = c.getBackground();
                if (bg == null) {
                    sb.append(" bg=none");
                } else if (bg instanceof android.graphics.drawable.ColorDrawable) {
                    sb.append(" bg=#").append(Integer.toHexString(
                            ((android.graphics.drawable.ColorDrawable) bg).getColor()));
                } else {
                    sb.append(" bg=").append(bg.getClass().getSimpleName());
                }
                float e = c.getElevation();
                if (e > 0f) {
                    sb.append(" e=").append(Math.round(e));
                }
                sb.append('\n');
            }
            config.putDebug(LockConfig.KEY_DEBUG_VIEWS, sb.toString());
            // 景深分身的证据也走 ModuleLog：debug_views 没有导出入口，debug_log 有
            scanDepthLayers();
        } catch (Throwable t) {
            log("dumpViews failed", t);
        }
    }

    /**
     * 递归扫出可能参与「景深」的 View —— 排查<b>景深分身</b>用（上滑时景深主体被复制一份跟着上移）。
     *
     * <p><b>只读不改。</b>分身的可能成因至少有三种：① 主体层在我们移动的容器里（上移一份）
     * 而下方露出的完整壁纸里本来就有另一份；② 主体层不在我们移动的容器里，与被移动的
     * 时钟/内容错开；③ 另有模块单独 hook 了壁纸景深（LSPosed 日志里能看到
     * “Installed third-party wallpaper depth capability hooks”）。三种成因的修法完全不同，
     * 在拿到真实层级前动手只会改错。
     *
     * <p>输出里每行标注它<b>是否落在我们管理的三个 View 子树内</b>（那意味着它同时被平移 + 裁切），
     * 这一行就是区分上面三种成因的判据。用户在 App 里导出日志即可带回。
     */
    private void scanDepthLayers() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("景深扫描 p=").append(Math.round(progress * 100f))
                    .append("% parallax=").append(config.parallax)
                    .append(" managed=keyguard/notif/panel\n");
            int[] found = {0};
            walkDepth(window, 0, sb, found);
            if (found[0] == 0) {
                sb.append("  (窗口内无 depth/subject/wallpaper 类 View)\n");
            }
            // 窗口直属子 View 全量概览：分身到底是「谁在动、谁不动」一眼可辨
            sb.append("窗口直属子View：\n");
            for (int i = 0; i < window.getChildCount(); i++) {
                View c = window.getChildAt(i);
                if (c == null) {
                    continue;
                }
                sb.append('[').append(i).append("] ")
                        .append(idName(c)).append('/').append(c.getClass().getSimpleName())
                        .append(" y=").append(c.getTop()).append("..").append(c.getBottom())
                        .append(" vis=").append(c.getVisibility())
                        .append(" a=").append(Math.round(c.getAlpha() * 100f) / 100f)
                        .append(inManaged(c) ? " [被我们平移]" : " [不动]")
                        .append('\n');
            }
            // 关键容器子层清单：主体层若仍会动，这里能直接点名它挂在谁下面
            int[] budget = {100};
            appendLayerDump(sb, "keyguard_panel_view", 1, budget);
            appendLayerDump(sb, "keyguard_info_layer", 2, budget);
            appendLayerDump(sb, "miui_keyguard_clock_container", 2, budget);
            appendLayerDump(sb, "miui_keyguard_foreground_clock_container", 2, budget);
            ModuleLog.d(sb.toString());
            ModuleLog.flush();
        } catch (Throwable t) {
            log("scanDepthLayers failed", t);
        }
    }

    private void walkDepth(View v, int depth, StringBuilder sb, int[] found) {
        // 三层熔断：深度、命中数、字符数 —— 通知列表展开时这棵树可能非常大
        if (v == null || depth > 8 || found[0] > 40 || sb.length() > 9000) {
            return;
        }
        String name = idName(v);
        // 主体层是系统按壁纸元数据（crop_subject_0.png）代码动态建出来的 View，
        // 未必设了 id —— 只按 id 扫会整个漏掉，所以类名也一起匹配
        if (isDepthLike(name) || isDepthLike(v.getClass().getSimpleName())) {
            found[0]++;
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < depth; i++) {
                line.append("  ");
            }
            line.append(name)
                    .append('/').append(v.getClass().getSimpleName())
                    .append(" y=").append(Math.round(v.getTop() + v.getTranslationY()))
                    .append("..").append(Math.round(v.getBottom() + v.getTranslationY()))
                    .append(" ty=").append(Math.round(v.getTranslationY()))
                    .append(" a=").append(Math.round(v.getAlpha() * 100f) / 100f)
                    .append(" path=").append(pathOf(v))
                    .append(inManaged(v) ? "  [被我们平移+裁切]" : "  [不在我们管理的子树内]")
                    .append('\n');
            sb.append(line);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walkDepth(g.getChildAt(i), depth + 1, sb, found);
            }
        }
    }

    /** 打印某容器的子层树（限深、限行数），用于日志点名主体层挂在谁下面。 */
    private void appendLayerDump(StringBuilder sb, String rootId, int maxDepth, int[] budget) {
        if (budget[0] <= 0) {
            return;
        }
        try {
            View root = find(rootId);
            if (root == null) {
                sb.append("子层[").append(rootId).append("] 未找到\n");
                return;
            }
            sb.append("子层[").append(rootId).append("]：\n");
            dumpLayerChildren(sb, root, 1, maxDepth, budget);
        } catch (Throwable t) {
            sb.append("子层[").append(rootId).append("] dump失败 ").append(t).append('\n');
        }
    }

    private void dumpLayerChildren(StringBuilder sb, View v, int depth, int maxDepth, int[] budget) {
        if (budget[0] <= 0 || depth > maxDepth || !(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        int n = Math.min(g.getChildCount(), 15);
        for (int i = 0; i < n; i++) {
            if (budget[0] <= 0) {
                return;
            }
            View c = g.getChildAt(i);
            if (c == null) {
                continue;
            }
            budget[0]--;
            StringBuilder line = new StringBuilder();
            for (int d = 0; d < depth; d++) {
                line.append("  ");
            }
            line.append(idName(c)).append('/').append(c.getClass().getSimpleName())
                    .append(" y=").append(c.getTop()).append("..").append(c.getBottom())
                    .append(" ty=").append(Math.round(c.getTranslationY()));
            if (c.getVisibility() != View.VISIBLE) {
                line.append(" vis=").append(c.getVisibility());
            }
            line.append('\n');
            sb.append(line);
            dumpLayerChildren(sb, c, depth + 1, maxDepth, budget);
        }
        if (g.getChildCount() > n) {
            budget[0]--;
            for (int d = 0; d < depth; d++) {
                sb.append("  ");
            }
            sb.append("...(共").append(g.getChildCount()).append("个子View)\n");
        }
    }

    /** 该 View 到窗口的祖先链（id 名，{@code >} 分隔），用于日志定位它挂在哪个子树下。 */
    private String pathOf(View v) {
        java.util.ArrayList<String> chain = new java.util.ArrayList<>();
        View c = v;
        while (c != null && c != window && chain.size() < 12) {
            chain.add(idName(c));
            android.view.ViewParent p = c.getParent();
            c = p instanceof View ? (View) p : null;
        }
        java.util.Collections.reverse(chain);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                sb.append('>');
            }
            sb.append(chain.get(i));
        }
        return sb.toString();
    }

    private static boolean isDepthLike(String name) {
        if (name == null || name.isEmpty() || "-".equals(name)) {
            return false;
        }
        String s = name.toLowerCase(java.util.Locale.ROOT);
        return s.contains("depth") || s.contains("subject") || s.contains("crop")
                || s.contains("wallpaper") || s.contains("portrait")
                || s.contains("blur") || s.contains("mask")
                // 景深主体抠图层 id = deducted_image_view（KeyguardDepthInteractor 维护），
                // 「deducted」不含上面任何关键词，漏了它分身就修不掉
                || s.contains("deducted");
    }

    /** 该 View 是否落在我们平移 + 裁切的三个目标之一的子树内（含自身）。 */
    private boolean inManaged(View v) {
        return under(v, keyguardRoot) || under(v, sharedNotif) || under(v, notificationPanel);
    }

    private static boolean under(View v, View root) {
        View c = v;
        while (c != null) {
            if (c == root) {
                return true;
            }
            android.view.ViewParent p = c.getParent();
            c = p instanceof View ? (View) p : null;
        }
        return false;
    }

    private int indexOf(View v) {
        if (v == null) {
            return -1;
        }
        for (int i = 0; i < window.getChildCount(); i++) {
            if (window.getChildAt(i) == v) {
                return i;
            }
        }
        return -1;
    }

    private String idName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) {
                return "-";
            }
            return window.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "-";
        }
    }

    private static String brief(Throwable t) {
        if (t == null) {
            return "null";
        }
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String m = c.getMessage();
        if (m != null && m.length() > 60) {
            m = m.substring(0, 60);
        }
        return c.getClass().getSimpleName() + (m == null ? "" : ":" + m);
    }

    // ------------------------------------------------------------------ 状态同步

    private void sync() {
        if (overlay == null) {
            return;
        }
        if (!config.isReady() && configLoadTry < MAX_CONFIG_TRY) {
            // LSPosed 远端配置偶尔要等一会儿才拿得到，失败就限次重试
            configLoadTry++;
            config.load();
        }
        if (resolveTry < MAX_RESOLVE_TRY
                || (keyguardRoot != null && !keyguardRoot.isAttachedToWindow())) {
            resolve();
        }
        boolean clockStale = (clockRoot != null && !clockRoot.isAttachedToWindow())
                || (clockSecond != null && !clockSecond.isAttachedToWindow());
        if (clockStale) {
            // 换锁屏样式 / 面板重建：旧引用是脱离窗口的，计数从头来
            clockTry = 0;
            clockSecondTry = 0;
        }
        if (clockStale
                || (clockRoot == null && clockTry < MAX_CLOCK_TRY)
                || (clockSecond == null && clockSecondTry < MAX_CLOCK_SECOND_TRY)) {
            // 副层（分层模板的分钟）可能比主层晚挂上，主层到手后继续找副层
            clockTry++;
            clockSecondTry++;
            resolveClock();
        }

        boolean bouncerShowing = bouncer != null && bouncer.getVisibility() == View.VISIBLE;
        boolean ready = keyguardRoot != null && keyguardRoot.isAttachedToWindow();
        boolean stateOk = sKeyguardState != 0;      // 0 = 明确不在锁屏；-1 = 未知，退回视图判断
        boolean interactive = isInteractive();
        boolean onKeyguard = ready && stateOk && !bouncerShowing && interactive
                && keyguardRoot.isShown() && config.enabled;

        int visibility = onKeyguard ? View.VISIBLE : View.GONE;
        if (overlay.getVisibility() != visibility) {
            overlay.setVisibility(visibility);
        }

        if (!onKeyguard) {
            if (progress != 0f) {
                reset();
            } else {
                clearLift();
            }
            return;
        }

        int h = window.getHeight();
        // 行程不再固定：拉满位移恒为 h*MAX_SHIFT_RATIO，行程 = 位移 / 跟手倍率。
        // dragRatio=1 → 倍率 1 → 行程 = 位移，手指动多少内容动多少（1:1 贴手）；
        // dragRatio 越小行程越短，同样行程内容走得更多 = 更快、但越不贴手。
        float range = Math.max(h * MAX_SHIFT_RATIO / followMul(config.dragRatio), 1f);
        if (range != rangePx) {
            rangePx = range;
            handle.setRange(rangePx);
        }
    }

    /** 息屏 / AOD（dozing）时 {@code isInteractive()} 为 false，此时不该显示小白条。 */
    private boolean isInteractive() {
        try {
            if (powerManager == null) {
                powerManager = (PowerManager)
                        window.getContext().getSystemService(Context.POWER_SERVICE);
            }
            return powerManager == null || powerManager.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 惰性解析锁屏相关 View（蓝图 sections 可能在窗口 attach 之后才加进来）。 */
    private void resolve() {
        resolveTry++;
        View root = find("keyguard_root_view");
        View notif = find("shared_notification_container");
        View panel = find("notification_panel");
        View box = find("keyguard_bouncer_container");

        if (root != keyguardRoot) {
            restoreAnchors();   // 候选层挂在旧根下，换根前先把补偿还原
            detachTarget(keyguardRoot, clipRoot);
            clipRoot = new LockGlass.Clip();
            keyguardRoot = root;
            lastApplied = Float.NaN;
            clipInstalled = false;
        }
        if (notif != sharedNotif) {
            restoreAnchors();
            detachTarget(sharedNotif, clipNotif);
            clipNotif = new LockGlass.Clip();
            sharedNotif = notif;
            lastApplied = Float.NaN;
        }
        if (panel != notificationPanel) {
            restoreAnchors();
            detachTarget(notificationPanel, clipPanel);
            clipPanel = new LockGlass.Clip();
            notificationPanel = panel;
            lastApplied = Float.NaN;
        }
        bouncer = box;
    }

    private static void detachTarget(View v, LockGlass.Clip clip) {
        if (v == null) {
            return;
        }
        try {
            clip.clear(v);
            v.setTranslationY(0f);
        } catch (Throwable ignored) {
            // 视图可能已被回收
        }
    }

    private View find(String name) {
        try {
            int id = window.getResources().getIdentifier(name, "id", PKG);
            return id != 0 ? window.findViewById(id) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 时间上移（仿 iOS）

    /**
     * 找锁屏时间。
     *
     * <p>要找<b>两个</b>容器：
     * <ul>
     *   <li>{@code miui_keyguard_clock_container} —— {@code KeyguardClockContainer}（整屏 FrameLayout），
     *       时针那半张表在里面；</li>
     *   <li>{@code miui_keyguard_foreground_clock_container} —— 副层，
     *       <b>分层模板的分钟（以及部分模板的日期）被拆到这一层</b>，跟主层是<b>兄弟</b>关系。</li>
     * </ul>
     * 两个是兄弟，各自改各自的 translationY 不会叠加，所以可以放心一起抬。
     *
     * <p>副层可能比主层晚出现，所以单独处理：换了副层不动进行中的位移。
     */
    private void resolveClock() {
        View root = find("miui_keyguard_clock_container");
        View second = find("miui_keyguard_foreground_clock_container");
        if (second == root) {
            second = null;      // 防御：万一 ROM 把两个 id 指到同一个 View
        }
        if (root != clockRoot) {
            // 主容器换了（换锁屏样式 / 窗口重建）：整段复位
            if (clockRoot != null) {
                clearLift();
            }
            clockRoot = root;
            clockTime = root != null ? find("clock_container1") : null;
        }
        if (second != clockSecond) {
            // 只换副层引用：把旧的放回去，新的按当前 lift 立刻对齐
            if (clockSecond != null && !Float.isNaN(clockSecondBaseY)) {
                setTranslation(clockSecond, clockSecondBaseY);
            }
            clockSecond = second;
            clockSecondBaseY = Float.NaN;
            if (lift != 0f) {
                writeLift();
            }
        }
    }

    /**
     * 时间最多能上移的距离（px）：时间本体高度的一半。
     *
     * <p>时/分容器本身都是 match_parent铺满整屏，直接量会得到“整屏”，所以用 {@link #clockBox()}
     * 往下钻到真正有内容的那层，把<b>主层的时</b>和<b>副层的分</b>一起算进来——
     * 它俩左右并排时取到的就是“一张表”的高度，上下叠放时就是两张表的总高。
     */
    private float liftMax() {
        int h = window.getHeight();
        if (h <= 0) {
            return 0f;
        }
        float[] box = new float[]{Float.MAX_VALUE, -Float.MAX_VALUE};
        int[] loc = new int[2];
        clockBox(clockTime, h, loc, box);
        clockBox(clockRoot, h, loc, box);
        clockBox(clockSecond, h, loc, box);
        float span = box[0] <= box[1] ? box[1] - box[0] : h * LIFT_SPAN_MIN_RATIO;
        span = Math.max(h * LIFT_SPAN_MIN_RATIO, Math.min(h * LIFT_SPAN_MAX_RATIO, span));
        return Math.max(0f, span * LIFT_MAX_RATIO);
    }

    /**
     * 把 {@code v}（或它下面真正有内容的子孙）的纵向范围并进 {@code box[0]~box[1]}。
     *
     * <p>高度为 0（布局里写 0dp、靠代码撑开的那类）或等于屏高（match_parent 的壳）继续往下钻，
     * 其余的当内容收进来；返回是否真的收进了东西。
     */
    private static boolean clockBox(View v, int windowH, int[] loc, float[] box) {
        if (v == null || v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0f) {
            return false;
        }
        int vh = v.getHeight();
        if (vh > 0 && vh < windowH) {
            v.getLocationInWindow(loc);
            float top = loc[1];
            float bottom = top + vh;
            if (box[0] > box[1]) {
                box[0] = top;
                box[1] = bottom;
            } else {
                box[0] = Math.min(box[0], top);
                box[1] = Math.max(box[1], bottom);
            }
            return true;
        }
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        boolean any = false;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (clockBox(g.getChildAt(i), windowH, loc, box)) {
                any = true;
            }
        }
        return any;
    }

    /** 跟手更新时间上移量；到“时间高度一半”就停住，继续滑也不动（禁止滑动）。 */
    private void updateLift(float y) {
        if ((clockRoot == null && clockSecond == null) || liftAnimator != null) {
            return;
        }
        float max = liftMax();
        if (max <= 0f) {
            return;
        }
        float v = Math.min(max, Math.max(0f, downY - y));
        if (v == lift) {
            return;
        }
        lift = v;
        writeLift();
    }

    /**
     * 把当前 {@link #lift} 写进时间容器（保留系统自己的原始 translationY）。
     *
     * <p>主层（时）和副层（分）是<b>兄弟</b>，各自记各自的原始值，抬的量完全一样，
     * 所以时分永远同步移动。
     */
    private void writeLift() {
        if (clockRoot != null) {
            if (Float.isNaN(clockBaseY)) {
                clockBaseY = clockRoot.getTranslationY();
            }
            setTranslation(clockRoot, clockBaseY - lift);
        }
        if (clockSecond != null) {
            if (Float.isNaN(clockSecondBaseY)) {
                clockSecondBaseY = clockSecond.getTranslationY();
            }
            setTranslation(clockSecond, clockSecondBaseY - lift);
        }
    }

    /**
     * 松手后弹性归位（仿 iOS）：先越过原位一点再弹回来。
     *
     * <p>用 {@link android.view.animation.OvershootInterpolator} 而不是直接归零，
     * 就是为了要那个“回弹”的手感。
     */
    private void springLiftBack() {
        if ((clockRoot == null && clockSecond == null) || lift == 0f) {
            clearLift();
            return;
        }
        // 先把 from 抓出来再 cancel：cancel 会走 onAnimationEnd → clearLift，把 lift 清成 0
        final float from = lift;
        cancelLiftAnimator();
        ValueAnimator a = ValueAnimator.ofFloat(from, 0f);
        a.setDuration(LIFT_RETURN_MS);
        a.setInterpolator(new android.view.animation.OvershootInterpolator(LIFT_OVERSHOOT));
        a.addUpdateListener(animation -> {
            lift = (Float) animation.getAnimatedValue();
            writeLift();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (liftAnimator == a) {
                    liftAnimator = null;
                }
                clearLift();
            }
        });
        liftAnimator = a;
        a.start();
    }

    private void cancelLiftAnimator() {
        ValueAnimator a = liftAnimator;
        if (a != null) {
            liftAnimator = null;
            a.cancel();
        }
    }

    /** 立刻把时间放回原位（解锁 / 离开锁屏 / 取消手势）。主副两层都要放回去。 */
    private void clearLift() {
        cancelLiftAnimator();
        lift = 0f;
        liftTracking = false;
        if (clockRoot != null && !Float.isNaN(clockBaseY)) {
            setTranslation(clockRoot, clockBaseY);
        }
        if (clockSecond != null && !Float.isNaN(clockSecondBaseY)) {
            setTranslation(clockSecond, clockSecondBaseY);
        }
        clockBaseY = Float.NaN;
        clockSecondBaseY = Float.NaN;
    }

    /** 诊断：拦了几次系统上滑（单独一条 key，免得把 unlock= 覆盖掉）。 */
    private void noteBlock(String text) {
        try {
            config.putDebug(LockConfig.KEY_DEBUG_BLOCK, text);
        } catch (Throwable ignored) {
            // 写诊断失败不影响功能
        }
        // 同步进可导出的 ModuleLog：拦截那一刻的命中链。反馈“通知被拦”全靠这条定位链条断在哪
        try {
            ModuleLog.d(text + " 链=" + chainText(downX, downY));
            ModuleLog.flush();
        } catch (Throwable ignored) {
            // 诊断失败不影响功能
        }
    }

    /** 落点往上的命中链（简单类名，最多 10 层；滚动容器标出“还能不能翻”）+ 通知行判定，诊断用。 */
    private String chainText(float x, float y) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (View p = topmostAt(window, x, y, overlay);
                p != null && p != window && n < 10; p = parentOf(p), n++) {
            if (n > 0) {
                sb.append('>');
            }
            String nm = p.getClass().getSimpleName();
            sb.append(nm);
            if (nm.contains("Scroll") || nm.contains("Recycler")
                    || nm.contains("ListView") || nm.contains("ViewPager")) {
                sb.append('{').append(canScrollDown(p)).append('}');
            }
        }
        sb.append(" 行=").append(onNotificationRow(x, y));
        return sb.toString();
    }

    /** 上次记进日志的诊断状态，用来避免同值反复挂载时刷屏。 */
    private String lastLoggedStatus;

    private void debugStatus(String text) {
        try {
            config.putDebug(LockConfig.KEY_DEBUG_STATUS, text);
        } catch (Throwable ignored) {
            // 写诊断失败不影响功能
        }
        if (text.equals(lastLoggedStatus)) {
            return;
        }
        lastLoggedStatus = text;
        // 这一条就是“覆盖层真的挂到窗口上了”的直接证据，排查“不生效”时最关键
        ModuleLog.d("覆盖层状态 " + text);
        ModuleLog.flush();
    }

    // ------------------------------------------------------------------ 每帧应用

    private void apply(float g) {
        if (g == lastApplied) {
            return;
        }
        lastApplied = g;

        int w = window.getWidth();
        int h = window.getHeight();
        float shift = 0f;
        if (w > 0 && h > 0) {
            // 拉满进度时的位移固定为 MAX_SHIFT_RATIO×屏高，与 dragRatio 无关：
            // 旧写法 shift = h*g*dragRatio 让 dragRatio 同时决定了「滑出多少」和「跟手快慢」，
            // 于是 dragRatio 调小时（如 0.3）拉满也只上移 30% 屏高，内容卡在屏幕里出不去。
            // 行程（rangePx）已按 followMul 缩放，这里对进度保持线性，
            // d(位移)/d(手指) 恒等于 followMul：dragRatio=1 时恰好 1:1 贴手。
            shift = h * MAX_SHIFT_RATIO * g;
        }
        // edge = 窗口坐标下的“可见底边”，-shift 时内容整体上移
        float edge = h - shift;
        float ty = config.parallax ? -shift : 0f;
        setTranslation(keyguardRoot, ty);
        setTranslation(sharedNotif, ty);
        setTranslation(notificationPanel, ty);
        // 景深/壁纸层反向补偿：这些层画的是壁纸本身（含景深主体），必须留在屏幕原位 ——
        // 否则前面的主体跟着上滑、后面的系统壁纸不动，就会出现「主体分身」。
        // 内容根已平移 ty，这里给候选层写 base-ty 抵消（ty=0 时还原并清缓存）。
        anchorWallpaperLayers(ty);
        // 小白条贴着可见底边一起上升（触控区跟着走，手指不用重新找位置）
        setTranslation(handle, -shift);
        setTranslation(hint, -shift);

        if (!config.arcEnabled || g <= 0.001f || w <= 0 || h <= 0) {
            clearClips();
            return;
        }

        float radius = radius();
        // 局部坐标：窗口坐标减去 translationY。
        // 联动开 = 内容跟着动，裁切就是自身 bounds；联动关 = 内容不动，裁切边上移。
        float localBottom = edge - ty;
        float localTop = (edge - h) - ty;
        LockGlass.Shape clipShape =
                new LockGlass.Shape(localBottom, radius, radius, 1f, localTop);
        clipRoot.update(keyguardRoot, clipShape);
        clipNotif.update(sharedNotif, clipShape);
        clipPanel.update(notificationPanel, clipShape);
        clipInstalled = true;

        // 描边壳：顶边与内容顶对齐，底边落在可见底边上
        rim.update(new LockGlass.Shape(edge, radius, radius, LockGlass.smoothstep(g), edge - h));
    }

    /**
     * 跟手倍率：{@code 内容位移速度 / 手指速度}，由「跟手程度」dragRatio 决定。
     *
     * <p>行程（{@link #rangePx}）= {@code MAX_SHIFT_RATIO×屏高 / followMul}，
     * 而 {@link #apply} 里位移对进度线性 —— 两者相除，速度比恒等于本函数：
     *
     * <ul>
     *   <li>dragRatio = 1 → 倍率 1：内容与手指 1:1 完全贴手，手指停内容停；
     *   <li>dragRatio &lt; 1 → 倍率 &gt; 1：行程变短，内容按该倍率跑在手指前面。
     * </ul>
     *
     * <p>倍率恒大于等于 1：手指从底部条出发最多划一屏，行程一旦超过屏高就永远拉不满，
     * 所以「更跟手」只能朝 1:1 收敛，低档位只能是「短行程 + 更快」，这是物理上限。
     * 拉满行程时位移恒为 {@link #MAX_SHIFT_RATIO}×屏高 —— 内容一定滑出屏幕，
     * 不管 dragRatio 取多小都不会卡在半屏（旧实现 dragRatio=0.3 只能上移 30% 屏高）。
     */
    private static float followMul(float dragRatio) {
        float r = Math.max(0.2f, Math.min(1f, dragRatio));
        return 1f + 2f * (1f - r);
    }

    private void clearClips() {
        if (clipInstalled) {
            clipRoot.clear(keyguardRoot);
            clipNotif.clear(sharedNotif);
            clipPanel.clear(notificationPanel);
            clipInstalled = false;
        }
        if (rim != null) {
            rim.clear();
        }
    }

    private static void setTranslation(View v, float y) {
        // HyperOSKeyguardRootView.setTranslationY 每次都会触发 onStateChanged，
        // 因此必须跳过数值未变化的写入，否则会和布局互相触发。
        if (v != null && v.getTranslationY() != y) {
            v.setTranslationY(y);
        }
    }

    // ---------------------------------------------------------- 壁纸层锚定（景深防分身）

    /**
     * 把「画壁纸本身」的层（壁纸预览 / 景深主体合成层）钉在屏幕原位。
     *
     * <p>父容器（keyguard_root_view 等）已被平移 {@code ty}，子层会跟着走 —— 但屏幕后面还有
     * 一份不动的系统壁纸，主体跟着上滑就会和后层的那份错开，形成「分身」。
     * 这里给每个候选写 {@code base - ty} 抵消父位移：壁纸层全程留在原位，
     * 与后层完全重合，上滑时只被弧形裁切逐步切走，视觉上就是一张完整壁纸。
     *
     * <p>候选 = 管理子树内 id/类名命中 depth|subject|crop|wallpaper|portrait|blur|mask 的 View
     * （与 {@link #scanDepthLayers} 同一套判定），只收集最上层命中者，整棵子树一起锚定，
     * 避免父子叠加补偿。
     *
     * <p>{@code ty == 0}（复位 / 联动关）时还原原始 translationY 并清缓存，下一轮手势重新收集 ——
     * 系统在空闲期改过 translationY 也不会被我们固化。
     */
    private void anchorWallpaperLayers(float ty) {
        try {
            if (ty == 0f) {
                restoreAnchors();
                return;
            }
            if (!anchorCollected) {
                collectAnchor(keyguardRoot);
                collectAnchor(sharedNotif);
                collectAnchor(notificationPanel);
                anchorCollected = true;   // 空结果也置位：非景深壁纸不该每帧重扫整棵树
            }
            for (java.util.Map.Entry<View, Float> e : anchorBaseY.entrySet()) {
                setTranslation(e.getKey(), e.getValue() - ty);
            }
        } catch (Throwable t) {
            log("anchorWallpaperLayers failed", t);
        }
    }

    /** 还原所有锚定候选的原始 translationY 并清缓存（视图换根 / 复位时也要走这里）。 */
    private void restoreAnchors() {
        if (!anchorCollected) {
            return;
        }
        for (java.util.Map.Entry<View, Float> e : anchorBaseY.entrySet()) {
            setTranslation(e.getKey(), e.getValue());
        }
        anchorBaseY.clear();
        anchorCollected = false;
    }

    private void collectAnchor(View root) {
        if (root == null) {
            return;
        }
        if (isAnchorLayer(root)) {
            anchorBaseY.put(root, root.getTranslationY());
            return;   // 最上层命中者：整棵子树一起留在原位，不再下钻
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectAnchor(g.getChildAt(i));
            }
        }
    }

    private boolean isAnchorLayer(View v) {
        String id = idName(v);
        // blur_blend_container 长在 miui_keyguard_clock_container / foreground 两个时钟容器里，
        // 是表盘/数字的合成层 —— 1.0.20 实测把它钉住 = 锁屏时钟不再跟随上移。
        // 它属于要跟内容一起上移的「界面」，不属于壁纸层，明确排除（诊断仍会打印它，ty=0 即未锚定）
        if ("blur_blend_container".equals(id)) {
            return false;
        }
        return isDepthLike(id) || isDepthLike(v.getClass().getSimpleName());
    }

    private void reset() {
        cancelAnimator();
        clearLift();
        progress = 0f;
        startProgress = 0f;
        lastTickBucket = -1;
        // 强制重新 apply：即便上一次已经是 0，也要保证位移/裁切被真正清掉
        lastApplied = Float.NaN;
        apply(0f);
        fadeHandle(0f);
        if (handle != null) {
            handle.resetGesture();
        }
    }

    // ------------------------------------------------------------------ 圆角

    private float radius() {
        if (radiusCache > 0f) {
            return radiusCache;
        }
        float r = LockGlass.screenCornerRadius(window);
        if (r <= 0f) {
            r = dimen("rounded_corner_radius");
        }
        if (r <= 0f) {
            r = dimen("rounded_corner_radius_top");
        }
        if (r > 0f) {
            radiusCache = r;
        }
        return Math.max(r, 0f);
    }

    private float dimen(String name) {
        try {
            Resources res = window.getResources();
            int id = res.getIdentifier(name, "dimen", "android");
            return id != 0 ? res.getDimensionPixelSize(id) : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    // ------------------------------------------------------------------ 手势

    @Override
    public void onDragStart(float y) {
        cancelAnimator();
        startProgress = progress;
        lastTickBucket = bucket(progress);
        viewsDumped = false;
        haptic(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    @Override
    public void onDragMove(float dy, float ignored) {
        if (!config.enabled || rangePx <= 1f) {
            return;
        }
        if (overlay == null || overlay.getVisibility() != View.VISIBLE) {
            // 拖动过程中离开了锁屏（弹出密码盘 / 解锁），立刻停止
            return;
        }
        float p = LockGlass.clamp01(startProgress + dy / rangePx);
        if (p == progress) {
            return;
        }
        progress = p;
        apply(p);
        fadeHandle(p);

        if (!viewsDumped && p >= 0.35f) {
            viewsDumped = true;
            dumpViews();
        }

        int bucket = bucket(p);
        if (bucket != lastTickBucket && bucket > 0) {
            lastTickBucket = bucket;
            haptic(HapticFeedbackConstants.SEGMENT_TICK);
        }
    }

    @Override
    public void onDragEnd(float dy, float ignored, boolean cancel) {
        if (!config.enabled || overlay == null
                || overlay.getVisibility() != View.VISIBLE) {
            reset();
            return;
        }
        // 拉过半松手：直接交给系统进解锁界面（仿 iOS），不回弹
        if (!cancel && progress >= UNLOCK_THRESHOLD && LockBarUnlock.show()) {
            animateTo(0f);
            return;
        }
        // cancel 也包含了“点击小白条没有拖动”的情况：点击视为归位
        if (cancel || config.releaseReturn) {
            animateTo(0f);
            return;
        }
        fadeHandle(progress);
    }

    private static int bucket(float p) {
        return (int) Math.min(3f, p * 4f);
    }

    private void fadeHandle(float p) {
        if (handle != null) {
            // showHandle=false 时把条本体隐藏，但仍保留上滑手势（透明可拖）
            // 上升过程中只轻微淡出：条要跟着弧线一起升，得看得见
            // handleAlpha 是设置页里的「小白条透明度」
            float visible = config.showHandle ? config.handleAlpha : 0f;
            handle.setAlpha(Math.max(0f, visible * (1f - 0.5f * p)));
        }
        if (hint != null && hint.getVisibility() == View.VISIBLE) {
            // 上方小字跟条一起淡出，拖动时不至于糊在内容上
            hint.setAlpha(Math.max(0f, 1f - 0.6f * p));
        }
    }

    /** 刷新小白条上方的自定义文字（内容 + 位置 + 字号 / 粗细 / 字体 / 颜色）。 */
    private void updateHint() {
        if (hint == null) {
            return;
        }
        String text = config.hintText == null ? "" : config.hintText.trim();
        if (text.isEmpty()) {
            hint.setText("");
            hint.setVisibility(View.GONE);
            return;
        }
        if (!text.equals(hint.getText())) {
            hint.setText(text);
        }
        hint.setVisibility(View.VISIBLE);

        float density = window.getResources().getDisplayMetrics().density;

        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.hintSize);
        hint.setTextColor(config.hintColor);
        try {
            hint.setTypeface(android.graphics.Typeface.create(
                    typefaceFor(config.hintFont), config.hintWeight, false));
        } catch (Throwable t) {
            hint.setTypeface(typefaceFor(config.hintFont));
        }

        // 位置：与小白条的垂直间距（条上方 36dp 触控盒之外）+ 水平偏移
        ViewGroup.LayoutParams lp = hint.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            int bottom = (int) ((HANDLE_HEIGHT_DP + config.hintGap) * density);
            if (((FrameLayout.LayoutParams) lp).bottomMargin != bottom) {
                ((FrameLayout.LayoutParams) lp).bottomMargin = bottom;
                hint.setLayoutParams(lp);
            }
        }
        float tx = config.hintOffsetX * density;
        if (hint.getTranslationX() != tx) {
            hint.setTranslationX(tx);
        }
    }

    /**
     * 字体索引 → 系统字体族名。
     *
     * <p>0 系统默认（HyperOS 上就是 MiSans）、1 衬线、2 等宽、3 手写、4 窄体。
     * 粗细另外由 {@code hint_weight} 控制，不跟字体族混在一起。
     */
    private static android.graphics.Typeface typefaceFor(int font) {
        String family;
        switch (font) {
            case 1:
                family = "serif";
                break;
            case 2:
                family = "monospace";
                break;
            case 3:
                family = "cursive";
                break;
            case 4:
                family = "sans-serif-condensed";
                break;
            default:
                family = "sans-serif";
                break;
        }
        try {
            return android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL);
        } catch (Throwable t) {
            return android.graphics.Typeface.DEFAULT;
        }
    }

    private void haptic(int constant) {
        if (!config.haptic || handle == null) {
            return;
        }
        try {
            // 不加 FLAG_IGNORE_GLOBAL_SETTING：跟随系统「触感反馈」开关
            handle.performHapticFeedback(constant);
        } catch (Throwable t) {
            // 某些 ROM 会拒绝，忽略即可
        }
    }

    // ------------------------------------------------------------------ 动画

    private void animateTo(float target) {
        cancelAnimator();
        float from = progress;
        if (from == target) {
            if (target == 0f) {
                apply(0f);
                fadeHandle(0f);
            }
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(from, target);
        a.setDuration(RETURN_MS);
        a.setInterpolator(SMOOTHSTEP);
        a.addUpdateListener(animation -> {
            progress = (Float) animation.getAnimatedValue();
            apply(progress);
            fadeHandle(progress);
        });
        animator = a;
        a.start();
    }

    private void cancelAnimator() {
        ValueAnimator a = animator;
        if (a != null) {
            animator = null;
            a.cancel();
        }
    }

    // ------------------------------------------------------------------ 配置变更

    @Override
    public void onConfigChanged(LockConfig c) {
        // 远端配置的回调可能不在主线程
        window.post(() -> {
            updateHandleWidth();
            if (rim != null) {
                rim.configure(c.arcWidth, c.arcAlpha, c.arcColor, c.arcDim, c.dimStrength);
            }
            updateHint();
            fadeHandle(progress);
            lastApplied = Float.NaN;
            sync();
            if (overlay != null && overlay.getVisibility() == View.VISIBLE) {
                apply(progress);
                fadeHandle(progress);
            } else if (progress != 0f) {
                reset();
            }
        });
    }

    /**
     * 「扩大触控范围」开关切换后重排小白条触控盒宽度。
     *
     * <p>宽度只是命中区：条本体在子 View / 自绘里都按屏幕居中，全宽也不改变外观。
     */
    private void updateHandleWidth() {
        if (handle == null) {
            return;
        }
        int want = config.handleWide
                ? ViewGroup.LayoutParams.MATCH_PARENT
                : Math.max(1,
                        (int) (handle.systemBarWidth()
                                + 2f * HANDLE_H_PAD_DP
                                * window.getResources().getDisplayMetrics().density));
        ViewGroup.LayoutParams lp = handle.getLayoutParams();
        if (lp != null && lp.width != want) {
            lp.width = want;
            handle.setLayoutParams(lp);
        }
    }

    private void log(String msg, Throwable t) {
        XposedInterface m = module;
        if (m != null) {
            m.log(4, TAG, msg, t);
        }
    }
}
