package com.lockbar.app.xposed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
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
    /**
     * 控制中心是否展开中：true = 控制中心开着，整套上滑系统让路 —— 拦截不再吞事件、
     * 小白条这次按下不消费（1.0.22 反馈：开着控制中心时上滑被模块劫持）。
     * 两条 hook 路径汇到这里（见 {@link #onControlCenterOpen}），volatile 供触线程读。
     */
    private static volatile boolean sControlCenterOpen;
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
    /** 组件专用图层：插在锁屏内容之下，通知/时钟自然盖在它上面。 */
    private FrameLayout widgetLayer;
    private LockRimView rim;
    /** 液态玻璃描边条（设置项「液态玻璃实时渲染弧形描边」，1.0.22）。 */
    private LockGlassStrip strip;
    private LockHandleView handle;
    /** 小白条上方的自定义文字（配置 hint_text 为空时不显示）。 */
    private TextView hint;
    /** 锁屏右上方小白条（下拉打开控制中心）。 */
    private View topRightBar;
    private float topRightBarBaseY = Float.NaN;
    private boolean topRightBarPullActive;
    private float topRightBarDownY;
    /** 锁屏右边缘下拉控制中心的系统手势是否被我们屏蔽。 */
    private boolean blockCcEdgePull;
    /** 上次启动右缘拦截的时刻：防止标志残留到解锁后。 */
    private long blockCcEdgeAt;
    /** 右半屏顶部按下：可能是右缘下拉控制中心，等它真的向下滑再拦。 */
    private boolean ccEdgeCandidate;
    /** 控制中心是不是由右上方小白条打开的（区分系统右缘下拉）。 */
    private boolean topRightHandleOpenedCc;

    /** 锁屏小组件（天气 / 步数 / 电池 三选一的单件）。 */
    private LockWidgetView lockWidgets;
    /** 上一次拉取小组件数据的时刻（ms，uptime）。 */
    private long lastWidgetDataAt;
    private long lastWidgetLog;
    /** 正在后台拉数据：避免叠加查询。 */
    private boolean widgetDataLoading;
    /** 被缩小的时钟内容容器（blur_blend_container）。 */
    private View clockScaleA;
    private View clockScaleB;
    private float clockShrinkNow = 1f;
    /** 通知堆叠容器缓存（每次 sync 都遍历整个窗口太贵）。 */
    private View notifStackCache;
    private long notifStackAt;
    /** 摆位节流：同一帧里反复计算没意义。 */
    private long lastPositionAt;
    /** 锁屏页面当前的整体位移（与 keyguardRoot 同一个 ty）。 */
    private float pageTy;
    /** 避让通知产生的额外位移（带动画，避免硬跳）。 */
    private float widgetExtra;
    /** 通知整体的额外位移（负数 = 上移）：小组件占到通知区时把通知顶上去。 */
    private float notifExtra;
    /** 我们是否曾经向系统容器写过非 0 平移（用于只归还一次）。 */
    private boolean sharedNotifOwned;
    private boolean panelOwned;
    /** 上次量到的通知卡片数（用来决定上移量上限）。 */
    private int notifCardCount;
    private android.animation.ValueAnimator notifExtraAnim;
    private android.animation.ValueAnimator widgetExtraAnim;
    /** 时钟当前是否处于被压短状态（做回差，避免反复抖）。 */
    private boolean clockShrunk;

    /** 电池/插拔电广播：让小组件实时刷新（不再等 30s 节流）。 */
    private android.content.BroadcastReceiver widgetReceiver;

    /** 小组件数据的刷新间隔（ms）：天气 / 步数都是分钟级数据，30 秒足够。 */
    private static final long WIDGET_DATA_MS = 30_000L;

    private View keyguardRoot;
    private View sharedNotif;
    private View notificationPanel;
    private View bouncer;
    /**
     * 上一次 {@link #sync()} 看到的「密码盘在显示」（{@code keyguard_bouncer_container}）。
     *
     * <p>只在「由显到隐」的那一刻用它判定「盘被收起、锁屏回来了」，用来把上滑进密码页时
     * 留下的钉顶状态收掉（见 {@link #resetPinnedIfKeyguardBack()}）。
     */
    private boolean bouncerWasShowing;

    private LockGlass.Clip clipRoot = new LockGlass.Clip();
    private LockGlass.Clip clipNotif = new LockGlass.Clip();
    private LockGlass.Clip clipPanel = new LockGlass.Clip();

    /** 当前进度 0（复位）..1（拉满）。 */
    private float progress;
    /** 上一次 apply 的进度，用于避免重复写 translationY。 */
    private float lastApplied = Float.NaN;
    /** 背景模糊：上次写进 view 的半径（px）；-1 = 当前没挂效果。半径没变就不重建 effect。 */
    private int lastBlurRadius = -1;
    /** 全屏壁纸盖层：铺在 window 最底层（index 0），盖住系统壁纸窗口那张真正垫底的壁纸。 */
    private WallCoverView cover;
    /** 景深主体上的玻璃补画层（depthband1）：只画「玻璃条 AND 主体剪影」，归零即拆。 */
    private DepthBandPass depthBand;
    /** 景深主体的毛玻璃影子层（depthband2）：弧线糊侧镜像主体并挂同款模糊，归零即拆。 */
    private final java.util.ArrayList<DepthFrostView> depthFrost = new java.util.ArrayList<>();
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
    /**
     * 解锁收尾中标记：内容钉在屏幕顶上，<b>不让 {@link #sync()} 把它归零</b>。
     *
     * <p>1.0.22 反馈「框不会随手势上去，而是下来了再进桌面」的病因就是归零归早了：
     * keyguardDone 之后锁屏还要淡出几百毫秒，这几帧里 {@code sync()} 走到
     * {@code !onKeyguard} 分支 {@code reset()}，{@code apply(0f)} 把内容从 -0.96×屏高
     * 当帧拽回原位 —— 用户看到的就是内容/弧框往下掉、桌面才慢半拍透出来。
     *
     * <p>复位点：锁屏视图真的不再 shown（{@link #sync()}，归零已看不见）、
     * 新一次手势（{@link #onDragStart}，手指接管 progress）。{@link #reset()} 里也顺手清，
     * 保证任何归位路径都不会把标记漏到下一锁屏周期。
     *
     * <p>keepup2 起成败一律置位：show() 被拒最常见的原因是人脸/指纹抢先、系统已在
     * 退场，此时回弹必然和退场撞车（「掉下来才进桌面」第二股）。真没解锁时手指
     * 往下一拖就跟随回来，或下次息屏亮屏由 {@link #sync()} 自动归零。
     */
    private boolean unlocking;

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

    /**
     * 上升在手指/动画手里（fpshield1 的闸）：把手拖动、被吞的屏内上滑两条入口都置位，
     * 飞升段由 {@link #unlocking} 换防（{@link #finishUnlock()} 里无缝交接）。
     * 与 {@link #liftTracking} 分开：那个只管「吞过 MOVE 的屏内手势」的收尾语义，
     * 把手路径根本不经过它 —— 混用会在把手拖升时漏屏蔽指纹。
     */
    private boolean fingerRising;
    /**
     * 本轮上升的起点（{@link SystemClock#uptimeMillis()}，0 = 没有在上升）。
     *
     * <p>fpshield5 用它给「上升期间压住指纹会话」加一个 5s 上限：万一
     * {@link #fingerRising} 因为异常路径没归零，超时后不再压 —— 宁可漏屏蔽，
     * 也绝不把指纹永久压死（违反密码盘纪律）。
     */
    private long fingerRisingAt;
    /**
     * fpshield3：结果侧宽限截止（{@link SystemClock#uptimeMillis()}，0 = 不吞）。
     *
     * <p>为什么单靠 {@link #fingerRising} 不够：本代光学指纹的认证在 HAL 侧自主完成，
     * 一次按压从触摸到成功要 200-500ms —— 上滑往往已经结束、{@link #fingerRising} 也
     * 清零了，结果才回来。按「当前是否在上升」判闸，这一下就漏成解屏（实测反馈
     * 「上滑过程中能识别指纹」）。所以上升一开始/结束时都上膛一个短宽限窗口，让迟到
     * 的那一下结果照样被吞；新的一次触摸（{@link #preTouch} 的 DOWN 或指纹入口按下时
     * 闸门已关）立刻撤膛 —— 中止手势后再按指纹照常有反应。
     */
    private long fpResultUntil;
    /** fpshield3：上升结束后结果侧继续吞的宽限，覆盖光学指纹的认证延迟。 */
    private static final long FP_RESULT_GRACE_MS = 1000L;
    /**
     * fpshield5：上升期间压住指纹会话的最长时间。
     *
     * <p>只是「标志卡住时别把指纹永久压死」的兜底，正常手势几百毫秒就结束了。
     */
    private static final long FP_SILENCE_MAX_MS = 5000L;
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
     * {@code UdfpsController#onTouch} 前置回调（fpshield1）：上升动画期间屏蔽指纹识别。
     *
     * <p>用户反馈「解锁页上升时按指纹区会触发识别」——识别与上升动画撞车。指纹触摸的
     * 总闸是 {@code UdfpsController#onTouch}：锁屏图标的监听器（UdfpsKeyguardViewController
     * 的内类）和 MIUI 指纹浮层窗口（独立窗口，我们窗口层的钩子根本看不见它）两条路都
     * 汇进这一个方法（OS1 反编译实锤），在这里挡一次全挡。
     *
     * @return true = 吃掉这次触摸（识别不启动）；false = 照常放行
     */
    static boolean shouldBlockUdfpsTouch() {
        LockBarController c = sInstance;
        if (c == null) {
            return false;
        }
        return c.blockUdfpsTouch();
    }

    /**
     * 屏蔽判定：模块关着不拦；密码盘可见不拦（钉顶挂着时盘才是主交互面，盘上指纹
     * 照常工作）；只拦「正在上升」——手指拖着升（把手 / 被吞的屏内上滑）与松手后的
     * 飞升 / 解锁钉顶。松手不进解锁、回弹、归位各分支都已即时放行。
     */
    private boolean blockUdfpsTouch() {
        if (!config.enabled) {
            return false;
        }
        if (bouncer != null && bouncer.getVisibility() == View.VISIBLE) {
            return false;
        }
        return fingerRising || unlocking;
    }

    /**
     * 结果侧闸（fpshield3）：比 {@link #blockUdfpsTouch()} 多一个宽限窗口。
     *
     * <p>入口闸只认「此刻在不在上升」；认证结果却常在上升结束之后才回来（HAL 侧自主
     * 认证有 200-500ms 延迟），所以结果侧必须多吞一个短窗口，否则抬手那一下正好漏成
     * 解屏。宽限只由本模块的上升动作上膛（见 {@link #armFpResult()}），新的一次触摸会
     * 立刻撤膛，正常按指纹不受影响。
     */
    static boolean shouldBlockFpResult() {
        LockBarController c = sInstance;
        if (c == null) {
            return false;
        }
        return c.blockFpResult();
    }

    private boolean blockFpResult() {
        if (!config.enabled) {
            return false;
        }
        if (bouncer != null && bouncer.getVisibility() == View.VISIBLE) {
            return false;
        }
        return fingerRising || unlocking
                || (fpResultUntil != 0 && SystemClock.uptimeMillis() < fpResultUntil);
    }

    /**
     * 结果侧闸门「为什么」开（诊断用，fpshield4）。
     *
     * <p>日志里一眼看出是还在上升、飞升/解锁中，还是迟到的结果落进了宽限窗口 ——
     * 分诊时不用再猜。只读判定，不改任何状态；条件与 {@link #blockFpResult()} 一一对应。
     */
    static String fpGateReason() {
        LockBarController c = sInstance;
        if (c == null) {
            return "无控制器";
        }
        return c.describeFpGate();
    }

    private String describeFpGate() {
        if (!config.enabled) {
            return "模块关闭";
        }
        if (bouncer != null && bouncer.getVisibility() == View.VISIBLE) {
            return "密码盘可见";
        }
        if (fingerRising) {
            return "上升中";
        }
        if (unlocking) {
            return "解锁中";
        }
        if (fpResultUntil != 0 && SystemClock.uptimeMillis() < fpResultUntil) {
            return "宽限窗口";
        }
        return "放行";
    }

    /**
     * 上升期是否要把「开指纹会话」压住（fpshield5）。
     *
     * <p>fpshield4 实测暴露的新问题：上滑期间每按一次指纹，识别都会成功一次 → 被我们吞掉
     * → 再立刻换防重开 → 用户连按就一路循环（日志里 1 秒内 4~8 轮）。MIUI 的 FOD 侧从来
     * 看不到成功回调（MIUI 的 {@code onPreBiometricAuthenticated} 就在被吞掉的那个方法
     * 里），于是每一轮都被它记成一次「没结果」，用户连按几下就撞上「指纹识别次数上限」。
     *
     * <p>治本的做法不是吞结果，而是<b>上升期间干脆不让会话起来</b>：这时按压根本不会产生
     * 认证 —— 既不消耗次数，也没有结果要吞。只在 {@link #fingerRising} 期间压（手势级、
     * 几百毫秒）；{@link #unlocking} 不压，飞升/钉顶阶段仍由结果侧闸兜住 —— 免得
     * {@code unlocking} 万一没归零把指纹永久压死。
     *
     * <p>再加 {@link #FP_SILENCE_MAX_MS} 上限：标志卡住时超时即不再压（漏屏蔽可以接受，
     * 指纹永久失效不可以）。
     */
    static boolean isFpSilenced() {
        LockBarController c = sInstance;
        if (c == null || !c.fingerRising) {
            return false;
        }
        long since = c.fingerRisingAt;
        if (since == 0) {
            return true;
        }
        return SystemClock.uptimeMillis() - since <= FP_SILENCE_MAX_MS;
    }

    /** 上升开始/结束：结果侧上膛，覆盖迟到的那一下认证结果。 */
    private void armFpResult() {
        fpResultUntil = SystemClock.uptimeMillis() + FP_RESULT_GRACE_MS;
        if (fingerRising) {
            // 上升开始：记下起点，fpshield5 的「压住上限」按它算
            fingerRisingAt = SystemClock.uptimeMillis();
        }
    }

    /** 新的一次触摸：闸门已关 = 用户主动按指纹，撤膛（中止手势后再按必须有反应）。 */
    private void disarmFpResult() {
        if (!fingerRising && !unlocking) {
            fpResultUntil = 0;
        }
    }

    /**
     * 指纹入口按下（每一代 UDFPS 入口都上报，闸门开没开都要报）。
     *
     * <p>上升期按下 = 这次认证是上滑带出来的，它的结果要吞；闸门关着按下 = 用户主动
     * 按指纹，撤膛放行。两条合成一条规则，既覆盖「上升中按」，也不误伤「中止后按」。
     */
    static void noteUdfpsDown() {
        LockBarController c = sInstance;
        if (c == null) {
            return;
        }
        if (c.fingerRising || c.unlocking) {
            c.armFpResult();
            ModuleLog.d("指纹入口按下：上升中 → 结果侧上膛");
        } else {
            c.disarmFpResult();
            ModuleLog.d("指纹入口按下：闸门关 → 结果侧撤膛");
        }
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
                // 新手势按下：上一次上滑的结果宽限作废（用户又在主动操作了，fpshield3）
                disarmFpResult();
                // 上一次手势如果还悬着（回弹中途又按下），先把时间放回原位
                clearLift();
                // 锁屏右上方小白条 / 右缘控制中心屏蔽
                topRightBarPullActive = false;
                blockCcEdgePull = false;
                ccEdgeCandidate = false;
                if (config.topRightHandle && sKeyguardState != 0
                        && overlay != null && overlay.getVisibility() == View.VISIBLE) {
                    if (inTopRightBar(downX, downY)) {
                        topRightBarPullActive = true;
                        topRightBarDownY = downY;
                        topRightBarBaseY = topRightBar.getTranslationY();
                    } else if (window.getWidth() > 0 && window.getHeight() > 0
                            && downX > window.getWidth() * 0.5f
                            && downY < window.getHeight() * 0.45f) {
                        // 右半屏顶部按下：可能是右缘下拉控制中心。等它真的向下滑再拦，
                        // 避免把底部解锁上滑（右半屏往上划）也吞掉。
                        ccEdgeCandidate = true;
                        ModuleLog.d("锁屏右上方小白条：右缘候选 @"
                                + Math.round(downX) + "," + Math.round(downY));
                    }
                }
                return TOUCH_PASS;

            case MotionEvent.ACTION_MOVE: {
                if (topRightBarPullActive) {
                    float dy = ev.getY() - topRightBarDownY;
                    if (dy > 0f) {
                        // 弹性跟手：位移按配置倍率，松手再回弹
                        topRightBar.setTranslationY(topRightBarBaseY + dy * config.topRightBarElastic);
                    }
                    return TOUCH_SWALLOW;
                }
                if (!blockCcEdgePull && ccEdgeCandidate
                        && (ev.getY() - downY) > touchSlop()) {
                    blockCcEdgePull = true;
                    blockCcEdgeAt = SystemClock.uptimeMillis();
                    ModuleLog.d("锁屏右上方小白条：开始拦截右缘下拉控制中心");
                }
                if (blockCcEdgePull) {
                    return TOUCH_SWALLOW;    // 吞掉右缘下拉，系统控制中心不开
                }
                if (downInHandle) {
                    return TOUCH_PASS;
                }
                // 必须真的是“往上滑”才拦，否则点一下（带点抖动）也会被打断
                if (!blockSystemSwipe
                        && (downY - ev.getY()) > touchSlop()
                        && shouldBlockSystemSwipe()) {
                    blockSystemSwipe = true;
                    liftTracking = true;
                    fingerRising = true;    // 屏内上滑升起来了：期间屏蔽指纹（fpshield1）
                    armFpResult();          // 结果侧同步上膛（fpshield3）：认证结果常晚于抬手
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
                if (topRightBarPullActive) {
                    topRightBarPullActive = false;
                    float dy = ev.getY() - topRightBarDownY;
                    // 弹性归位
                    if (topRightBar != null) {
                        topRightBar.animate().cancel();
                        topRightBar.animate().translationY(0f)
                                .setDuration(280L)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f))
                                .start();
                    }
                    if (dy > 120f * density()) {
                        topRightHandleOpenedCc = true;
                        LockBarHooks.openControlCenter();
                    }
                    return TOUCH_SWALLOW;
                }
                if (blockCcEdgePull) {
                    blockCcEdgePull = false;
                    ccEdgeCandidate = false;
                    return TOUCH_PASS;
                }
                ccEdgeCandidate = false;
                if (liftTracking) {
                    liftTracking = false;
                    fingerRising = false;   // 手离屏：这条屏内上滑到此为止，指纹恢复
                    armFpResult();          // 但结果侧再宽限一小段：认证结果可能还在路上（fpshield3）
                    // fpshield5：上升期间指纹会话是被压住的，手势一收必须补回来，
                    // 否则「中止手势后按指纹」没反应
                    LockBarHooks.requestFpRearmSoon();
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

    private float density() {
        return window.getResources().getDisplayMetrics().density;
    }

    /** 落点是否在锁屏右上方小白条的触控区（含 20dp 外扩）。 */
    private boolean inTopRightBar(float x, float y) {
        if (topRightBar == null || topRightBar.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] loc = new int[2];
        topRightBar.getLocationInWindow(loc);
        float pad = 20f * density();
        float left = loc[0] - pad;
        float top = loc[1] - pad;
        float right = loc[0] + topRightBar.getWidth() + pad;
        float bottom = loc[1] + topRightBar.getHeight() + pad;
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    /** 按配置刷新右上方小白条尺寸与位置；能找到锁屏状态栏就垂直跟随它。 */
    private void positionTopRightBar() {
        if (topRightBar == null) {
            return;
        }
        float d = density();
        int y = (int) (config.topRightBarY * d);
        View sb = find("keyguard_status_bar");
        if (sb != null && sb.getVisibility() == View.VISIBLE && sb.getHeight() > 0) {
            int[] loc = new int[2];
            sb.getLocationInWindow(loc);
            // 跟随状态栏：Y 变成「相对状态栏顶部的偏移」
            y = loc[1] + (int) (config.topRightBarY * d);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topRightBar.getLayoutParams();
        lp.width = Math.max(1, (int) (config.topRightBarLength * d));
        lp.height = Math.max(1, (int) (config.topRightBarThickness * d));
        lp.rightMargin = (int) (config.topRightBarX * d);
        lp.topMargin = y;
        topRightBar.setLayoutParams(lp);
        topRightBar.setAlpha(config.topRightBarOpacity);
    }

    // ------------------------------------------------------------ 锁屏小组件（三选一）

    /**
     * 摆位：锚在「时钟看得见的那部分」的底边 + 配置下移量，水平居中。
     *
     * <p>不用时钟容器的 bottom —— 那玩意儿在本 ROM 是整屏大容器，直接取 bottom 会把小组件
     * 甩到屏幕最下面（压到勿扰模式那排上，上一版就是这么翻车的）。所以先找时钟里真正有内容的
     * 子视图的底边；实在找不到才退到屏幕 30% 处，并且整体夹在 15%~55% 屏高之间兜底。
     */
    /**
     * 摆位：节流 120ms，并且只在“真正变化”时重算 / 重设布局。
     *
     * <p>这个方法跑在 sync() 里，而 sync 在滑动、通知变化、屏幕亮灭时都会调；
     * 每次都遍历时钟+通知子树会直接掉帧。
     */
    private void positionLockWidgets() {
        positionLockWidgets(false);
    }

    private void positionLockWidgets(boolean force) {
        if (lockWidgets == null) {
            return;
        }
        // 手势进行中不碰布局
        if (lift != 0f || progress != 0f) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastPositionAt < 300L) {
            return;
        }
        lastPositionAt = now;

        float d = density();
        int widgetH = Math.round(config.lockWidgetSize * d);
        int offset = (int) (config.lockWidgetY * d);
        int gap = (int) (8f * d);

        int clockTop = clockContentTop();
        int clockBottom = clockContentBottom();
        int notifTop = notificationTop();

        // 不再压短时钟（之前那套无论有没有通知都在缩，就是你说的「始终缩小」）。
        applyClockShrink(1f);
        if (clockShrinkNow < 0.999f) {
            clockBottom = clockContentBottom();
        }

        lockWidgets.setTranslationX(config.lockWidgetX * d);

        int y = clockBottom + offset;
        y = Math.max(0, Math.min(Math.max(0, window.getHeight() - widgetH), y));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lockWidgets.getLayoutParams();
        if (lp.topMargin != y) {
            lp.topMargin = y;
            lockWidgets.setLayoutParams(lp);
        }
        updateWidgetGlassOrigin();
        updateNotifOffset(y, widgetH, clockTop, gap);
        if (now - lastWidgetLog > 20000L) {
            lastWidgetLog = now;
            ModuleLog.d("widget y=" + y + " clock=" + clockTop + ".." + clockBottom
                    + " h=" + widgetH + " z=" + widgetZIndex()
                    + " glass=" + lockWidgets.diag());
        }
    }

    /**
     * 按小组件占位决定通知要不要上移。
     *
     * <p>规则（取自你的要求）：
     * 小组件在下方、且占到了通知区 → 通知整体上移到小组件上面；
     * 小组件在上方（不影响通知）或面板已展开 → 通知不动。
     */
    private void updateNotifOffset(int widgetTop, int widgetH, int clockTop, int gap) {
        float wanted = 0f;
        int measured = notificationTop();
        if (measured != Integer.MAX_VALUE) {
            // notificationTop() 已经返回“未平移”的基准坐标
            int baseTop = measured;
            // 堆叠展开后卡片会跑到屏幕上部：这时不上移，
            // 让通知回到原位（阈值取 32% 屏高，涵盖展开后的堆叠卡片）
            boolean expanded = baseTop < (int) (window.getHeight() * 0.32f);
            if (!expanded) {
                int widgetBottom = widgetTop + widgetH;
                int overlap = widgetBottom + gap - baseTop;
                if (overlap > 0) {
                    // 多条通知时整列一起上移会看着太多，
                    // 上移量封顶：单条 = 小组件高 + 间隙，多条 = 55%
                    float cap = notifCardCount >= 2 ? (widgetH + gap) * 0.55f : (widgetH + gap);
                    wanted = -Math.min(overlap, cap);
                }
            }
        }
        // 死区：差异小于 8dp 就不动，避免在阈值附近反复微调
        if (Math.abs(wanted - notifExtra) < 8f * density() && wanted * notifExtra >= 0f) {
            return;
        }
        applyNotifExtra(wanted);
    }

    /** 通知位移带 160ms 过渡，避免硬跳。 */
    private void applyNotifExtra(float extra) {
        if (Math.abs(extra - notifExtra) < 0.5f) {
            return;
        }
        if (notifExtraAnim != null) {
            notifExtraAnim.cancel();
        }
        android.animation.ValueAnimator a =
                android.animation.ValueAnimator.ofFloat(notifExtra, extra);
        a.setDuration(160L);
        a.addUpdateListener(animation -> {
            notifExtra = (Float) animation.getAnimatedValue();
            applyNotifTranslation();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (notifExtraAnim == a) {
                    notifExtraAnim = null;
                }
            }
        });
        notifExtraAnim = a;
        a.start();
    }

    /** 把 pageTy + notifExtra 合成到通知堆叠容器上。 */
    /**
     * 通知容器的 translationY 是**系统自己的动画参数**（下拉展开、通知插入
     * 都在写它）。我们覆盖它会直接搞崩新通知的布局与可见性：
     * 实测就是“来了通知不显示，上滑再下滑才出来”。
     * 所以这里只保留“页面平移”那一路（与模块原有行为一致），
     * 不再叠加 notifExtra。
     */
    /**
     * 通知容器与面板的 translationY 是 **SystemUI 自己的动画参数**（下拉展开、
     * 通知插入都在写）。无论我们写什么值，只要频繁重写，
     * 就会打断它的展开动画 —— 表现就是“通知中心时好时坏地空白”。
     *
     * <p>所以改成受控写入：只有我们确实需要偏移（页面上滑 / 避让）时才写，
     * 一旦回到 0 就归还一次，之后完全交给系统，不再每次 sync 都去覆盖它。
     */
    private void applyNotifTranslation() {
        if (sKeyguardState == 0) {
            return;   // 不在锁屏：不碰系统这两个容器
        }
        float notifTarget = pageTy + notifExtra;
        if (Math.abs(notifTarget) > 0.5f) {
            setTranslation(sharedNotif, notifTarget);
            sharedNotifOwned = true;
        } else if (sharedNotifOwned) {
            setTranslation(sharedNotif, 0f);
            sharedNotifOwned = false;
        }

        if (Math.abs(pageTy) > 0.5f) {
            setTranslation(notificationPanel, pageTy);
            panelOwned = true;
        } else if (panelOwned) {
            setTranslation(notificationPanel, 0f);
            panelOwned = false;
        }
    }

    private static boolean isDescendant(View child, View ancestor) {
        android.view.ViewParent p = child.getParent();
        while (p != null) {
            if (p == ancestor) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }

    /** 小组件图层当前在窗口里的索引（供日志看 z 序）。 */
    private int widgetZIndex() {
        return overlay == null || window == null ? -1 : window.indexOfChild(overlay);
    }

    /**
     * 把小组件图层保持在锁屏内容下面：
     * 通知、时钟都在 keyguard_root_view 里，它们自然盖在小组件上面。
     */
    private void ensureWidgetLayerOrder() {
        if (widgetLayer == null || window == null) {
            return;
        }
        View kg = find("keyguard_root_view");
        if (kg == null) {
            kg = keyguardRoot;
        }
        if (kg == null) {
            return;
        }
        int kgIdx = window.indexOfChild(kg);
        int myIdx = window.indexOfChild(widgetLayer);
        if (kgIdx < 0 || myIdx < 0 || myIdx < kgIdx) {
            return;   // 已经在下面了
        }
        try {
            window.removeView(widgetLayer);
            window.addView(widgetLayer, kgIdx,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable ignored) {
            // 重排失败就维持原样
        }
    }

    /** 通知盖住组件时淡出（相当于置于底层）。 */
    private void setWidgetCovered(boolean covered) {
        if (lockWidgets == null) {
            return;
        }
        float target = covered ? 0f : 1f;
        if (Math.abs(lockWidgets.getAlpha() - target) < 0.01f) {
            return;
        }
        lockWidgets.animate().cancel();
        lockWidgets.animate().alpha(target).setDuration(120L).start();
    }

    private void setWidgetShown(boolean shown) {
        if (lockWidgets == null) {
            return;
        }
        float a = shown ? 1f : 0f;
        if (lockWidgets.getAlpha() != a) {
            lockWidgets.setAlpha(a);
        }
    }

    /** 时钟可见内容的顶边（窗口坐标）：与 clockContentBottom 同一套判定。 */
    private int clockContentTop() {
        int limit = (int) (window.getHeight() * 0.70f);
        int best = Integer.MAX_VALUE;
        best = Math.min(best, visualTop(clockRoot, limit, 0));
        best = Math.min(best, visualTop(clockSecond, limit, 0));
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    private int visualTop(View v, int heightLimit, int depth) {
        if (v == null || depth > 12 || v.getVisibility() != View.VISIBLE) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        if (v.getWidth() > 0 && v.getHeight() > 0 && v.getHeight() <= heightLimit) {
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            best = loc[1];
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                best = Math.min(best, visualTop(g.getChildAt(i), heightLimit, depth + 1));
            }
        }
        return best;
    }

    /**
     * 缩放时钟内容（blur_blend_container）：小组件占位时时钟自动变小让位。
     *
     * <p>刻意不缩放整个 miui_keyguard_clock_container：那是整屏容器，
     * 缩它会把背景与通知一起带歪。只缩真正画时钟的那层。
     */
    private void applyClockShrink(float factor) {
        if (Math.abs(factor - clockShrinkNow) < 0.01f) {
            return;
        }
        clockShrinkNow = factor;
        if (clockScaleA == null) {
            clockScaleA = findByIdIn(clockRoot, "blur_blend_container");
        }
        if (clockScaleB == null) {
            clockScaleB = findByIdIn(clockSecond, "blur_blend_container");
        }
        scaleClockView(clockScaleA, factor);
        scaleClockView(clockScaleB, factor);
    }

    private void scaleClockView(View v, float factor) {
        if (v == null) {
            return;
        }
        try {
            // 只压缩纵向：时钟变短，字号宽度不变
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(0f);
            // 缩放期间挂硬件图层：变形交给 GPU，不重新光栅化
            if (factor < 0.999f && v.getLayerType() != View.LAYER_TYPE_HARDWARE) {
                v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
            v.setScaleY(factor);
            if (factor >= 0.999f && v.getLayerType() != View.LAYER_TYPE_NONE) {
                v.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        } catch (Throwable ignored) {
            // 视图被回收之类：忽略
        }
    }

    /** 按 id 名在子树里找第一个命中（同一 id 在主/副时钟容器里各有一份）。 */
    private View findByIdIn(View root, String name) {
        if (root == null) {
            return null;
        }
        try {
            int id = window.getResources().getIdentifier(name, "id", PKG);
            if (id != 0) {
                return root.findViewById(id);
            }
        } catch (Throwable ignored) {
            // 没有这个 id 就算了
        }
        return null;
    }

    /**
     * 通知列表里第一条可见通知的顶边（窗口坐标）；没有通知返回 MAX_VALUE。
     *
     * <p>不能只认 id：HyperOS 的通知堆叠容器叫
     * {@code NotificationStackScrollLayout}，id 名各机型不一样（之前
     * {@code findFirst} 两个 id 都没命中 → notifTop 恒为 MAX，避让形同虚设）。
     * 这里改成按类名遍历找堆叠容器，再取它子视图里最高的那条。
     */
    private int notificationTop() {
        View stack = stackView();
        if (stack == null) {
            return Integer.MAX_VALUE;
        }
        // 排除状态栏/顶部悬浮通知（banner）区：只认真正的列表卡片
        int min = (int) (window.getHeight() * 0.14f);
        int best = Integer.MAX_VALUE;
        int count = 0;
        if (stack instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) stack;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || c.getVisibility() != View.VISIBLE || c.getHeight() <= 0) {
                    continue;
                }
                int[] loc = new int[2];
                c.getLocationInWindow(loc);
                // 返回的是“未平移”的基准坐标：先把我们自己的 notifExtra 扣掉，
                // 否则下一轮会把“被我们推上去的位置”当成原始位置
                int top = loc[1] - Math.round(notifExtra);
                if (top < min) {
                    continue;   // 十分钟历/状态栏那些不算通知
                }
                count++;
                best = Math.min(best, top);
            }
        }
        return best;
    }

    /**
     * 通知面板是否已整体展开：**第一张卡片**都跑到时钟上面去了。
     *
     * <p>不能用堆叠容器自身的顶边：它横跨整个屏幕（top≈0），
     * 用它判定会永远得出“已展开”→ 小组件永远不显示。
     */
    private boolean shadeExpanded(int clockTop) {
        int top = notificationTop();
        if (top == Integer.MAX_VALUE) {
            return false;
        }
        return top < clockTop + (int) (8f * density());
    }

    /** 取（带缓存的）通知堆叠容器。 */
    private View stackView() {
        long now = SystemClock.uptimeMillis();
        if (notifStackCache == null || !notifStackCache.isAttachedToWindow()
                || now - notifStackAt > 2000L) {
            notifStackAt = now;
            View found = findStackByClass(window, 0);
            notifStackCache = found != null ? found : sharedNotif;
        }
        return notifStackCache;
    }

    /** 按类名找通知堆叠容器（不依赖 id）。 */
    private View findStackByClass(View v, int depth) {
        if (v == null || depth > 14) {
            return null;
        }
        String name = v.getClass().getSimpleName();
        if (name.contains("NotificationStackScrollLayout")) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findStackByClass(g.getChildAt(i), depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * 时钟「实际画出来那块」的底边（窗口坐标）。
     *
     * <p>不能用时钟容器自身：HyperOS 的 {@code miui_keyguard_clock_container} 是整屏大容器，
     * 取它的 bottom 会把小组件甩到屏幕最下面（上一版就是这么翻车的）；也不能只看 TextView ——
     * 这里的数字时钟是自绘视图，一个 TextView 都没有，结果只量到日期那一行（y≈311px），
     * 小组件就压在数字上了。
     *
     * <p>所以这里取「所有可见、且高度不超过 70% 屏高的子视图」的底边最大值：整屏容器和
     * 满屏背景会被高度门槛排除，数字/日期这两种都会被算进来。
     */
    private int clockContentBottom() {
        int limit = (int) (window.getHeight() * 0.70f);
        int best = Integer.MIN_VALUE;
        best = Math.max(best, visualBottom(clockRoot, limit, 0));
        best = Math.max(best, visualBottom(clockSecond, limit, 0));
        if (best == Integer.MIN_VALUE) {
            best = Math.max(best,
                    visualBottom(find("miui_keyguard_clock_container"), limit, 0));
        }
        if (best == Integer.MIN_VALUE) {
            return (int) (window.getHeight() * 0.30f);
        }
        return best;
    }

    /** 子树里所有「不是整屏」的可见视图的底边最大值。 */
    private int visualBottom(View v, int heightLimit, int depth) {
        if (v == null || depth > 12 || v.getVisibility() != View.VISIBLE) {
            return Integer.MIN_VALUE;
        }
        int best = Integer.MIN_VALUE;
        if (v.getWidth() > 0 && v.getHeight() > 0 && v.getHeight() <= heightLimit) {
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            best = loc[1] + Math.round(v.getHeight() * v.getScaleY());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                best = Math.max(best, visualBottom(g.getChildAt(i), heightLimit, depth + 1));
            }
        }
        return best;
    }

    /**
     * 监听电池与插拔电：电量瞳扇变化、插上/拔下电源时立即重拉数据。
     *
     * <p>之前只靠每 30s 定时拉，电量从 9% 跳到 8%、插上线也要等半分钟才变图标。
     */
    private void installWidgetReceiver(Context ctx) {
        if (widgetReceiver != null) {
            return;
        }
        widgetReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                refreshLockWidgetData(true);
            }
        };
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_BATTERY_CHANGED);
            f.addAction(android.content.Intent.ACTION_POWER_CONNECTED);
            f.addAction(android.content.Intent.ACTION_POWER_DISCONNECTED);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(widgetReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(widgetReceiver, f);
            }
        } catch (Throwable t) {
            widgetReceiver = null;   // 注册失败就退回 30s 定时拉
        }
    }

    /** 后台刷新数据；{@code force} = 不管节流（刚显示时用）。 */
    private void refreshLockWidgetData(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (widgetDataLoading || (!force && now - lastWidgetDataAt < WIDGET_DATA_MS)) {
            return;
        }
        lastWidgetDataAt = now;
        widgetDataLoading = true;
        final Context ctx = window.getContext();
        LockConfig.io(() -> {
            try {
                applyWidgetData(ctx);
            } catch (Throwable ignored) {
                // 数据源不可用时只是这一件不显示，别的功能不受影响
            } finally {
                widgetDataLoading = false;
            }
        });
    }

    /**
     * 拉三份数据，按当前样式喂给视图。
     *
     * <p>三个来源都是系统里已有的接口，不额外申请权限：
     * 电池 = 粘性广播；天气 = {@code content://weather/weather}；步数 = 健康的
     * {@code content://com.mi.health.provider.main/activity/steps/brief}（与系统锁屏步数同源）。
     */
    private void applyWidgetData(Context ctx) {
        // ---- 电池
        int pct = -1;
        boolean charging = false;
        try {
            android.content.Intent batt = ctx.registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (batt != null) {
                int level = batt.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = batt.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                int status = batt.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                int plugged = batt.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
                if (level >= 0 && scale > 0) {
                    pct = Math.round(level * 100f / scale);
                    // ‘插着电源’就算充电：满电/充电保护时系统会报
                    // NOT_CHARGING（status=4），只看 status 的话插着线也不切图标
                    charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                            || status == android.os.BatteryManager.BATTERY_STATUS_FULL
                            || plugged != 0;
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就这一件不显示
        }

        // ---- 天气（第一行 = 今天）
        String temp = "";
        String range = "";
        int weatherType = 1;
        float weatherRatio = 1f;
        try (android.database.Cursor c = ctx.getContentResolver().query(
                android.net.Uri.parse("content://weather/weather"), null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                temp = cursorString(c, "temperature").replace("℃", "°");
                range = cursorString(c, "temperature_range").replace("℃", "°");
                weatherType = cursorInt(c, "weather_type");
                int cur = parseIntSafe(temp.replace("°", "").trim());
                int high = cursorInt(c, "tmphighs");
                int low = cursorInt(c, "tmplows");
                if (high > low && cur != Integer.MIN_VALUE) {
                    weatherRatio = (cur - low) / (float) (high - low);
                }
            }
        } catch (Throwable ignored) {
            // 没装天气 / 没授权：这一件不显示
        }

        // ---- 步数
        String steps = "";
        String energy = "";
        float stepsRatio = 0f;
        try (android.database.Cursor c = ctx.getContentResolver().query(
                android.net.Uri.parse("content://com.mi.health.provider.main/activity/steps/brief"),
                null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = cursorInt(c, "steps");
                int goal = cursorInt(c, "goal");
                int kcal = cursorInt(c, "energy");
                // 跨天归零也要显示：0 步也是有效数据，
                // 之前写成 n>0 才显示，午夜一过整个环就消失
                steps = n >= 10000
                        ? String.format(java.util.Locale.US, "%.1f万", n / 10000f)
                        : String.valueOf(n);
                energy = kcal > 0 ? kcal + " 千卡" : "";
                stepsRatio = goal > 0 ? Math.min(1f, n / (float) goal) : 0.6f;
            }
        } catch (Throwable ignored) {
            // 健康未授权：这一件不显示
        }

        final int fPct = pct;
        final boolean fCharging = charging;
        final String fTemp = temp;
        final String fRange = range;
        final int fType = weatherType;
        final float fWeatherRatio = Math.max(0f, Math.min(1f, weatherRatio));
        final String fSteps = steps;
        final String fEnergy = energy;
        final float fStepsRatio = stepsRatio;

        window.post(() -> {
            LockWidgetView v = lockWidgets;
            if (v == null) {
                return;
            }
            // 三件拼一排：一直同时喂三份数据
            v.setBattery(fPct, fCharging);
            v.setWeather(fTemp, fRange, fType, fWeatherRatio,
                    java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY));
            v.setSteps(fSteps, fEnergy, fStepsRatio);
        });
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private static String cursorString(android.database.Cursor c, String column) {
        try {
            int i = c.getColumnIndex(column);
            if (i < 0) {
                return "";
            }
            String s = c.getString(i);
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static int cursorInt(android.database.Cursor c, String column) {
        try {
            int i = c.getColumnIndex(column);
            return i < 0 ? 0 : c.getInt(i);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------- 状态栏几何（给参数页预览用）

    /**
     * 「信号塔」图标的类名特征（小写比对）。
     *
     * <p>本 ROM 是 {@code MiuiStatusBarMobileView}，但别的 HyperOS / 一加 / 三星机型各有各的
     * 类名，所以只认语义片段，不认全名；聚合容器（{@code *SignalCluster*}）要排除，那是一个
     * 装着信号 + Wi-Fi + 电量的壳，拿它的矩形会把预览标成一大块。
     */
    private static final String[] TOWER_HINTS = {"mobile", "signalview", "networkview", "mobilesignal"};

    /**
     * 预览数据多久回传一次（ms）。
     *
     * <p>状态栏几何是「基本不变的东西」，但 {@code sync()} 在锁屏上会被频繁调用；
     * 每次都遍历一遍状态栏子树纯属浪费主线程，节流到这个间隔足够。
     */
    private static final long LAYOUT_PUBLISH_MS = 2000L;

    private long lastLayoutPublish;

    /**
     * 把锁屏状态栏与信号塔的<b>实测几何</b>回传 App（{@link LockConfig#KEY_DEBUG_LAYOUT}）。
     *
     * <p>坐标全用<b>窗口坐标 px</b>，和 {@code positionTopRightBar()} 里算 {@code topMargin}
     * 的口径完全一致 —— 预览只有和真机用同一套坐标，才不会出现「预览在塔上方、真机在塔下方」。
     * 找不到信号塔时 {@code tL..tB} 全 0，参数页据此退化成只画状态栏高度。
     */
    private void publishLayoutMetrics(View sb) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLayoutPublish < LAYOUT_PUBLISH_MS) {
            return;
        }
        lastLayoutPublish = now;
        try {
            int dpi = Math.round(window.getResources().getDisplayMetrics().density * 160f);
            int sbT = 0;
            int sbH = 0;
            int tL = 0;
            int tT = 0;
            int tR = 0;
            int tB = 0;
            String cls = "";
            if (sb != null && sb.isAttachedToWindow()) {
                int[] loc = new int[2];
                sb.getLocationInWindow(loc);
                sbT = loc[1];
                sbH = sb.getHeight();
                View tower = findSignalTower(sb);
                if (tower != null) {
                    int[] tl = new int[2];
                    tower.getLocationInWindow(tl);
                    tL = tl[0];
                    tT = tl[1];
                    tR = tl[0] + tower.getWidth();
                    tB = tl[1] + tower.getHeight();
                    cls = tower.getClass().getSimpleName();
                }
            }
            String s = "w=" + window.getWidth() + ";h=" + window.getHeight() + ";dpi=" + dpi
                    + ";sbT=" + sbT + ";sbH=" + sbH
                    + ";tL=" + tL + ";tT=" + tT + ";tR=" + tR + ";tB=" + tB + ";cls=" + cls;
            config.putDebug(LockConfig.KEY_DEBUG_LAYOUT, s);
        } catch (Throwable ignored) {
            // 纯预览数据：失败不影响小白条本体，也不该让 sync 抛出去
        }
    }

    /**
     * 在锁屏状态栏子树里找信号塔。
     *
     * <p>取<b>最靠左</b>的那个候选：右上角一排是「信号塔 / Wi-Fi / 电量」，塔永远在最左边，
     * 靠这条相对关系就能在类名完全不知道的 ROM 上稳定选中它，而不用去枚举机型。
     */
    private View findSignalTower(View root) {
        if (root == null) {
            return null;
        }
        java.util.ArrayList<View> hits = new java.util.ArrayList<>();
        collectIconViews(root, hits, 0);
        View best = null;
        int bestLeft = Integer.MAX_VALUE;
        for (int i = 0; i < hits.size(); i++) {
            View v = hits.get(i);
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            if (loc[0] < bestLeft) {
                bestLeft = loc[0];
                best = v;
            }
        }
        return best;
    }

    private static void collectIconViews(View v, java.util.List<View> out, int depth) {
        if (v == null || depth > 12 || v.getVisibility() != View.VISIBLE) {
            return;
        }
        String name = v.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (!name.contains("cluster")) {
            for (int i = 0; i < TOWER_HINTS.length; i++) {
                if (name.contains(TOWER_HINTS[i])) {
                    out.add(v);
                    break;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectIconViews(g.getChildAt(i), out, depth + 1);
            }
        }
    }

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
     * <p>例外两条：① 落点<b>压在通知行上</b>——那一把手势整个归通知，我们绝不插手
     * （翻动、点击、侧滑删除都原样走系统），这条在 {@code DOWN} 时一次性判死
     * （{@link #resolveGestureExempt()}）；② <b>控制中心展开中</b>——锁屏上的上滑归系统
     * （开合控制中心），拦截与小白条整套让路（1.0.22 反馈：开着控制中心时上滑被模块劫持，
     * 关不掉控制中心、底部上滑还误触发解锁）。空白区 / 时钟区一律拦：真机上那些区域的上滑
     * NSSL 不消费，不拦就漏给系统上滑解锁（v1.0.26 反馈）。
     */
    private boolean shouldBlockSystemSwipe() {
        if (!config.interceptSwipe || sKeyguardState == 0) {
            return false;
        }
        // 控制中心开着：这条上滑归系统（用户要开合控制中心），我们整套让路
        if (sControlCenterOpen) {
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
     * <p>判定两条，都是「这把手势不归我们」：① <b>控制中心展开中</b>——系统的开合手势优先，
     * 一步 DFS 都不用走（1.0.22 反馈：开着控制中心时上滑被模块吞掉，关不掉也往上推不动）；
     * ② 按下点<b>压在通知行上</b>（整树 DFS 找 {@code ExpandableNotificationRow}）
     * → 这把手势整个归通知，永不拦截。原“底下列表还能翻也不拦”的 {@code hitsScrollable}
     * 豁免在 v1.0.27 删除：真机上<b>空白区</b>的上滑 NSSL 根本不消费，事件会直接漏给系统
     * 上滑解锁（1.0.26 实测“空白区域大概率触发系统的上滑”就是它放的行）；而压在通知上时
     * 列表自己会消费（1.0.26 已验证），行豁免一条就够。
     *
     * <p>判定顺序先便宜后贵：小白条手势 / 功能没开 / 确定不在锁屏 / 控制中心展开 / 窗口没量好
     * / 覆盖层不可见都直接判“不拦”（这些情形下 {@link #shouldBlockSystemSwipe()} 本来也过不了）。
     */
    private boolean resolveGestureExempt() {
        if (downInHandle || !config.interceptSwipe || sKeyguardState == 0 || sControlCenterOpen) {
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

    /**
     * 密码盘收起（{@code StatusBarKeyguardViewManager#hideBouncer} 执行完之后调）。
     *
     * <p><b>实测 bug</b>：上滑进密码页 → 点「返回」→ 页面变成纯壁纸：时钟、通知全在屏外，
     * 只剩状态栏（那是另一个窗口）还看得见；而且在这个页面上滑会直接触发系统解锁。
     *
     * <p>两个症状同一个根：上滑进密码页时内容已经被钉在顶上（{@code progress=1} /
     * {@code unlocking=true}），而 {@code sync()} 只在「锁屏视图真的消失」时才归零
     * （keepup1/2 拍板，为的是别在解锁退场时把内容当着用户面拽下来）。密码盘一收，
     * 锁屏根本没消失 —— 于是钉顶姿态一直留着：内容全在屏外 = 纯壁纸；
     * 同时 {@link #shouldBlockSystemSwipe()} 见到 {@code progress > 0} 会判定
     * 「我们自己正在拖」而放行 → 那一滑就落到系统手里解锁了。
     *
     * <p>所以盘一收就补一刀：**只要还留在锁屏上**（{@code sKeyguardState != 0}）且盘确实
     * 不在了，就把钉顶状态收掉。真解锁时（密码正确 / 生物识别）keyguard 会退场，
     * 那条路必须继续钉顶等淡出 —— 所以延迟一点再判，绝不抢在退场之前把内容拽下来。
     */
    static void onBouncerHidden() {
        final LockBarController c = sInstance;
        if (c == null) {
            return;
        }
        c.window.postDelayed(c::resetPinnedIfKeyguardBack, 120L);
    }

    /**
     * 「密码盘收起 + 锁屏还在」→ 把钉顶状态收掉。幂等，任何一步不成立就什么都不做。
     */
    private void resetPinnedIfKeyguardBack() {
        if (sKeyguardState == 0) {
            return;     // 明确不在锁屏：真解锁，交给既有钉顶逻辑等锁屏退场
        }
        if (bouncer != null && bouncer.getVisibility() == View.VISIBLE) {
            return;     // 盘又开了（连按返回 / 重新输密码），别动
        }
        if (fingerRising || liftTracking) {
            return;     // 手上还有手势，不抢
        }
        if (progress != 0f || unlocking) {
            ModuleLog.d("密码盘收起·锁屏回位：内容归位（否则会停在纯壁纸状态）");
            ModuleLog.flush();
            reset();
        }
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

    /**
     * 控制中心展开状态回调 —— 两条 hook 路径都打到这里（keyguard 侧
     * {@code onExpandChanged(boolean)}、container 委托 {@code onVisibleChanged(boolean)}），
     * 谁先响谁算，互为兜底。值没变不记日志，稳态零开销。
     */
    static void onControlCenterOpen(boolean open) {
        if (sControlCenterOpen == open) {
            return;
        }
        sControlCenterOpen = open;
        final LockBarController c = sInstance;
        if (open) {
            if (c != null && c.topRightBar != null) {
                c.topRightBar.setVisibility(View.GONE);   // 控制中心开着时藏起右上角小白条
            }
            ModuleLog.d("控制中心展开·上滑手势让路");
            ModuleLog.flush();
        } else {
            if (c != null) {
                c.topRightHandleOpenedCc = false;
                if (c.topRightBar != null && c.config.topRightHandle && sKeyguardState != 0) {
                    c.topRightBar.setVisibility(View.VISIBLE);
                }
            }
            ModuleLog.d("控制中心收起·拦截恢复");
            ModuleLog.flush();
        }
    }

    /** 控制中心开着时给 {@link LockHandleView} 用：小白条这次按下直接不消费。 */
    static boolean controlCenterOpen() {
        return sControlCenterOpen;
    }

    /**
     * 只有“手指确实在拖右缘”时才压制控制中心展开。
     *
     * <p>之前用 {@link #shouldBlockCcPull()} 做判定，它只看“开关开了 + 在锁屏”，
     * 于是整个下拉（包括左半边的通知中心）都被压成 0 高度 ——
     * 就是“有通知但通知中心空白”的真因。
     */
    static boolean shouldBlockCcExpandNow() {
        LockBarController c = sInstance;
        if (c == null || !c.blockCcEdgePull || !c.config.topRightHandle || sKeyguardState == 0) {
            return false;
        }
        // 时效：只认最近 2 秒内真正在拖右缘的那次手势。
        // 否则标志一旦残留（手势被别人吃掉、没收到 UP），
        // 解锁后的通知中心也会被压成 0 高度——就是“有通知但空白”。
        return SystemClock.uptimeMillis() - c.blockCcEdgeAt < 2000L;
    }

    /** 锁屏右上方小白条开启时：系统右缘下拉的控制中心该不该被压住。 */
    static boolean shouldBlockCcPull() {
        final LockBarController c = sInstance;
        return c != null && c.config.topRightHandle && sKeyguardState != 0
                && !c.topRightHandleOpenedCc;
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
                config.dimStrength, config.dimUpper);
        overlay.addView(rim, matchParent());

        // 玻璃条压在描边/压暗之上（玻璃是材质，不该被一层黑纱盖掉），但在小白条之下（触控优先）。
        // 黑纱在玻璃下面 = 带外的壁纸已经变暗、带内取的却是原图，所以 shader 里要按
        // 同一条弧线把压暗同步补上（setRim 后两项），否则玻璃与压暗背景之间是硬割裂。
        // underlay1：这里先挂进 overlay，随后 resolve() 找到锁屏内容层时会把 strip
        // 迁到内容之下（placeStripUnderContent）—— 玻璃不再盖住通知/时钟；rim、
        // 小白条、提示字这三件留在 overlay 照旧压顶。
        strip = new LockGlassStrip(ctx);
        // 描边参数必须与上面 rim.configure 的五项同源：画在哪 / 多宽 / 要不要画 / 压哪半 / 压多深
        strip.setRim(config.arcWidth, config.arcAlpha, config.arcDim,
                config.dimStrength, config.dimUpper);
        strip.setGlassThickness(config.glassThick);
        // 「玻璃参数」页的九项：**必须排在 setGlassEnabled 之前** —— 否则开关一开
        // 就先 rebuild 了一次，用的还是字段初值，紧接着又 rebuild 一次才拿到真值。
        // 字段初值恰好等于默认配置，所以两次结果一样，但顺序写死更省心。
        applyGlassTuning(strip, config);
        strip.setGlassEnabled(config.glassLive);
        overlay.addView(strip, matchParent());

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

        // 锁屏右上方小白条：下拉打开控制中心（替代系统右缘下拉）
        topRightBar = new View(ctx);
        android.graphics.drawable.GradientDrawable barBg =
                new android.graphics.drawable.GradientDrawable();
        barBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        barBg.setColor(0xCCFFFFFF);
        barBg.setCornerRadius(3f * density);
        topRightBar.setBackground(barBg);
        topRightBar.setAlpha(config.topRightBarOpacity);
        topRightBar.setVisibility(View.GONE);
        int barWidth = Math.max(1, (int) (config.topRightBarLength * density));
        int barHeight = Math.max(1, (int) (config.topRightBarThickness * density));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                barWidth, barHeight, Gravity.TOP | Gravity.END);
        barLp.topMargin = (int) (config.topRightBarY * density);
        barLp.rightMargin = (int) (config.topRightBarX * density);
        overlay.addView(topRightBar, barLp);

        // 锁屏小组件：一排小圆（天气 / 步数 / 电池），位置跟着时钟底部走
        lockWidgets = new LockWidgetView(ctx);
        lockWidgets.setSizeDp(config.lockWidgetSize);
        lockWidgets.setVisibility(View.GONE);
        lockWidgets.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateWidgetGlassOrigin());
        installWidgetReceiver(ctx);

        // 组件放回 overlay 里（不再往 NotificationShadeWindowView 插额外图层）：
        // 之前为了 z 序往窗口里插了一层 MatchParent 子视图，它会改变系统对
        // 窗口子视图序列的假设（通知面板的布局/可见性会受影响）。
        // 组件单独一层，插在 keyguard_root_view **之下**：
        // 这样锁屏上展开的通知会自然盖在组件上面（真正的底层），
        // 而不是靠淡出去装。之前怀疑它弄坏通知中心，
        // 后来定位到真因是 pageTy 残留，所以这层可以放回来。
        FrameLayout.LayoutParams widgetsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        widgetLayer = new FrameLayout(ctx);
        widgetLayer.setClipChildren(false);
        widgetLayer.setClipToPadding(false);
        widgetLayer.addView(lockWidgets, widgetsLp);

        // NotificationShadeWindowView 的父类链是 WindowRootView -> FrameLayout
        FrameLayout.LayoutParams windowLp =
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
        View kg = find("keyguard_root_view");
        int insertAt = window.getChildCount();
        if (kg != null) {
            int idx = window.indexOfChild(kg);
            if (idx >= 0) {
                insertAt = idx;
            }
        }
        window.addView(widgetLayer, insertAt, windowLp);
        window.addView(overlay, windowLp);

        if (config.blurEnabled) {
            // 壁纸盖层预热：位图加载是异步的，等起滑那一刻才开始就来不及（第一次上滑会没糊）
            strip.ensureWallpaperForBlur();
        }

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
        } catch (Throwable missing) {
            // 澎湃1 的 NavigationHandle 没有 getHandleColor：读它的公开字段 mLightColor
            // 做同一道「系统色全透明就换成我们解析的系统色」检查（mPaint 两代都公开）。
            // 老系统这条路走不到（上面 try 成功就直接出），行为零变化
            try {
                Object light = cls.getField("mLightColor").get(nav);
                if (light instanceof Integer && ((Integer) light) >>> 24 < 8) {
                    Object paint = cls.getField("mPaint").get(nav);
                    if (paint instanceof android.graphics.Paint) {
                        ((android.graphics.Paint) paint).setColor(handle.systemHandleColor());
                        note = "ok(paint-override-light)";
                    } else {
                        note = "ok(lightColor)";
                    }
                } else {
                    note = "ok(lightColor)";
                }
            } catch (Throwable t) {
                note = "color:" + brief(t);
                log("handle color failed", t);
            }
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
        if (config.blurEnabled && strip != null) {
            // 后开模糊的用户：锁屏一出现就补预热（方法内有节流），起滑那次才有位图可盖
            strip.ensureWallpaperForBlur();
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
        // 密码盘收起（返回键 / 点空白）→ 锁屏回到眼前，但我们的内容还钉在顶上：
        // 主路是 SBVM#hideBouncer 那条 hook（见 onBouncerHidden），这里按「盘由显到隐」
        // 的跳变兜一遍，覆盖其它收起路径。两条都是幂等的。
        if (bouncerWasShowing && !bouncerShowing) {
            resetPinnedIfKeyguardBack();
        }
        bouncerWasShowing = bouncerShowing;
        boolean ready = keyguardRoot != null && keyguardRoot.isAttachedToWindow();
        boolean stateOk = sKeyguardState != 0;      // 0 = 明确不在锁屏；-1 = 未知，退回视图判断
        boolean interactive = isInteractive();
        boolean onKeyguard = ready && stateOk && !bouncerShowing && interactive
                && keyguardRoot.isShown() && config.enabled;
        if (!onKeyguard) {
            // 解锁 / 隐藏锁屏：右缘拦截标志必须归零，否则会带到桌面的通知中心
            blockCcEdgePull = false;
            ccEdgeCandidate = false;

            // 关键：把锁屏那套位移全部撤回。
            // 不撤的话，pageTy 会残留成一个负值，而我们的“受控写入”会一直把
            // 通知容器 / 面板按在那个偏移上 —— 解锁后的通知中心就会“有通知但看不到”。
            pageTy = 0f;
            widgetExtra = 0f;
            notifExtra = 0f;
            if (sharedNotifOwned) {
                setTranslation(sharedNotif, 0f);
                sharedNotifOwned = false;
            }
            if (panelOwned) {
                setTranslation(notificationPanel, 0f);
                panelOwned = false;
            }
        }

        int visibility = onKeyguard ? View.VISIBLE : View.GONE;
        if (overlay.getVisibility() != visibility) {
            overlay.setVisibility(visibility);
        }
        int barVis = onKeyguard && config.topRightHandle && !sControlCenterOpen
                ? View.VISIBLE : View.GONE;
        if (topRightBar != null && topRightBar.getVisibility() != barVis) {
            topRightBar.setVisibility(barVis);
        }
        if (onKeyguard && config.topRightHandle && !sControlCenterOpen) {
            positionTopRightBar();
        }
        int widgetVis = onKeyguard && config.lockWidget && !sControlCenterOpen
                ? View.VISIBLE : View.GONE;
        if (lockWidgets != null) {
            boolean becameVisible = lockWidgets.getVisibility() != widgetVis
                    && widgetVis == View.VISIBLE;
            if (lockWidgets.getVisibility() != widgetVis) {
                lockWidgets.setVisibility(widgetVis);
            }
            if (widgetLayer != null && widgetLayer.getVisibility() != widgetVis) {
                widgetLayer.setVisibility(widgetVis);
            }
            if (widgetVis == View.VISIBLE) {
                positionLockWidgets(becameVisible);
                lockWidgets.ensureWallpaper();
                if (strip != null) {
                    // 玻璃条读到的壁纸与小组件共用：动态壁纸下自己读不到文件
                    lockWidgets.setWallpaper(strip.wallpaperBitmap());
                }
                // 刚显示 / 从隐到显：强制重拉一次，避免拿着旧数据
                refreshLockWidgetData(becameVisible);
            }
        }
        if (onKeyguard) {
            // 与「小白条开不开」无关：参数页预览任何时候打开都该有真机几何可用
            publishLayoutMetrics(find("keyguard_status_bar"));
        }

        if (!onKeyguard) {
            if (progress != 0f) {
                // 解锁收尾（unlocking）：锁屏还挂在屏上（keyguardDone 之后的淡出窗口），
                // 此刻归零会把内容和弧框当着用户面拽回底部、桌面才慢半拍透出来 ——
                // 1.0.22 反馈的「框不上去了，下来再进桌面」就是这一下。钉在顶上等锁屏
                // 视图真的不再 shown，那时归零已经看不见；卡住不走交给手指（往下一拖
                // 就跟随回来），下次息屏亮屏也会经这里自动归零。
                if (unlocking && keyguardRoot != null && keyguardRoot.isAttachedToWindow()
                        && keyguardRoot.isShown()) {
                    return;
                }
                reset();
            } else {
                unlocking = false;
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
        // 锚点历代命名，按序取第一个找得到的：第一项 = 澎湃3/4，第二项 = 澎湃1
        // （米板5 SystemUI dex 实证：keyguard_background_layer 装着时钟+信息层+锁图标
        //   = 内容根；notification_container_parent 是包着通知列表的壳 = 通知容器）。
        // 名字不存在时 getIdentifier=0 → find 返回 null → 自然落下一个，老系统一字不差
        View root = findFirst("keyguard_root_view", "keyguard_background_layer");
        View notif = findFirst("shared_notification_container", "notification_container_parent");
        View panel = find("notification_panel");
        View box = find("keyguard_bouncer_container");

        if (root != keyguardRoot || notif != sharedNotif || panel != notificationPanel) {
            restoreBlurAll();   // 旧根上的模糊是我们挂的，换人前先把系统原件还回去
        }
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
        placeStripUnderContent();
    }

    /**
     * 玻璃条沉到锁屏内容之下（underlay1，实测拍板：「避开锁屏的通知和时钟，在这俩的
     * 下面放玻璃，不让玻璃覆盖在这俩上面」）。
     *
     * <p>做法：把 {@code strip} 从 {@code overlay}（window 的最后一个子 View，压在一切
     * 内容之上）里摘出来，直接插到锁屏内容层的正下方 —— 锚点取 {@code keyguard_root_view}
     * （时钟、充电动效、解锁提示都在它的子树里）与 {@code shared_notification_container}
     * （锁屏通知列表，蓝图里在 root 之上）中<b>更靠下的那个</b>。描边/黑纱（rim）、
     * 小白条、提示字仍在 overlay 里照旧压顶；壁纸盖层（cover）垫底不动。
     *
     * <p>玻璃取样的是自解码的壁纸位图、不是窗口内容，所以沉底只改<b>遮挡关系</b> ——
     * 材质、光学、透明度一个像素不变；锁屏背景是透的，内容不画的地方照样透出玻璃。
     *
     * <p>幂等 + 自愈：已在锚点之下就什么都不做；root 换人（面板重建）时 resolve() 会
     * 再跑一遍本方法，自动迁到新 root 底下。{@code find()} 是递归 findViewById，锚点
     * 未必是直属子 View，故沿父链爬到直属层再取 index。一个锚点都找不到（奇葩 ROM 把
     * root 埋深、或压根不在 window 树里）就留在原位 —— 退回老的「压顶」挂法，与历史
     * 版本表现一致，不会更糟。
     *
     * <p>已知交互：压暗方向开「上面」且玻璃同开时，黑纱（在 overlay、比沉底的玻璃高）
     * 会多压玻璃一层；默认的「下面」方向黑纱区与玻璃带不重叠，不受影响。
     *
     * <p>景深主体是唯一不吃这个遮挡的例外（depthband1）：deducted_image_view 等主体
     * 抠图层在 root 子树里、z 高于沉底的玻璃条，亮带被它挡住 —— 由 {@link DepthBandPass}
     * 在主体剪影上把玻璃条复画补回（见 {@link #ensureDepthBandPass}）。
     */
    private void placeStripUnderContent() {
        if (window == null || strip == null || keyguardRoot == null) {
            return;
        }
        try {
            int idx = Integer.MAX_VALUE;
            View[] cands = {keyguardRoot, sharedNotif};
            for (View cand : cands) {
                if (cand == null) {
                    continue;
                }
                View anchor = cand;
                android.view.ViewParent p = anchor.getParent();
                while (p != null && p != window) {
                    if (!(p instanceof View)) {
                        anchor = null;
                        break;
                    }
                    anchor = (View) p;
                    p = anchor.getParent();
                }
                if (p != window || anchor == null) {
                    continue;
                }
                int i = window.indexOfChild(anchor);
                if (i >= 0 && i < idx) {
                    idx = i;
                }
            }
            if (idx == Integer.MAX_VALUE) {
                return;   // 一个锚点都没找到：留在 overlay 里（老挂法）
            }
            android.view.ViewParent sp = strip.getParent();
            if (sp == window) {
                int cur = window.indexOfChild(strip);
                if (cur >= 0 && cur < idx) {
                    return;   // 已经在内容之下
                }
                window.removeView(strip);
            } else if (sp instanceof ViewGroup) {
                ((ViewGroup) sp).removeView(strip);
            }
            window.addView(strip, idx, matchParent());
        } catch (Throwable ignored) {
            // 迁移是纯视觉优化：失败就保持 overlay 里的老位置，功能不受影响
        }
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

    /**
     * 按序找第一个存在的名字：本 ROM 没有的命名 getIdentifier=0 → {@link #find} 返回 null
     * → 落到下一个（跨系统世代的锚点别名用这个，老系统行为与从前逐字节一致）。
     */
    private View findFirst(String... names) {
        for (String name : names) {
            View v = find(name);
            if (v != null) {
                return v;
            }
        }
        return null;
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
        // 锁屏小组件：同时跟页面位移与时钟抬升
        updateWidgetFollow();
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

    /** 模糊满强度对应的半径（dp）：20dp 在 3x 屏上约 60px，糊到看不清细节但渐变还在。 */
    private static final float BLUR_MAX_DP = 20f;

    /**
     * 上滑模糊**只糊弧线以下露出的那半边背景**（arcui7 拍板「只糊下面」）：
     * 可见壁纸长在**系统壁纸窗口**里、在我们窗口树外，RenderEffect 够不着
     * （无景深时树内也没有任何背景层）—— 盖一张同套 center-crop 的壁纸位图到
     * window 最底层，对盖层挂效果，等于把盖层画到的那半边糊掉；哪半边由
     * {@link WallCoverView#setShell} 逐帧喂的同源弧线裁出，与压暗分界线重合，
     * 弧线以上不画、透出真实壁纸保持清晰。景深主体/前景抠图浮在上层，按拍板
     * 保持清晰，时钟/通知同样清晰；给树内景深层挂效果的老路已整体拆除。
     * （玻璃条被景深主体挡住的缺口由补画层 depthband1 管，与本模糊无关。）
     * 半径 = 模糊程度 × 进度 × 20dp 连续渐强，不做分级阶梯；取整后没变就不重建 effect，
     * 稳态滑动不产生分配。
     */
    private void applySwipeBlur(float g) {
        if (!config.blurEnabled || config.blurStrength <= 0f) {
            if (lastBlurRadius != -1) {
                restoreBlurAll();
            }
            return;
        }
        float density = window.getResources().getDisplayMetrics().density;
        int r = Math.round(config.blurStrength * Math.min(g, 1f) * BLUR_MAX_DP * density);
        if (g <= 0.001f) {
            r = 0;
        }
        if (r == lastBlurRadius) {
            return;
        }
        if (r <= 0) {
            restoreBlurAll();
            return;
        }
        try {
            RenderEffect effect = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
            ensureCover();
            if (cover != null) {
                cover.setRenderEffect(effect);
                // 景深主体影子层（depthband2）：盖层在场才挂 —— 背景没糊时单独糊主体
                // 会前後不一致；effect 与盖层同一实例，半径逐帧同源。
                ensureDepthFrost();
                for (int i = 0; i < depthFrost.size(); i++) {
                    depthFrost.get(i).setRenderEffect(effect);
                }
            }
            lastBlurRadius = r;
        } catch (Throwable t) {
            log("applySwipeBlur failed", t);
        }
    }

    /**
     * 挂上/续上壁纸盖层：位图取自玻璃条那份解码（同一份源、同一套 center-crop，
     * 低半径阶段与真实壁纸逐像素对齐）；加载是异步的，本帧拿不到就先不挂，半径
     * 下一次变化时自动补上。盖层只在弧线的糊侧画位图，分界由 apply() 里 setShell
     * 逐帧喂；拿不到静态壁纸（超级/视频壁纸）就不挂 —— 宁可这一轮不糊，
     * 也绝不拿固定截图冒充实时背景。
     */
    private void ensureCover() {
        if (window == null || strip == null) {
            return;
        }
        strip.ensureWallpaperForBlur();   // 自带 4s 轮询 + 20s 重试窗，加载中/已就绪都是空转
        if (cover == null) {
            android.graphics.Bitmap b = strip.wallpaperBitmap();
            if (b == null) {
                return;
            }
            cover = new WallCoverView(window.getContext());
            cover.setBitmap(b);
            window.addView(cover, 0, matchParent());
            return;
        }
        // 换过壁纸就跟上（同对象时 setBitmap 直接返回，稳态零开销）
        android.graphics.Bitmap cur = strip.wallpaperBitmap();
        if (cur != null) {
            cover.setBitmap(cur);
        }
    }

    /**
     * 收起模糊：把盖层与主体影子层撤出窗口、半径缓存归 -1（下次起滑重挂）。
     * 时钟/通知从头到尾没被碰过、一律清晰；景深主体源层同样零改动（影子层只是
     * 镜像，拆除即无残留）。系统自己的模糊（下拉玻璃、通知栏过渡）逐帧重挂，不受影响。
     */
    private void restoreBlurAll() {
        detachDepthFrost();
        if (cover != null) {
            try {
                cover.setRenderEffect(null);
                window.removeView(cover);
            } catch (Throwable ignored) {
                // 已经不在树上（容器换代时被 resolve 收过）
            }
            cover = null;
        }
        lastBlurRadius = -1;
    }

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
            // keepup3 钉顶收尾：钉住时（解锁已拉起、锁屏视图还要挂 0.1~1s 才真正消失）
            // 补满到整屏高 —— 手势行程故意只给 0.96 屏高（弧线要留可见余量），但钉住后
            // 那 4% 里塞着通知底边/手电筒相机按钮，实测就是「最后一块卡在屏幕最上方」。
            // 补满后内容在动画收尾时已整块出屏，钉多久都不可见；拖回时 g<1 照旧。
            if (unlocking && g >= 1f) {
                shift = h;
            }
        }
        // edge = 窗口坐标下的“可见底边”，-shift 时内容整体上移
        float edge = h - shift;
        float ty = config.parallax ? -shift : 0f;
        setTranslation(keyguardRoot, ty);
        applyNotifTranslation();
        setTranslation(notificationPanel, ty);
        pageTy = ty;
        updateWidgetFollow();
        // 景深/壁纸层反向补偿：这些层画的是壁纸本身（含景深主体），必须留在屏幕原位 ——
        // 否则前面的主体跟着上滑、后面的系统壁纸不动，就会出现「主体分身」。
        // 内容根已平移 ty，这里给候选层写 base-ty 抵消（ty=0 时还原并清缓存）。
        anchorWallpaperLayers(ty);
        // 小白条贴着可见底边一起上升（触控区跟着走，手指不用重新找位置）
        setTranslation(handle, -shift);
        setTranslation(hint, -shift);
        // keepup3 钉顶收尾：内容已补满整屏高出屏（位移在上面算好），顶上不该再留任何
        // 自己画的东西 —— 弧壳/玻璃带画在 edge=h-shift=0 附近，钉住期间会在顶上留一条
        // 残带；背景糊此刻也该撤（restoreBlurAll 在 finishUnlock 已撤，这里防配置变更
        // 路径再把糊盖回来）。clear 全幂等；真没解锁、手指拖回时 g<1 自动重挂全套。
        if (unlocking && g >= 1f) {
            clearClips();
            restoreBlurAll();
            return;
        }
        // 背景模糊与位移同一节拍：半径没变时这行是空操作（稳态滑动零分配）
        applySwipeBlur(g);

        if (g <= 0.001f || w <= 0 || h <= 0) {
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
        // 窗口坐标系的弧线壳：rim 压暗、盖层模糊分界、玻璃条三处共用同一份，
        // 每帧只建一个；字段是不可变快照语义（要变就 copy()），谁都不原地改它。
        LockGlass.Shape arcShape =
                new LockGlass.Shape(edge, radius, radius, LockGlass.smoothstep(g), edge - h);
        // 「启用弧形描边」只管这一段：裁切 + 描边/压暗。玻璃条是「玻璃参数」页那套独立
        // 效果，由 glassLive 自己开关，关掉描边照样跟手 —— 原先这句 return 连着玻璃一起
        // 端走（clearClips() 里 rim.clear() 还捎带 strip.clear()），描边一关玻璃也没了。
        if (config.arcEnabled) {
            clipRoot.update(keyguardRoot, clipShape);
            clipNotif.update(sharedNotif, clipShape);
            clipPanel.update(notificationPanel, clipShape);
            clipInstalled = true;

            // 描边壳：顶边与内容顶对齐，底边落在可见底边上
            // rimAlpha=smoothstep 只给传统描边淡入用；1.0.20 起压暗分支不再读它（固定强度）
            rim.update(arcShape);
        } else {
            // 收掉裁切与描边壳，**但不动玻璃条**：这里只能调 clearArcClips()，
            // clearClips() 会连 strip 一起收。两者的 clear() 都幂等，每帧走一次没开销。
            clearArcClips();
        }
        // 盖层的模糊分界与压暗/裁切同一条弧（arcui7 拍板「只糊下面」）：喂壳过去，
        // 盖层只在糊的那半画位图 —— 弧线以上透出真实壁纸、一个像素不盖。
        // 描边关了也要喂：模糊归 blur_enabled 自己管，跟描边开关互相独立（同玻璃条）。
        if (cover != null) {
            cover.setShell(arcShape, config.dimUpper);
        }
        // 主体影子层喂同一份弧（depthband2）：与盖层同点分发，糊侧逐帧对齐
        for (int i = 0; i < depthFrost.size(); i++) {
            depthFrost.get(i).setShell(arcShape, config.dimUpper);
        }
        // 景深补画层跟手重画（depthband1）：它复画的是玻璃条**当前帧**的带，必须与手势
        // 同节拍失效 —— 首版漏了这句，补画层冻在挂载那一帧，主体上只有底部一段静止的带，
        // 弧升到中上部后补画就再也跟不上（用户实测「底部有、中上部没有」的死因）。
        if (depthBand != null) {
            depthBand.invalidate();
        }
        // 景深主体补画玻璃（depthband1）：主体抠图层 z 高于沉底的玻璃条、亮带会被它挡住，
        // 这里在主体剪影上把玻璃条复画一遍 —— 与手势同节拍，归零由 clearClips 拆除。
        ensureDepthBandPass();
        // 玻璃条用的是同一条弧线（同一份 Shape），逐帧跟手，不另起任何动画。
        // rimAlpha（= smoothstep(g)）照旧传下去，但 1.0.22 起它**不再控制玻璃强度**：
        // 强度在 rebuild() 里恒等于「玻璃参数」页的不透明度，手势第一帧就是满的。
        // rimAlpha 只剩两个用途 —— 驱动 shader 的 phase（液态扰动 / 跑动高光）、
        // 以及 rim 自己的描边淡入。所以这里「描边还没显影、玻璃已经满强度」是**故意的**，
        // 就是用户要的那句：不要跟随上滑程度再慢慢添加玻璃效果。
        if (strip != null) {
            strip.update(arcShape);
        }
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

    /** 只收「弧形描边」这一套（裁切 + 描边壳）。玻璃条归 glassLive 管，这里不碰它。 */
    private void clearArcClips() {
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

    /** 整套全收（手势归零 / 尺寸无效）：裁切 + 描边壳 + 玻璃条的外壳 + 景深补画一起。 */
    private void clearClips() {
        clearArcClips();
        if (rim != null && strip != null) {
            strip.clear();
        }
        detachDepthBandPass();
    }

    /** 小组件跟随：页面位移 + 时钟抬升（与时钟同一套变换）。 */
    private void updateWidgetFollow() {
        if (lockWidgets == null) {
            return;
        }
        // 跟随的只有“页面位移”（小横条上滑解锁那一路）；
        // 时钟自己的 lift 不算 —— “上滑时钟”时组件保持静止。
        setTranslation(lockWidgets, pageTy + widgetExtra);
        updateWidgetGlassOrigin();
    }

    /** Overlay 是独立于锁屏内容的 Window 子 View；给玻璃采样器传入合成后的真实屏幕坐标。 */
    private void updateWidgetGlassOrigin() {
        if (lockWidgets == null || overlay == null) {
            return;
        }
        int[] root = new int[2];
        View parent = widgetLayer != null ? widgetLayer : overlay;
        parent.getLocationInWindow(root);
        int x = root[0] + lockWidgets.getLeft() + Math.round(lockWidgets.getTranslationX());
        int y = root[1] + lockWidgets.getTop() + Math.round(lockWidgets.getTranslationY());
        lockWidgets.setSamplingOrigin(x, y);
    }

    /** 避让产生的位移用 160ms 过渡，直接改 topMargin 会看着像跳了一下。 */
    private void setWidgetExtra(float extra) {
        if (Math.abs(extra - widgetExtra) < 0.5f) {
            return;
        }
        if (widgetExtraAnim != null) {
            widgetExtraAnim.cancel();
        }
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(widgetExtra, extra);
        a.setDuration(160L);
        a.addUpdateListener(animation -> {
            widgetExtra = (Float) animation.getAnimatedValue();
            updateWidgetFollow();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (widgetExtraAnim == a) {
                    widgetExtraAnim = null;
                }
            }
        });
        widgetExtraAnim = a;
        a.start();
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

    // ------------------------------------------------------ 景深主体上的玻璃补画（depthband1）

    /**
     * 玻璃条在景深主体上「续」一段：underlay1 把玻璃条沉到锁屏内容之下，时钟/通知压在
     * 玻璃上是拍板效果；但主体抠图层（deducted_image_view 等）是壁纸场景不是内容，它长在
     * keyguard_root 子树里、z 比沉底的玻璃条高 —— 亮带扫过主体时被它整段挡住，看起来
     * 就是「主体上没有玻璃」。
     *
     * <p>做法：把主体层挑出来（{@link #isSubjectCutout}），各建一张 ALPHA_8 剪影，
     * 叠一个 {@link DepthBandPass} —— 每帧把玻璃条自己复画进 saveLayer，再按剪影
     * DST_IN 剔掉带外像素。输出 = 亮带 AND 主体剪影：主体处亮带续上；时钟/通知处本层
     * 全透明，玻璃仍在它们下面（underlay1 一个像素不破）。材质完全复用 strip 自己的
     * onDraw，不复制 shader、不动任何 K_* 数值。
     *
     * <p>挑层比锚定严（锚定钉「画壁纸本身」的整层，这里只要「主体抠图」语义的名字）：
     * 壁纸预览/背景层一律不进剪影 —— 它们是整屏背景，进剪影等于把亮带补到全屏、盖住时钟。
     * 找不到主体层就不挂（普通壁纸零开销）；源层零改动（剪影是只读快照，归零整层拆除）。
     */
    private void ensureDepthBandPass() {
        if (window == null || strip == null || !config.glassLive) {
            return;
        }
        if (depthBand != null) {
            if (depthBand.getParent() == window) {
                return;   // 本帧已挂着：手势期间幂等
            }
            depthBand.clear();   // 窗口换过（面板重建）：旧层作废重挂
            depthBand = null;
        }
        try {
            java.util.ArrayList<View> hits = new java.util.ArrayList<>();
            collectBandSource(keyguardRoot, hits);
            collectBandSource(sharedNotif, hits);
            collectBandSource(notificationPanel, hits);
            if (hits.isEmpty() || window.indexOfChild(overlay) < 0) {
                return;   // 没有主体（普通壁纸）/ overlay 不在树里：保持现状
            }
            DepthBandPass pass = new DepthBandPass(window.getContext());
            pass.setStrip(strip);
            pass.setSources(hits);
            // 插在 overlay 正下方：压过锁屏内容与主体（补画要盖住主体），
            // rim/黑纱/小白条/提示字仍在 overlay 里照旧压顶
            window.addView(pass, window.indexOfChild(overlay), matchParent());
            depthBand = pass;
            ModuleLog.d("景深补画 attached sources=" + hits.size());
        } catch (Throwable t) {
            log("ensureDepthBandPass failed", t);
            depthBand = null;
        }
    }

    /** 手势归零整拆（clearClips 收尾走这里）：补画层与剪影一起撤，源层零改动无残留。 */
    private void detachDepthBandPass() {
        DepthBandPass p = depthBand;
        if (p == null) {
            return;
        }
        depthBand = null;
        try {
            window.removeView(p);
        } catch (Throwable ignored) {
            // window 已换：旧树随窗消亡，无需处理
        }
        p.clear();
    }

    /**
     * 景深主体的毛玻璃影子层（depthband2）：锚定把主体钉在原位保持锐利，弧线糊侧里
     * 周围壁纸是磨砂的、主体却是锐利的洞。这里给每个主体层插一个同父同参的影子层，
     * 逐帧镜像主体、只画糊的那半并挂与盖层相同的模糊 —— 源层零改动，遮挡关系不动。
     * 只在盖层在场时挂（applySwipeBlur 的 cover 分支里调用）；无主体（普通壁纸）零开销。
     */
    private void ensureDepthFrost() {
        if (window == null || strip == null || !depthFrost.isEmpty()) {
            return;
        }
        try {
            java.util.ArrayList<View> hits = new java.util.ArrayList<>();
            collectBandSource(keyguardRoot, hits);
            collectBandSource(sharedNotif, hits);
            collectBandSource(notificationPanel, hits);
            for (int i = 0; i < hits.size(); i++) {
                View v = hits.get(i);
                android.view.ViewGroup p = (android.view.ViewGroup) v.getParent();
                if (p == null) {
                    continue;
                }
                DepthFrostView fv =
                        new DepthFrostView(window.getContext(), v, v.getLayoutParams());
                // 插在主体正上方一个位置：z 只压过主体自己，兄弟顺序一概不动
                p.addView(fv, Math.min(p.indexOfChild(v) + 1, p.getChildCount()));
                depthFrost.add(fv);
            }
            if (!depthFrost.isEmpty()) {
                ModuleLog.d("景深毛玻璃影子 attached=" + depthFrost.size());
            }
        } catch (Throwable t) {
            log("ensureDepthFrost failed", t);
            detachDepthFrost();
        }
    }

    /** 收影子层（restoreBlurAll 走这里）：逐个摘除清引用，源层无任何残留。 */
    private void detachDepthFrost() {
        for (int i = 0; i < depthFrost.size(); i++) {
            DepthFrostView fv = depthFrost.get(i);
            fv.setRenderEffect(null);
            try {
                android.view.ViewGroup p = (android.view.ViewGroup) fv.getParent();
                if (p != null) {
                    p.removeView(fv);
                }
            } catch (Throwable ignored) {
                // 父容器已随面板重建换代：影子层随旧树消亡，无需处理
            }
            fv.clear();
        }
        depthFrost.clear();
    }

    /**
     * 全量下钻收集主体抠图层：与锚定的「最上层即止」不同，这里一路下钻 —— 主体可能嵌在
     * depth 壳里，剪影取的是并集、多收无害；上限 4 层防奇葩 ROM 疯狂嵌套。
     */
    private void collectBandSource(View root, java.util.List<View> out) {
        if (root == null || out.size() >= 4) {
            return;
        }
        if (isSubjectCutout(root)) {
            out.add(root);
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectBandSource(g.getChildAt(i), out);
            }
        }
    }

    /**
     * 只有「主体抠图」语义的名字进剪影：deducted / subject / portrait / crop
     * （四个词都在 isDepthLike 清单里，本判定是它的真子集）。
     */
    private boolean isSubjectCutout(View v) {
        String s = (idName(v) + " " + v.getClass().getSimpleName())
                .toLowerCase(java.util.Locale.ROOT);
        if (s.contains("wallpaper") || s.contains("background") || s.contains("preview")
                || s.contains("blur") || s.contains("mask")) {
            return false;   // 整屏背景/合成壳：进剪影 = 亮带补到全屏、盖住时钟
        }
        return s.contains("deducted") || s.contains("subject")
                || s.contains("portrait") || s.contains("crop");
    }

    private void reset() {
        cancelAnimator();
        unlocking = false;
        fingerRising = false;   // 一切收场都归零：指纹屏蔽不留残留（fpshield1）
        fpResultUntil = 0;      // 结果侧宽限也归零（fpshield3）
        fingerRisingAt = 0;     // 上升起点也清掉（fpshield5）
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
        unlocking = false;
        fingerRising = true;    // 把手接管：整个拖升过程屏蔽指纹（fpshield1）
        armFpResult();          // 结果侧同步上膛（fpshield3）
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
    public void onDragEnd(float dy, float ignored, float velocityUpPx, boolean cancel) {
        if (!config.enabled || overlay == null
                || overlay.getVisibility() != View.VISIBLE) {
            reset();
            return;
        }
        // 拉过半松手进解锁；第二条路是「速度解锁」—— 100% 跟手时行程 = 0.96×屏高
        // （约 2400px），光靠进度得划掉近半屏，慢拖累、甩一下反而拖不快；甩一下就够了。
        // 顺序是**先让条飞到顶、再拉解锁界面**：以前是 show() 和 animateTo(0f) 同时发，
        // 密码盘往上冒、条往下掉，两股反方向撞在一起就是那股简陋劲。
        if (!cancel && (progress >= UNLOCK_THRESHOLD || flickByVelocity(velocityUpPx))) {
            // 屏蔽不撤：飞升这段接着由 unlocking 换防（finishUnlock 里无缝交接，fpshield1）
            animateUpThenUnlock();
            return;
        }
        fingerRising = false;   // 松手不进解锁：内容不再上升，指纹立刻恢复
        armFpResult();          // 结果侧再宽限一小段，等迟到的那一下认证结果（fpshield3）
        // fpshield5：把手拖升期间指纹会话是被压住的，松手立刻要回来
        LockBarHooks.requestFpRearmSoon();
        // cancel 也包含了“点击小白条没有拖动”的情况：点击视为归位
        if (cancel || config.releaseReturn) {
            animateTo(0f);
            return;
        }
        fadeHandle(progress);
    }

    /**
     * 速度解锁：松手瞬间手指向上速度 ≥ 「触发速度」就判定解锁，与进度无关。
     *
     * <p>阈值来自设置页（dp/s），这里乘 density 换成 px/s 再比 ——
     * {@link LockHandleView} 给的本来就是原始屏幕像素上的速度。
     *
     * <p>方向天然受限：只收<b>向上</b>的速度，向下甩得到的是负数，永远够不到正阈值；
     * 开关关掉这条就彻底退化成纯进度判定，行为与 1.0.21 逐帧一致。
     *
     * <p>不会截胡慢拖：滑条下限 300 dp/s 已经明显快于手动，慢拖松手时的瞬时速度
     * 远在它之下，照旧得划到 {@link #UNLOCK_THRESHOLD} 才解锁。
     */
    private boolean flickByVelocity(float velocityUpPx) {
        if (!config.flickUnlock || config.flickVelocity <= 0f || window == null) {
            return false;
        }
        float density = window.getResources().getDisplayMetrics().density;
        return velocityUpPx >= config.flickVelocity * density;
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

    /**
     * 触发解锁的那一下：先把条和内容顺着上滑的劲送到屏幕顶，<b>送到位了才</b>拉解锁界面。
     *
     * <p>1.0.21 及以前是「松手立刻 {@code LockBarUnlock.show()} + 同时 animateTo(0f)」，
     * 于是密码盘往上冒的同时条正在往下掉，两股反方向的运动撞在一起 —— 那就是看着简陋的
     * 全部原因。仿 iOS 应该是：条继续往上飞出屏幕，屏幕空一下，解锁界面才进来。
     *
     * <p>送到位后停在 {@code progress = 1}（内容上移 0.96×屏高，条和弧线都到顶）<b>不回弹</b>：
     * 解锁界面接管时 {@link #sync()} 会在下一次全局布局里把覆盖层置 GONE；内容的归零则
     * 推迟到锁屏视图真的消失（{@code unlocking} 钉顶，见 {@link #sync()}）—— 淡出窗口里
     * 归零会把内容当着用户面拽回底部才进桌面（1.0.22 反馈的那股）。
     * keepup2 起 show() 明确失败也<b>不回弹</b>：被拒的最常见原因是人脸/指纹抢先、
     * 系统已在退场，回弹必然撞车（反馈里的第二股下落）；真没解锁时手指拖回即可。
     *
     * <p>被 {@link #cancelAnimator()} 取消（用户又按下、或 {@link #reset()}）时绝不能去拉
     * 解锁界面 —— ValueAnimator 被取消同样会回调 onAnimationEnd，所以先记 cancelled。
     */
    private void animateUpThenUnlock() {
        cancelAnimator();
        float from = progress;
        if (from >= 1f) {
            // 手指已经拉到顶了，没有东西可飞，直接进解锁
            finishUnlock();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(from, 1f);
        a.setDuration(Math.round(config.unlockMs));
        a.setInterpolator(SMOOTHSTEP);
        a.addUpdateListener(animation -> {
            progress = (Float) animation.getAnimatedValue();
            apply(progress);
            fadeHandle(progress);
            // 条是飞出去的、不是停在顶上：在 fadeHandle 那 50% 的基础上再乘 (1-p)，
            // 到顶刚好淡干净。这段只在解锁这一下跑，正常拖动不经过这里。
            if (handle != null) {
                handle.setAlpha(handle.getAlpha() * (1f - progress));
            }
            if (hint != null && hint.getVisibility() == View.VISIBLE) {
                hint.setAlpha(hint.getAlpha() * (1f - progress));
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelled) {
                    return;
                }
                if (animator == a) {
                    animator = null;
                }
                finishUnlock();
            }
        });
        animator = a;
        a.start();
    }

    /** 条送到顶之后再拉解锁界面；成败一律钉顶 —— 内容绝不再自己下来（keepup2 拍板）。 */
    private void finishUnlock() {
        // 进度停在 1 时盖层正全糊着 —— 解锁一启动先撤掉，绝不能把糊住的壁纸盖到桌面上；
        // 这几帧 keyguard 还盖在上面，背景短暂回清晰无碍。真没解锁、手指拖回来时，
        // 随 apply(<1) 自动重挂。
        restoreBlurAll();
        // show() 被拒不等于「解锁没发生」：开了人脸/指纹时被拒最常见的原因就是生物识别
        // 抢先认证成功、系统自己已经在退场 —— 此刻回弹必然和退场撞车，画面就是
        // 「内容掉下来才进桌面」（1.0.22 反馈的第二股下落，帧轨迹实锤是这里的立即回弹）。
        // 所以成败一律钉顶：系统退场 → 锁屏视图消失时 sync 静默归零；
        // 真没解锁 → 手指往下一拖就跟随回来（拍板「跟随手势」），下次息屏亮屏也自动归零。
        LockBarUnlock.show();
        unlocking = true;
        fingerRising = false;   // 指纹屏蔽换防给 unlocking（fpshield1）：同线程无缝，零空档
        armFpResult();          // 结果侧继续上膛，覆盖退场窗口里的迟到结果（fpshield3）
        // fpshield5：压住期间攒下的换防在这里补一次（unlocking 期间不压会话，结果侧闸兜住）
        LockBarHooks.requestFpRearmSoon();
        // keepup3：钉住后立刻按钉顶姿态重放一帧 —— 位移补满整屏高把「最后一块」送出屏外，
        // 弧壳/玻璃带/背景糊一并收掉（见 apply 的钉顶分支）。不破 lastApplied 相等保护
        // 这帧会被跳过（飞行末帧刚写过 1f）；窗口没尺寸就跳过，宁可不补也不能把内容
        // 拽回原位。
        if (window.getWidth() > 0 && window.getHeight() > 0) {
            lastApplied = Float.NaN;
            apply(1f);
        }
    }

    // ------------------------------------------------------------------ 配置变更

    /**
     * 把「玻璃参数」页那九项下发给玻璃条。
     *
     * <p>抽成方法是为了让初始化和 {@link #onConfigChanged} 两个调用点共用<b>同一行</b>：
     * 九个实参手抄两遍，早晚有一处顺序会串位 —— 那种 bug 表现为「改折射却变了高光」，
     * 肉眼根本分不清是顺序错了还是效果本来就这样。
     *
     * <p>实参顺序与 {@code LockConfig} 字段声明顺序、{@code setGlassTuning} 形参顺序一致，
     * 三处锁死在同一条注释链上。
     */
    private void applyGlassTuning(LockGlassStrip s, LockConfig c) {
        s.setGlassTuning(c.glassDiffuse, c.glassBlur, c.glassRefract, c.glassEdgeRef,
                c.glassRefractW, c.glassFresnel, c.glassHighlight, c.glassHlWidth,
                c.glassOpacity);
    }

    @Override
    public void onConfigChanged(LockConfig c) {
        // 远端配置的回调可能不在主线程
        window.post(() -> {
            updateHandleWidth();
            if (rim != null) {
                rim.configure(c.arcWidth, c.arcAlpha, c.arcColor, c.arcDim, c.dimStrength,
                        c.dimUpper);
            }
            if (strip != null) {
                // 与上面 rim.configure 同源的五项：宽度 / 不透明度 / 是否压暗 / 压哪半 / 压多深
                strip.setRim(c.arcWidth, c.arcAlpha, c.arcDim, c.dimStrength, c.dimUpper);
                strip.setGlassThickness(c.glassThick);
                applyGlassTuning(strip, c);
                strip.setGlassEnabled(c.glassLive);
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
