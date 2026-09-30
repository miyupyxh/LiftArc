package com.lockbar.app.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 上滑拉满后直接进入系统解锁界面（仿 iOS：松手就跳到密码盘，而不是弹回原位）。
 *
 * <p>{@code StatusBarKeyguardViewManager} 是 Dagger 单例、没有静态入口，
 * 因此由 {@link LockBarHooks} 里几个实例方法的 hook 顺手把 {@code this} 存下来。
 *
 * <p>行为：
 * <ul>
 *   <li><b>设置了密码 / 图案 / PIN</b>：调 {@code showPrimaryBouncer()} 弹出密码盘；</li>
 *   <li><b>没有安全锁</b>：调 {@code ViewMediatorCallback#keyguardDone(userId)}，
 *       等同系统自己的“上滑直接解锁”。</li>
 * </ul>
 *
 * <p>安全约定：只有在<b>明确</b>读到 {@code SecurityMode.None} 时才会走直接解锁那条路，
 * 任何反射失败一律按“有密码”处理，因此不可能绕过锁屏密码。
 */
final class LockBarUnlock {

    private static final String TAG = "LockBar";
    /** 传给 bouncer 的来源标记，方便在日志里认出来。 */
    private static final String REASON = "lockbar";

    private static volatile XposedInterface module;
    private static volatile LockConfig config;
    private static volatile Object manager;

    /** 安装时记录：哪些捕获 hook 真的挂上了，写进诊断信息。 */
    private static final StringBuilder CAPTURE_NOTES = new StringBuilder();

    private LockBarUnlock() {
    }

    static void bind(XposedInterface m, LockConfig c) {
        module = m;
        config = c;
    }

    /** 由 hook 捕获 {@code StatusBarKeyguardViewManager} 实例。 */
    static void capture(Object instance) {
        if (instance != null) {
            manager = instance;
        }
    }

    static synchronized void noteCapture(String where, boolean ok, String detail) {
        if (CAPTURE_NOTES.length() > 400) {
            return;
        }
        if (CAPTURE_NOTES.length() > 0) {
            CAPTURE_NOTES.append(", ");
        }
        CAPTURE_NOTES.append(where).append('=').append(ok ? "ok" : ("fail:" + detail));
        status("capture[" + CAPTURE_NOTES + "] mgr=" + (manager != null ? "yes" : "no"));
    }

    /** 拉起系统解锁界面；返回是否成功发起（false = 调用方按“回弹”处理）。 */
    static boolean show() {
        Object m = manager;
        if (m == null) {
            status("unlock=fail(manager未捕获; " + CAPTURE_NOTES + ")");
            log("StatusBarKeyguardViewManager not captured yet");
            return false;
        }
        try {
            int userId = currentUserId(m);
            if (!isSecure(m, userId)) {
                Object callback = field(m, "mViewMediatorCallback");
                if (callback != null) {
                    Method done = callback.getClass().getMethod("keyguardDone", int.class);
                    done.setAccessible(true);
                    done.invoke(callback, userId);
                    status("unlock=keyguardDone(user " + userId + ")");
                    return true;
                }
                // 拿不到回调就不敢直接解锁：退回去弹密码盘
                status("unlock=noViewMediatorCallback(user " + userId + ") → bouncer");
            }

            // 有密码：先直接问 PrimaryBouncerInteractor 要结果（showPrimaryBouncer 不返回值，
            // 失败时看起来就像“弹不出来又弹回去了”，没法判断哪一步出的问题）
            Object interactor = field(m, "mPrimaryBouncerInteractor");
            if (interactor != null) {
                Method show = interactor.getClass().getMethod("show", String.class);
                show.setAccessible(true);
                Object ok = show.invoke(interactor, REASON);
                if (Boolean.TRUE.equals(ok)) {
                    status("unlock=bouncer(user " + userId + ", secure)");
                    return true;
                }
                status("unlock=fail(PrimaryBouncerInteractor.show=false; user " + userId
                        + "; delegate未就绪?)");
                return false;
            }

            // 拿不到 interactor 就退回老办法：showPrimaryBouncer（看不到返回值）
            Method show = m.getClass().getMethod("showPrimaryBouncer", String.class);
            show.setAccessible(true);
            show.invoke(m, REASON);
            status("unlock=bouncer-legacy(user " + userId + ", 结果未知)");
            return true;
        } catch (Throwable t) {
            status("unlock=fail(" + brief(t) + ")");
            XposedInterface mod = module;
            if (mod != null) {
                mod.log(5, TAG, "failed to show unlock ui", t);
            }
            return false;
        }
    }

    /** 诊断信息（App 的「诊断」卡片里显示）。 */
    private static void status(String text) {
        LockConfig c = config;
        if (c != null) {
            c.putDebug(LockConfig.KEY_DEBUG_STATUS, text);
        }
    }

    private static String brief(Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String m = c.getMessage();
        if (m != null && m.length() > 60) {
            m = m.substring(0, 60);
        }
        return c.getClass().getSimpleName() + (m == null ? "" : ":" + m);
    }

    /** 是否设置了安全锁；读不到一律按“有密码”处理。 */
    private static boolean isSecure(Object m, int userId) {
        try {
            Object model = field(m, "mKeyguardSecurityModel");
            if (model == null) {
                return true;
            }
            Object mode = model.getClass()
                    .getMethod("getSecurityMode", int.class)
                    .invoke(model, userId);
            return mode == null || !"None".equals(String.valueOf(mode));
        } catch (Throwable t) {
            return true;
        }
    }

    /** 当前前台用户 id；拿不到就退回 0（系统用户）。 */
    private static int currentUserId(Object m) {
        try {
            Object interactor = field(m, "mPrimaryBouncerInteractor");
            Object selected = field(interactor, "selectedUserInteractor");
            if (selected == null) {
                return 0;
            }
            Method getter = selected.getClass().getMethod("getSelectedUserId");
            getter.setAccessible(true);
            return (Integer) getter.invoke(selected);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 沿继承链找字段并取值。 */
    private static Object field(Object o, String name) {
        if (o == null) {
            return null;
        }
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    private static void log(String msg) {
        XposedInterface mod = module;
        if (mod != null) {
            mod.log(4, TAG, msg, null);
        }
    }
}
