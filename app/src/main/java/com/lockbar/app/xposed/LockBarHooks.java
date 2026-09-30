package com.lockbar.app.xposed;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
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
    private static final String WINDOW_CLASS =
            "com.android.systemui.shade.NotificationShadeWindowView";

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

        Class<?> windowClass = classLoader.loadClass(WINDOW_CLASS);

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
                    boolean swallow = false;
                    try {
                        MotionEvent ev = (MotionEvent) chain.getArg(0);
                        swallow = LockBarController.onWindowTouchPre(
                                chain.getThisObject(), config, ev);
                    } catch (Throwable t) {
                        module.log(5, TAG, "dispatchTouchEvent pre hook failed", t);
                    }
                    if (swallow) {
                        // 这次事件不进系统：子视图一个都收不到，那次上滑解锁就不存在了
                        return Boolean.FALSE;
                    }
                    Object result = chain.proceed();
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

        ModuleLog.d("主 hook 就位：attach / touch / intercept / keyguard-state");
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
