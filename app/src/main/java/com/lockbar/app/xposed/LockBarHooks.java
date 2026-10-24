package com.lockbar.app.xposed;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * SystemUI 侧的 hook 安装入口。
 *
 * <p>三个主 hook，都在 SystemUI 自己的类上：
 * <ul>
 *   <li>{@code NotificationShadeWindowView#onAttachedToWindow()} —— 窗口一上来就把覆盖层
 *       （描边 + 小白条）装好，保证用户第一次按下小白条时手势就已经可用；</li>
 *   <li>{@code NotificationShadeWindowView#dispatchTouchEvent(MotionEvent)} —— <b>最外层的事件入口</b>：
 *       未拦截时照常放行并同步锁屏状态；开了「拦截系统上滑解锁」且判定成立时直接吞掉
 *       {@code MOVE}，系统那条上滑解锁根本不会发生，同时用它驱动“时间上移”；</li>
 *   <li>{@code NotificationShadeWindowView#onInterceptTouchEvent(MotionEvent)} —— 落点在小白条
 *       触控区里就强制不拦截（手势一定归我们）；</li>
 *   <li>控制中心开合的两条布尔回调（keyguard 侧 {@code onExpandChanged(boolean)}、container
 *       委托 {@code onVisibleChanged(boolean)}）—— 展开期间拦截与小白条整套让路
 *       （1.0.22 反馈：开着控制中心时上滑被模块劫持，关不掉也往上推不动）；</li>
 *   <li>{@code KeyguardStateControllerImpl#notifyKeyguardState(boolean, boolean)} ——
 *       解锁 / 被应用遮挡时立刻复位位移与裁切。</li>
 * </ul>
 *
 * <p>另有几个<b>只存引用</b>的附加 hook（{@code StatusBarKeyguardViewManager} 的构造器、
 * {@code reset} / {@code setOccluded} / {@code showPrimaryBouncer}），用来拿到 Dagger 单例，
 * 实现“拉满直接进解锁界面”；每个单独 try/catch，挂不上也不影响主功能，
 * 结果写进诊断信息供 App 显示。
 *
 * <p>上滑手势本身由覆盖层里的 {@link LockHandleView} 捕获；只有用户主动打开
 * 「拦截系统上滑解锁」时，系统自己的上滑才会被吞掉，其余情况系统原有手势不受影响。
 */
final class LockBarHooks {

    private static final String TAG = "LockBar";
    /**
     * 窗口视图类的历代命名，按序取第一个能加载的：
     * <ul>
     *   <li>澎湃3/4（A14 起）：{@code com.android.systemui.shade.*}；</li>
     *   <li>澎湃1（Android 13 底座，经米板5「系统界面 20220714.0」dex 实证）：
     *       还在 {@code com.android.systemui.statusbar.phone.*} —— 老包版同样自声明
     *       attach/touch/intercept 三方法且无子类覆写，hook 点语义完全一致。</li>
     * </ul>
     * 旧码只认 shade 名，澎湃1 上 loadClass 直接抛 CNFE 中断 install = 模块整体失效。
     */
    private static final String[] WINDOW_CLASSES = {
            "com.android.systemui.shade.NotificationShadeWindowView",
            "com.android.systemui.statusbar.phone.NotificationShadeWindowView",
    };

    /**
     * 本次启动的配置对象。
     *
     * <p>给 {@link #captureContext} 用：{@code putDebug} 必须等到 Context 到手才写得出去，
     * 而那一刻 {@link #install} 早就返回了，只能靠这个静态引用把状态补上去。
     */
    private static volatile LockConfig sConfig;

    private LockBarHooks() {
    }

    static void install(XposedModule module, ClassLoader classLoader, String dataDir) throws Throwable {
        // ① 必须排在读配置之前：读配置可能抛异常（v1.0.15 就是），一旦抛出去 install 就中断了，
        //    后面的 hook 一个都装不上 —— 而那恰恰是最需要把日志送出去的时刻。
        //    这个 hook 不依赖任何配置，永远能装上，且 attachBaseContext 一定发生在本方法之后。
        captureContext(module);

        // ② 安全模式判定：读宿主自己的 files 目录，不需要 Context、不需要 IPC、不需要任何权限，
        //    所以能排在“装任何功能 hook”之前。判据本身出错一律返回 false = 照常装 ——
        //    绝不能因为“安全检查坏了”就把模块干掉。
        boolean tripped = SafetyGate.evaluate(dataDir);

        LockConfig config = new LockConfig(module);
        sConfig = config;
        // 最早绑定落盘目标：后面任何一步抛异常，已记录的日志都还写得出去
        ModuleLog.bind(config);
        config.load();

        if (tripped) {
            armSafeMode(config);
            return;
        }
        SafetyGate.startHeartbeat();
        LockBarUnlock.bind(module, config);

        ModuleLog.d("远端配置：" + (config.isReady()
                ? "已就绪"
                : "未就绪（LSPosed 服务没连上，暂用默认值）")
                + "；enabled=" + config.enabled
                + " showHandle=" + config.showHandle
                + " interceptSwipe=" + config.interceptSwipe);

        Class<?> windowClass = firstClass(classLoader, WINDOW_CLASSES);
        ModuleLog.d("窗口视图类=" + windowClass.getName());

        Method attach = declared(windowClass, "onAttachedToWindow");
        module.hook(attach)
                .setId("lockbar-attach")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        LockBarController.onWindowAttached(chain.getThisObject(), config, module);
                    } catch (Throwable t) {
                        module.log(5, TAG, "onAttachedToWindow hook failed", t);
                    }
                    return result;
                });

        Method touch = declared(windowClass, "dispatchTouchEvent", MotionEvent.class);
        module.hook(touch)
                .setId("lockbar-touch")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    int mode = LockBarController.TOUCH_PASS;
                    MotionEvent ev = null;
                    try {
                        ev = (MotionEvent) chain.getArg(0);
                        mode = LockBarController.onWindowTouchPre(
                                chain.getThisObject(), config, ev);
                    } catch (Throwable t) {
                        module.log(5, TAG, "dispatchTouchEvent pre hook failed", t);
                    }
                    if (mode == LockBarController.TOUCH_SWALLOW) {
                        // 这次事件不进系统：子视图一个都收不到，那次上滑解锁就不存在了
                        return Boolean.FALSE;
                    }
                    Object result;
                    if (mode == LockBarController.TOUCH_PASS_AS_CANCEL && ev != null) {
                        // 吞过 MOVE 的手势收尾：把 UP 改写成 CANCEL 再放行。子树此前只收到
                        // DOWN（没超 slop 的 MOVE），收 UP 会被按事件流判手势的观察者当成
                        // 单击而误触发；CANCEL 是标准的“手势被收走”，各方都会干净复位
                        MotionEvent cancel = MotionEvent.obtain(ev);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        try {
                            result = chain.proceed(new Object[]{cancel});
                        } finally {
                            cancel.recycle();
                        }
                    } else {
                        result = chain.proceed();
                    }
                    try {
                        LockBarController.onWindowTouchPost(chain.getThisObject(), config);
                    } catch (Throwable t) {
                        module.log(5, TAG, "dispatchTouchEvent hook failed", t);
                    }
                    return result;
                });

        // 窗口的“是否拦截”判断：小白条自己的手势绝不能被抢走。
        // 单独 try/catch：万一这个 ROM 改了方法名，也不能拖垮前面几个 hook。
        try {
            Method intercept = declared(windowClass, "onInterceptTouchEvent", MotionEvent.class);
            module.hook(intercept)
                    .setId("lockbar-intercept")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        int mode = LockBarController.INTERCEPT_DEFAULT;
                        try {
                            MotionEvent ev = (MotionEvent) chain.getArg(0);
                            mode = LockBarController.onWindowIntercept(
                                    chain.getThisObject(), config, ev);
                        } catch (Throwable t) {
                            module.log(5, TAG, "onInterceptTouchEvent hook failed", t);
                        }
                        // 原逻辑照跑（它里面有一堆状态标记，直接跳过会留脏数据），只改返回值
                        Object result = chain.proceed();
                        if (mode == LockBarController.INTERCEPT_NO) {
                            return Boolean.FALSE;
                        }
                        if (mode == LockBarController.INTERCEPT_YES) {
                            return Boolean.TRUE;
                        }
                        // 防御：拿不到原始返回值就按“不拦截”处理，保持系统原行为
                        return result instanceof Boolean ? result : Boolean.TRUE;
                    });
        } catch (Throwable t) {
            ModuleLog.w("挂不上 onInterceptTouchEvent hook（这个 ROM 可能改了方法名）", t);
        }

        // 锁屏状态（showing / occluded）：比“视图是否可见”更可靠，
        // 用于在解锁、被其它应用遮挡时立刻复位。
        Class<?> stateClass =
                classLoader.loadClass("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl");
        Method state = declared(stateClass, "notifyKeyguardState", boolean.class, boolean.class);
        module.hook(state)
                .setId("lockbar-keyguard-state")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        boolean showing = (Boolean) chain.getArg(0);
                        boolean occluded = (Boolean) chain.getArg(1);
                        LockBarController.onKeyguardState(showing, occluded);
                    } catch (Throwable t) {
                        module.log(5, TAG, "notifyKeyguardState hook failed", t);
                    }
                    return result;
                });

        // 上升动画期间屏蔽指纹识别（fpshield1）：解锁页上升时按指纹区会触发识别，与上升
        // 动画撞车。指纹入口在各代 SystemUI 里形态多变 —— 本代（拉机实证）R8 把私有
        // onTouch 整个内联进了 -$$Nest$ 桥（2228 字节实现体、带 "ignoring onTouch with
        // null overlay" 常量串），原始 onTouch 已不存在；OS1 是 onTouch(J,MotionEvent,
        // boolean)；另外 MIUI 自家光学指纹图标（MiuiGxzwIconView）与 AOSP 栈并存。所以
        // 不猜单一总闸：按候选逐个挂，挂上几个算几个 —— 每个都是独立触发点，任意一个
        // 在场就挡得住。onFingerDown 是认证启动的最下游（上游再怎么折叠都进这里）。
        // 每个候选单独 try/catch，挂不上只是少一层，绝不拖垮前面的 hook（不更糟）。
        try {
            Class<?> udfps = classLoader.loadClass(
                    "com.android.systemui.biometrics.UdfpsController");
            // 最下游：认证启动总闸。十参签名取自本代机器 SystemUI dex 实证
            // （JIFFFFFJJZ = long,int,5×float,long,long,boolean —— 上一版少了 2 参没挂上）
            shieldUdfps(module, udfps, "onFingerDown",
                    new Class<?>[]{long.class, int.class, float.class, float.class,
                            float.class, float.class, float.class, long.class, long.class,
                            boolean.class},
                    "lockbar-udfps-down", false);
            // 本代实证：触摸总闸 = -$$Nest$ 桥（私有 onTouch 的实现体在这里）
            shieldUdfps(module, udfps, "-$$Nest$monTouch",
                    new Class<?>[]{udfps, long.class, MotionEvent.class},
                    "lockbar-udfps-bridge", true);
            // OS1 实证形态：四参 / 单参 onTouch
            shieldUdfps(module, udfps, "onTouch",
                    new Class<?>[]{long.class, MotionEvent.class, boolean.class},
                    "lockbar-udfps-touch3", true);
            shieldUdfps(module, udfps, "onTouch",
                    new Class<?>[]{MotionEvent.class},
                    "lockbar-udfps-touch1", true);
        } catch (Throwable t) {
            ModuleLog.w("挂不上指纹屏蔽 hook（这个 ROM 没有 UdfpsController）", t);
        }
        // MIUI 自家光学指纹图标（老路）：按下那一下直接跳过，类不在的老 ROM 静默少一层
        shieldUdfps(module, classLoader, "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView",
                "onTouchDown", new Class<?>[]{}, "lockbar-udfps-gxzw", false);

        // fpresult1: 结果侧总闸 —— 上面那组入口只挡得住"显示"：本代光学指纹的识别在
        // HAL/服务侧自主完成（内核触摸驱动直接感知按压区域），SystemUI 的触摸管线只管
        // 动效，挡掉入口后识别照样成功、照样解屏（1.0.23 实测：动效没了但识别还在）。
        // 认证成功的唯一分发根 = KeyguardUpdateMonitor#onFingerprintAuthenticated：
        // mUserFingerprintAuthenticated 记账、TrustManager.unlockedByBiometricForUser
        // 信任授权、全部回调（BUC→注入器 keyguardDone、现代转场仓库、MIUI 各家）都挂在
        // 它里面 —— 上升期整段吞掉 = 成功当没发生。任何异常一律 fail-open 走
        // chain.proceed() = 系统原行为（不更糟）。
        //
        // 换防是本闸的命门：原方法内的 updateFingerprintListeningState(2) 不会执行，
        // 而锁屏静止时没有任何触发源会再调它 → 不换防 = 下次按指纹毫无反应（锁死，
        // 违反密码盘纪律）。
        //
        // fpshield3（时序补丁，实测第二轮的漏点）：只按「此刻是否在上升」判闸会漏 ——
        // 光学指纹从按压到成功要 200-500ms，上滑常常已经结束、fingerRising 也清零了，
        // 结果才回来，正好从闸门缝里钻过去解屏（用户看到的就是「上滑过程中能识别」）。
        // 现在结果侧另有一个上膛窗口（armFpResult），上升开始/结束都续一次，覆盖迟到
        // 的那一下；新的一次触摸（preTouch DOWN 或指纹入口按下且闸门已关）立刻撤膛，
        // 中止手势后再按指纹仍然立刻有反应。
        //
        // fpshield4（本轮实测暴露的锁死）：fpshield3 的换防是「在回调里就地
        // startListeningForFingerprint(false)」，而调用方 KUM$10#onAuthenticationSucceeded
        // 在本方法返回后紧接着会写 setFingerprintRunningState(0)（dex 实证：:cond_a0
        // invoke onFingerprintAuthenticated → invoke setFingerprintRunningState(0)）。
        // 就地重开把运行态写成 1，随即被这一下踩回 0：服务端会话与 KUM 记账彻底脱节，
        // 而 updateFingerprintListeningState 的唯一触发源又被我们一起跳过 —— 实测表现
        // 就是「吞掉一次以后指纹彻底没反应，只能进密码盘或重新锁屏亮屏才恢复」。
        // 现在改成：吞掉时只登记「欠一次换防」，由运行态写回 0 的那一刻（
        // setFingerprintRunningState hook）才真正重开会话，并用「运行态回到 1」确认
        // 成功、没确认就有界重试（最多 FP_REARM_MAX_TRIES 次）。
        try {
            Class<?> kum = classLoader.loadClass("com.android.keyguard.KeyguardUpdateMonitor");
            Method kumFpAuth = declared(kum, "onFingerprintAuthenticated",
                    int.class, boolean.class);
            sKumRestart = declared(kum, "startListeningForFingerprint", boolean.class);
            try {
                sKumUpdateListening = declared(kum, "updateFingerprintListeningState", int.class);
            } catch (Throwable ignored) {
                // 兜底口缺失不影响主路（startListeningForFingerprint 才是主路）
            }
            try {
                sKumBiometricGranted = declared(kum, "getUserUnlockedWithBiometric", int.class);
            } catch (Throwable ignored) {
                // 拿不到就当作「还没授权」，屏蔽口径不变
            }
            // fpshield5：上升期间把「开指纹会话」压住（治本，见 LockBarController#isFpSilenced）。
            // 这条 hook 同时是我们自己换防要走的门 —— 换防期间用 sFpAllowStart 放行。
            try {
                module.hook(sKumRestart)
                        .setId("lockbar-fpstart")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!sFpAllowStart) {
                                try {
                                    if (LockBarController.isFpSilenced()) {
                                        ModuleLog.d("指纹会话被压住：上升中（不产生认证、不消耗次数）");
                                        return null;
                                    }
                                } catch (Throwable ignored) {
                                    // 判定失败一律放行（不更糟）
                                }
                            }
                            return chain.proceed();
                        });
                ModuleLog.d("指纹会话闸就位：KUM#startListeningForFingerprint");
            } catch (Throwable t) {
                ModuleLog.w("挂不上指纹会话闸（上升期压不住会话，退回吞结果）", t);
            }
            module.hook(kumFpAuth)
                    .setId("lockbar-fpresult")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            boolean block = LockBarController.shouldBlockFpResult();
                            // fpshield5：已经被人脸（或上一次指纹）授权过的话，这次解锁不是
                            // 指纹造成的 —— 挡它只会白烧一次识别次数，直接放行并写进日志。
                            int uid = 0;
                            try {
                                uid = (Integer) chain.getArg(0);
                            } catch (Throwable ignored) {
                                // 拿不到 userId 就当 0 查，查不到自然是 false
                            }
                            boolean authorized = biometricAlreadyGranted(chain.getThisObject(), uid);
                            if (block && authorized) {
                                block = false;
                            }
                            // 诊断：每次认证结果都留一行，闸门开没开、为什么开一目了然。
                            // 频率 = 每次指纹认证一条，不是每帧，不会刷屏。
                            ModuleLog.d("指纹结果到达：gate=" + (block ? "开" : "关")
                                    + "（" + LockBarController.fpGateReason() + "）"
                                    + (authorized ? "｜已生物识别授权(人脸/上次指纹)，放行" : ""));
                            if (block) {
                                // 只登记，不就地重开（原因见上面 fpshield4 那段）
                                requestFpRearm(chain.getThisObject());
                                ModuleLog.d("指纹成功已拦截（结果侧），等运行态写回 0 后换防");
                                return null; // 跳过原方法：信任授权 + 全回调分发都不发生
                            }
                        } catch (Throwable t) {
                            module.log(5, TAG, "fpresult shield check failed", t);
                        }
                        return chain.proceed();
                    });
            ModuleLog.d("指纹结果屏蔽就位：KUM#onFingerprintAuthenticated");
        } catch (Throwable t) {
            ModuleLog.w("挂不上指纹结果屏蔽 hook（这个 ROM 没有 KUM#onFingerprintAuthenticated）", t);
        }

        // fpshield4：运行态写回 = 换防的触发点兼确认点（顺序见 requestFpRearm 注释）。
        // 先 proceed 再判断：必须等系统把状态真写进去；任何异常都吞掉，绝不影响写回本身。
        try {
            Class<?> kum = classLoader.loadClass("com.android.keyguard.KeyguardUpdateMonitor");
            Method setState = declared(kum, "setFingerprintRunningState", int.class);
            module.hook(setState)
                    .setId("lockbar-fprunning")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            int runningState = (Integer) chain.getArg(0);
                            // 顺手抓实例：换防/压会话都要在它身上调方法（早于第一次吞结果）
                            if (chain.getThisObject() != null) {
                                sKum = chain.getThisObject();
                            }
                            ModuleLog.d("指纹运行态 → " + runningState);
                            onFpRunningState(runningState);
                        } catch (Throwable t) {
                            module.log(5, TAG, "fp running-state hook failed", t);
                        }
                        return result;
                    });
            ModuleLog.d("指纹运行态 hook 就位：KUM#setFingerprintRunningState");
        } catch (Throwable t) {
            ModuleLog.w("挂不上指纹运行态 hook（换防退回兜底定时器）", t);
        }

        // 诊断：认证错误回调。换防到底有没有把会话要回来，看这一行最直接 ——
        // 「按了完全没反应」时如果连它都没有，说明请求根本没被服务端接走。
        // 只记日志，不改任何行为。
        try {
            Class<?> kumCb = classLoader.loadClass(
                    "com.android.keyguard.KeyguardUpdateMonitor$10");
            Method authError = declared(kumCb, "onAuthenticationError",
                    int.class, CharSequence.class);
            module.hook(authError)
                    .setId("lockbar-fperr")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            ModuleLog.d("指纹认证错误 code=" + chain.getArg(0)
                                    + " " + chain.getArg(1));
                        } catch (Throwable ignored) {
                            // 诊断行失败不影响任何行为
                        }
                        return result;
                    });
        } catch (Throwable t) {
            ModuleLog.d("指纹错误回调没挂上（只少一条诊断）");
        }

        // 控制中心开合状态（多条布尔回调互为兜底；类与方法名取自各机型 SystemUI dex 实证）：
        // 展开期间拦截不再吞事件、小白条这次按下不消费 —— 否则锁屏上滑关不掉控制中心、
        // 任何向上动作失灵，底部上滑还会误触发模块解锁（1.0.22 反馈）。
        // 前两条 = 澎湃3/4（keyguard 侧监听器、container 委托）；澎湃1 的控制中心以插件窗口
        // 存在，开合先落 ControlPanelWindowManager.onExpandChange(boolean) 再分发给
        // keyguard 侧（KeyguardStatusBarViewController$2，写 mControlPanelExpand）与
        // 手势侧（GestureObserver，写 mControlCenterExpand）监听者 —— 挂分发点 + 两个
        // 代表性监听者，五条互为兜底；字段名即语义（ControlPanel/ControlCenter Expand），
        // 挂在哪一代上含义都一样。每条单独 try/catch：挂不上只是退回老行为，不能拖垮前面的 hook。
        controlCenterOpen(module, classLoader,
                "com.android.keyguard.injector.KeyguardPanelViewInjector$onControlCenterExpandChangedListener$1",
                "onExpandChanged");
        controlCenterOpen(module, classLoader,
                "com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate",
                "onVisibleChanged");
        controlCenterOpen(module, classLoader,
                "com.android.systemui.controlcenter.phone.ControlPanelWindowManager",
                "onExpandChange");
        controlCenterOpen(module, classLoader,
                "com.android.systemui.statusbar.phone.KeyguardStatusBarViewController$2",
                "onExpandChange");
        controlCenterOpen(module, classLoader,
                "com.miui.systemui.util.GestureObserver",
                "onExpandChange");

        // 附加 hook：捕获 StatusBarKeyguardViewManager，供“拉满直接进解锁界面”用。
        // 每个都单独 try/catch —— 挂不上只是没有那个效果，不能拖垮小白条本体。
        // 构造器最可靠（Dagger 单例一被 new 出来就抓到），其次才是那几个会被调用的方法。
        captureConstructor(module, classLoader,
                "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager");
        capture(module, classLoader,
                "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
                "reset", new Class<?>[]{boolean.class}, "lockbar-capture-reset");
        capture(module, classLoader,
                "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
                "setOccluded", new Class<?>[]{boolean.class}, "lockbar-capture-occluded");
        capture(module, classLoader,
                "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
                "showPrimaryBouncer", new Class<?>[]{String.class}, "lockbar-capture-bouncer");
        capture(module, classLoader,
                "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
                "show$4", new Class<?>[]{}, "lockbar-capture-show");
        capture(module, classLoader,
                "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
                "show", new Class<?>[]{}, "lockbar-capture-show2");

        // 密码盘收起（点「返回」/ 点空白）：上滑进密码页时内容已钉在顶上，盘一收锁屏就回到
        // 眼前，但钉顶姿态还留着 —— 实测就是「纯壁纸状态（时钟等全在屏外，只剩状态栏）+
        // 这个页面上一滑触发系统解锁」。这里在盘收起之后补一刀把钉顶收掉，细节见
        // LockBarController#onBouncerHidden()。
        try {
            Class<?> sbvm = classLoader.loadClass(
                    "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager");
            Method hideBouncer = declared(sbvm, "hideBouncer", boolean.class);
            module.hook(hideBouncer)
                    .setId("lockbar-bouncer-hide")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            LockBarController.onBouncerHidden();
                        } catch (Throwable t) {
                            module.log(5, TAG, "bouncer-hide hook failed", t);
                        }
                        return result;
                    });
            ModuleLog.d("密码盘收起 hook 就位：SBVM#hideBouncer");
        } catch (Throwable t) {
            ModuleLog.w("挂不上密码盘收起 hook（退回布局回调兜底）", t);
        }

        ModuleLog.d("主 hook 就位：attach / touch / intercept / cc-open / keyguard-state / udfps");
        ModuleLog.flush();
    }

    /**
     * 安全模式下的<b>全部</b>动作。
     *
     * <p>刻意只做三件事，其余一律不碰：
     * <ul>
     *   <li><b>记日志</b> —— 让用户在 App 的「模块日志」里看到模块不是没装，而是主动闭嘴了；</li>
     *   <li><b>把状态回传给 App</b> —— 首页据此显示提示与「退出安全模式」按钮
     *       （真正写出去要等 Context 到手，见 {@link #notifySafetyState}）；</li>
     *   <li><b>监听退出请求</b> —— 这是本方法里唯一的“活着”的东西：不装任何 hook，
     *       但 {@link LockConfig} 的监听必须留着，否则 App 的按钮按了也没人听见。</li>
     * </ul>
     *
     * <p>不调 {@link SafetyGate#startHeartbeat()}：安全模式下进程死活已经不反映本模块的行为。
     */
    private static void armSafeMode(LockConfig config) {
        String text = SafetyGate.statusText();
        ModuleLog.e(text, null);
        ModuleLog.flush();
        config.setListener(c -> {
            if (SafetyGate.tryExit(c.safetyExit())) {
                ModuleLog.d("已退出安全模式；重启系统界面后 hook 才会重新装上");
                ModuleLog.flush();
                try {
                    config.putDebug(LockConfig.KEY_DEBUG_SAFETY, "0");
                    config.putDebug(LockConfig.KEY_DEBUG_STATUS,
                            "安全模式已退出，重启系统界面后恢复");
                } catch (Throwable ignored) {
                    // 回传失败不影响已经生效的退出
                }
            }
        });
    }

    /**
     * Context 到手后补一条安全模式状态。
     *
     * <p>为什么必须等这里：{@link LockConfig#putDebug} 在没有 Context 时直接跳过，
     * 而 {@link #install} 跑的时候 Application 还没建出来 —— 那一刻写什么都是写不出去的。
     * {@link #captureContext} 恰好是最早的、确定拿得到 Context 的时机。
     */
    private static void notifySafetyState() {
        try {
            LockConfig config = sConfig;
            if (config == null) {
                return;
            }
            if (SafetyGate.isSafe()) {
                config.putDebug(LockConfig.KEY_DEBUG_STATUS, SafetyGate.statusText());
                config.putDebug(LockConfig.KEY_DEBUG_SAFETY, "1");
            } else {
                config.putDebug(LockConfig.KEY_DEBUG_SAFETY, "0");
            }
        } catch (Throwable t) {
            // 状态回传失败只影响 App 的显示，绝不能影响 hook
        }
    }

    /**
     * 挂 {@code ContextWrapper#attachBaseContext} 拿 SystemUI 的 {@link Context}。
     *
     * <p>日志回传（{@link LockConfig#putDebug}）需要 {@code ContentResolver}，
     * 而 {@code onPackageLoaded} 阶段 Application 还没建出来 —— 只能等 attach。
     * 不挂这一步，App 的「模块日志」页永远是空的。
     *
     * <p>三个设计点：
     * <ul>
     *   <li>挂 {@code ContextWrapper} 而不是 {@code Application#attach(Context)}：
     *       后者是包私有方法，各 Android 大版本签名变过；{@code attachBaseContext} 从
     *       API 1 起没动过，更稳。</li>
     *   <li>用 {@code instanceof Application} 过滤：同进程里 ContextWrapper 一大堆，
     *       只要 Application 那一个。</li>
     *   <li>{@code PROTECTIVE} + 自己 try/catch：挂不上只是 App 内看不到日志，
     *       LSPosed 日志那条出口仍然可用，绝不能影响主功能。</li>
     * </ul>
     */
    private static void captureContext(XposedModule module) {
        try {
            Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext",
                    Context.class);
            module.hook(attach)
                    .setId("lockbar-context")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            if (chain.getThisObject() instanceof Application) {
                                ModuleLog.setContext((Context) chain.getArg(0));
                                // Context 到手了，此刻才写得出去的东西都在这里补
                                notifySafetyState();
                            }
                        } catch (Throwable t) {
                            module.log(4, TAG, "failed to capture context", t);
                        }
                        return result;
                    });
        } catch (Throwable t) {
            ModuleLog.w("挂不上 attachBaseContext，App 内将看不到模块日志", t);
        }
    }

    /** hook 构造器：Dagger 单例被创建时第一时间拿到 {@code this}。 */
    private static void captureConstructor(XposedModule module, ClassLoader classLoader,
                                           String className) {
        try {
            Class<?> cls = classLoader.loadClass(className);
            java.lang.reflect.Constructor<?>[] ctors = cls.getDeclaredConstructors();
            for (java.lang.reflect.Constructor<?> ctor : ctors) {
                module.hook(ctor)
                        .setId("lockbar-capture-ctor")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                LockBarUnlock.capture(chain.getThisObject());
                            } catch (Throwable t) {
                                module.log(5, TAG, "failed to capture ctor", t);
                            }
                            return result;
                        });
            }
            LockBarUnlock.noteCapture("ctor", true, ctors.length + " ctor(s)");
        } catch (Throwable t) {
            LockBarUnlock.noteCapture("ctor", false, t.getClass().getSimpleName());
            ModuleLog.w("跳过构造器捕获 " + className + "（只影响“拉满进解锁界面”）", t);
        }
    }

    /** 只是把 {@code this} 存下来，不改变任何系统行为；结果记进诊断信息。 */
    private static void capture(XposedModule module, ClassLoader classLoader,
                                String className, String method, Class<?>[] params, String id) {
        String where = id.startsWith("lockbar-capture-")
                ? id.substring("lockbar-capture-".length()) : id;
        try {
            Method m = declared(classLoader.loadClass(className), method, params);
            module.hook(m)
                    .setId(id)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            LockBarUnlock.capture(chain.getThisObject());
                        } catch (Throwable t) {
                            module.log(5, TAG, "failed to capture " + method, t);
                        }
                        return result;
                    });
            LockBarUnlock.noteCapture(where, true, "");
        } catch (Throwable t) {
            LockBarUnlock.noteCapture(where, false, t.getClass().getSimpleName());
            ModuleLog.w("跳过方法捕获 " + className + "#" + method + "（只影响“拉满进解锁界面”）", t);
        }
    }

    /**
     * 挂一条“控制中心开合”的布尔回调：参数原样写进控制器的让路开关
     * （{@link LockBarController#onControlCenterOpen(boolean)}）。
     *
     * <p>两条路径语义一致、互为兜底：哪条挂不上或没响，另一条仍能把状态纠正过来；
     * 单条失败只记 {@link ModuleLog#w}，不影响已装好的 hook。
     */
    private static void controlCenterOpen(XposedModule module, ClassLoader classLoader,
                                          String className, String methodName) {
        try {
            Method m = declared(classLoader.loadClass(className), methodName, boolean.class);
            module.hook(m)
                    .setId("lockbar-cc-open")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            LockBarController.onControlCenterOpen((Boolean) chain.getArg(0));
                        } catch (Throwable t) {
                            module.log(5, TAG, "cc open hook failed", t);
                        }
                        return result;
                    });
        } catch (Throwable t) {
            ModuleLog.w("跳过控制中心开合 hook " + className + "#" + methodName, t);
        }
    }

    /** 按类名挂一个指纹屏蔽点；类不在（老 ROM）就静默少一层。 */
    private static void shieldUdfps(XposedInterface module, ClassLoader classLoader,
            String className, String methodName, Class<?>[] params, String id,
            boolean booleanReturn) {
        try {
            shieldUdfps(module, classLoader.loadClass(className),
                    methodName, params, id, booleanReturn);
        } catch (Throwable ignored) {
            ModuleLog.d("指纹屏蔽点没有：" + className + "（这个 ROM 没这个类）");
        }
    }

    /**
     * 挂一个指纹屏蔽点（fpshield1）：上升期 gate=true 时直接吃掉事件 / 跳过这次调用。
     *
     * <p>方法体在各代 SystemUI 里形态多变（R8 甚至会把私有 onTouch 内联进 -$$Nest$
     * 桥），所以按候选逐个挂、挂上几个算几个 —— 每个都是独立触发点，任意一个在场
     * 就挡得住；全军覆没也只是几条日志，系统行为原样（不更糟）。
     */
    private static void shieldUdfps(XposedInterface module, Class<?> clazz,
            String methodName, Class<?>[] params, String id, boolean booleanReturn) {
        try {
            Method m = declared(clazz, methodName, params);
            module.hook(m)
                    .setId(id)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        // fpshield3：入口按下要上报（闸门开没开都报）—— 结果侧靠它区分
                        // 「上滑带出来的按压」与「中止手势后主动按」。只认 DOWN：MOVE/UP
                        // 不能当成新按压，否则刚上膛的宽限会被同一手势的后续事件撤掉。
                        try {
                            boolean down = true;
                            for (int i = 0; i < params.length; i++) {
                                Object a = chain.getArg(i);
                                if (a instanceof MotionEvent) {
                                    down = ((MotionEvent) a).getActionMasked()
                                            == MotionEvent.ACTION_DOWN;
                                    break;
                                }
                            }
                            if (down) {
                                LockBarController.noteUdfpsDown();
                            }
                        } catch (Throwable ignored) {
                            // 上报失败不影响屏蔽判定
                        }
                        try {
                            if (LockBarController.shouldBlockUdfpsTouch()) {
                                // boolean 口 = 吃掉这次触摸；void 口 = 这一下按下当没发生
                                return booleanReturn ? Boolean.TRUE : null;
                            }
                        } catch (Throwable t) {
                            module.log(5, TAG, "udfps shield check failed", t);
                        }
                        return chain.proceed();
                    });
            ModuleLog.d("指纹屏蔽点就位：" + clazz.getSimpleName() + "#" + methodName
                    + " 参数=" + params.length);
        } catch (Throwable t) {
            ModuleLog.d("指纹屏蔽点没有：" + clazz.getSimpleName() + "#" + methodName
                    + "（这个 ROM 没这形态）");
        }
    }

    // ------------------------------------------------- 结果侧换防（fpshield4）

    /** 结果侧 hook 抓到的 KUM 实例（换防要在它身上开新会话）。 */
    private static volatile Object sKum;
    /** {@code KUM#startListeningForFingerprint(boolean)} —— 换防主路。 */
    private static volatile Method sKumRestart;
    /** {@code KUM#updateFingerprintListeningState(int)} —— 主路缺失时的兜底。 */
    private static volatile Method sKumUpdateListening;
    /** {@code KUM#getUserUnlockedWithBiometric(int)} —— 判断「是不是已经有人脸/上次指纹授权过」。 */
    private static volatile Method sKumBiometricGranted;
    /** 吞掉一次结果后「还欠一次换防」。 */
    private static volatile boolean sFpRearmPending;
    /** 本轮已经尝试换防几次（有界，绝不死循环）。 */
    private static volatile int sFpRearmTries;
    /** 换防刚跑过、还没看到运行态回到 1 = 会话没起来。 */
    private static volatile boolean sFpRearmUnconfirmed;
    /**
     * 换防自己的「放行票」（fpshield5）。
     *
     * <p>{@code startListeningForFingerprint} 被我们挂成了「上升期间一律不开会话」的闸，
     * 而我们自己换防走的正是这个方法 —— 换防期间把票举起来，闸才让过。
     */
    private static volatile boolean sFpAllowStart;
    /** 主线程 Handler（懒建；指纹回调本来全在主线程上）。 */
    private static volatile Handler sFpHandler;

    /** 换防延迟：必须晚于 KUM$10 把运行态写回 0 的那一下。 */
    private static final long FP_REARM_DELAY_MS = 120L;
    /** 兜底：万一这个 ROM 不写回 0，也不能一直不换防。 */
    private static final long FP_REARM_FALLBACK_MS = 600L;
    /** 换防没被确认时的重试间隔。 */
    private static final long FP_REARM_RETRY_MS = 800L;
    /** 还在上升、会话被压住时，换防往后再问一次的间隔（fpshield5）。 */
    private static final long FP_REARM_SILENCE_RETRY_MS = 200L;
    /** 每次吞结果最多尝试换防几次。 */
    private static final int FP_REARM_MAX_TRIES = 3;

    /** 真正去重开会话的那一下。 */
    private static final Runnable FP_REARM = new Runnable() {
        @Override
        public void run() {
            if (!sFpRearmPending) {
                return;
            }
            // fpshield5：还在上升就再等等 —— 这时候开会话等于把刚压住的会话又放出来，
            // 用户连按就会一路循环烧指纹次数。手势结束后 requestFpRearmSoon 会再叫一次。
            try {
                if (LockBarController.isFpSilenced()) {
                    postFp(FP_REARM, FP_REARM_SILENCE_RETRY_MS);
                    return;
                }
            } catch (Throwable ignored) {
                // 判定失败就照常换防（不更糟）
            }
            sFpRearmPending = false;
            sFpRearmTries++;
            Object kum = sKum;
            if (kum == null) {
                ModuleLog.d("换防跳过：还没抓到 KUM 实例");
                return;
            }
            Method start = sKumRestart;
            Method upd = sKumUpdateListening;
            if (start == null && upd == null) {
                ModuleLog.d("换防放弃：这个 ROM 没有可用的重开入口");
                return;
            }
            // 先置「未确认」再调：startListeningForFingerprint 内部就会写
            // setFingerprintRunningState(1)，那条写回就是我们的确认信号 —— 顺序反了
            // 会把已经成功的换防误判成失败，白多重开一次会话。
            sFpRearmUnconfirmed = true;
            sFpAllowStart = true;   // fpshield5：举票过闸
            try {
                if (start != null) {
                    ModuleLog.d("换防：startListeningForFingerprint(false) 第 "
                            + sFpRearmTries + " 次");
                    start.invoke(kum, false);
                } else {
                    ModuleLog.d("换防：updateFingerprintListeningState(2) 第 "
                            + sFpRearmTries + " 次");
                    upd.invoke(kum, 2);
                }
            } catch (Throwable t) {
                sFpRearmUnconfirmed = false;
                ModuleLog.w("换防重开失败", t);
                return;
            } finally {
                sFpAllowStart = false;
            }
            if (!sFpRearmUnconfirmed) {
                return; // 运行态已在本次调用里回到 1，换防成功（日志见“换防确认”）
            }
            postFp(FP_REARM_CHECK, FP_REARM_RETRY_MS);
        }
    };

    /** 换防没被确认 → 有界重试。 */
    private static final Runnable FP_REARM_CHECK = new Runnable() {
        @Override
        public void run() {
            if (!sFpRearmUnconfirmed) {
                return; // 已经看到运行态 1，会话确实回来了
            }
            if (sFpRearmTries >= FP_REARM_MAX_TRIES) {
                sFpRearmUnconfirmed = false;
                ModuleLog.d("换防放弃：连续 " + sFpRearmTries
                        + " 次都没起来（进密码盘或重新锁屏亮屏即可恢复）");
                return;
            }
            sFpRearmPending = true;
            postFp(FP_REARM, 0L);
        }
    };

    /**
     * 结果被吞掉后登记一次换防（fpshield4）。
     *
     * <p><b>为什么不能就地重开</b>：{@code onFingerprintAuthenticated} 返回后，调用方
     * {@code KUM$10#onAuthenticationSucceeded} 紧接着会写 {@code setFingerprintRunningState(0)}
     * （dex 实证）。就地重开会把运行态写成 1，随即被这一下踩回 0 —— 服务端会话与 KUM
     * 记账脱节，而 {@code updateFingerprintListeningState} 的唯一触发源（原方法内部那次）
     * 又被我们一起跳过，锁屏静止时再没有任何触发源 → 实测就是「吞掉一次后指纹彻底没反应，
     * 只能进密码盘或重新锁屏亮屏」。
     *
     * <p>所以这里只登记「欠一次换防」：由运行态写回 0 的那一刻触发重开
     * （{@link #onFpRunningState(int)}），另配一个兜底定时器，万一这个 ROM 不走那条
     * 写回也不会漏。任何一步失败都只留日志，绝不改变系统行为（不更糟）。
     */
    static void requestFpRearm(Object kum) {
        if (kum != null) {
            sKum = kum;
        }
        sFpRearmPending = true;
        sFpRearmTries = 0;
        sFpRearmUnconfirmed = false;
        postFp(FP_REARM, FP_REARM_FALLBACK_MS);
    }

    /**
     * 手势结束（闸门关）时立刻安排一次换防（fpshield5）。
     *
     * <p>上升期间 {@code startListeningForFingerprint} 被我们压住了，会话是关着的 ——
     * 手势一收必须马上把它要回来，否则「中止手势后按指纹」会没反应（那正是上一版
     * fpshield3 的老毛病）。这里不重试、不确认，纯补一次；真正的换防确认仍由
     * {@link #FP_REARM} / {@link #FP_REARM_CHECK} 那套兜住。
     */
    static void requestFpRearmSoon() {
        if (sKum == null || sKumRestart == null) {
            return; // 还没抓到实例 / 这个 ROM 没有重开入口：什么都不做（不更糟）
        }
        sFpRearmPending = true;
        sFpRearmTries = 0;
        sFpRearmUnconfirmed = false;
        postFp(FP_REARM, FP_REARM_DELAY_MS);
    }

    /**
     * 运行态写回之后调（0 = 会话结束，1 = 会话在跑）。
     *
     * <p>0 且「还欠一次换防」→ 这才是重开的安全时机；1 → 换防确认成功。
     */
    static void onFpRunningState(int state) {
        if (state == 1) {
            if (sFpRearmUnconfirmed) {
                sFpRearmUnconfirmed = false;
                ModuleLog.d("换防确认：指纹会话已重开");
            }
            return;
        }
        if (state == 0 && sFpRearmPending) {
            postFp(FP_REARM, FP_REARM_DELAY_MS);
        }
    }

    /**
     * 这个用户是不是已经被人脸（或上一次指纹）授权过了。
     *
     * <p>用 {@code getUserUnlockedWithBiometric(userId)} 而不是 {@code getUserCanSkipBouncer}：
     * 后者把 {@code getUserHasTrust}（智能解锁 / 蓝牙信任 / 地点信任）也算进去 —— 那些在
     * <b>刚锁屏</b>时就是 true，会让我们整个屏蔽静默失效。前者只看
     * {@code mUserFingerprintAuthenticated} 与 {@code mFaceAuthInteractor.isAuthenticated}，
     * 且都过一遍 {@code isUnlockingWithBiometricAllowed}。
     *
     * <p>我们吞掉结果**不会**让它变 true（被跳过的正是写 {@code mUserFingerprintAuthenticated}
     * 的那段），所以它一旦为 true，就说明这次解锁不是当前这一下指纹造成的。
     *
     * <p>任何异常都返回 false（= 按「还没授权」处理，屏蔽口径不变，不更糟）。
     */
    private static boolean biometricAlreadyGranted(Object kum, int userId) {
        Method m = sKumBiometricGranted;
        if (m == null || kum == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(m.invoke(kum, userId));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 主线程投递（同一条 Runnable 只留一个待执行，绝不堆积）。 */
    private static void postFp(Runnable r, long delayMs) {
        try {
            Handler h = sFpHandler;
            if (h == null) {
                h = new Handler(Looper.getMainLooper());
                sFpHandler = h;
            }
            h.removeCallbacks(r);
            h.postDelayed(r, delayMs);
        } catch (Throwable t) {
            ModuleLog.w("换防定时器起不来", t);
        }
    }

    /** 按序取第一个能加载的类；全都找不到才抛（保持原来“缺类即中止 install”的语义）。 */
    private static Class<?> firstClass(ClassLoader classLoader, String[] names)
            throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (String name : names) {
            try {
                return classLoader.loadClass(name);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        throw last;
    }

    /** 取声明在该类自身（而非父类）上的方法，避免误 hook 到 View / ViewGroup 的通用实现。 */
    private static Method declared(Class<?> clazz, String name, Class<?>... params)
            throws NoSuchMethodException {
        Method m = clazz.getDeclaredMethod(name, params);
        if (m.getDeclaringClass() != clazz) {
            throw new NoSuchMethodException(clazz.getName() + "#" + name + " is inherited");
        }
        return m;
    }
}
