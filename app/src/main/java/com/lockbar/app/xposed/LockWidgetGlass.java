// SPDX-License-Identifier: AGPL-3.0-or-later
// Material recipe adapted from HyperChanger, Copyright 2026 btm_m.
// LiftArc adaptation, 2026-11-09: owned sampler/path-consumer lifecycle and
// checked native View + Paint calls, based on the supplied ROM's draw contract.
// See THIRD_PARTY_NOTICES.md and assets/licenses/HyperChanger-AGPL-3.0.txt.
package com.lockbar.app.xposed;

import android.graphics.Paint;
import android.view.View;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 小组件专用的系统路径玻璃：背景采样容器 + 使用玻璃 Paint 的路径绘制层。
 *
 * <p>HyperChanger 的圆角 Drawable 流程不能直接替代自绘 Path。已核对本机 ROM 的
 * TimeView.onDraw：实际 drawPath 使用的 Paint 要开启 setGlassEffect，并携带
 * recipe[19]/[20] 的边缘参数；路径所在 View 使用 blur mode 3、surface color type 16。
 * 这里仅对两个新建的自有 View 操作，不改任何系统父层或外层小组件的 alpha/动画。
 */
final class LockWidgetGlass {
    private static final int BACKDROP_BLUR = 40;
    private static final int GLASS_SMALL_BLUR = 36;
    private static final int GLASS_BIG_BLUR = 72;
    private static final int MATERIAL_TYPE = 1;
    private static final float CLIP_PADDING_PX = 50f;

    // 保留已确认生效的完整光学配方；电池仅使用独立采样半径，不改高光/边缘/折射参数。
    private static final float[] RECIPE = {
            0.67f, 0.16f, 0.09f, 0f, 0.14f, 1.4f, -0.02f, 0.3f, 0.6f, 1f,
            0.03f, 1f, 1f, 1f, 0.1f, 0.2f, 0.3f, 1f, 1f, 72f, 3.8f, 80f, 800f,
            1.2f, 1f, -0.4f, 0.6f, -0.8f, 1.4f, 0.7f, 0.8f, 1.15f, 4f, 2f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f
    };

    private static Api api;
    private static boolean resolved;

    private final View surface;
    private final View backdrop;
    private final Paint marker;
    private final String label;
    private boolean bound;
    private boolean attempted;
    private boolean failedThisAttach;
    private boolean loggedBound;
    private boolean loggedDraw;
    private boolean loggedFailure;
    private int width;
    private int height;
    /** 0 表示保持已验证的圆环 profile；正数是电池胶囊路径的真实高度（px）。 */
    private float profileThicknessPx;
    private float loggedThicknessPx;

    LockWidgetGlass(View surface, View backdrop, Paint marker, String label) {
        if (surface == backdrop) {
            throw new IllegalArgumentException("Path glass requires separate owned sampler and consumer");
        }
        this.surface = surface;
        this.backdrop = backdrop;
        this.marker = marker;
        this.label = label;
    }

    boolean isBound() {
        return bound;
    }

    /** 圆环保持已经过用户确认的采样与光学参数。 */
    boolean bind() {
        return bindProfile(0f);
    }

    /** 电池轨道只调整底材采样；保留原生 Paint 标志、42 槽材质与已确认的高光。 */
    boolean bindBatteryTrack(float thicknessPx) {
        if (!Float.isFinite(thicknessPx) || thicknessPx <= 0f) {
            if (bound) detach();
            return false;
        }
        return bindProfile(thicknessPx);
    }

    /** 两个节点均挂载并完成测量后再绑定；普通重排不反复注册采样器。 */
    private boolean bindProfile(float thicknessPx) {
        if (!surface.isAttachedToWindow() || !surface.isHardwareAccelerated()
                || surface.getWidth() <= 0 || surface.getHeight() <= 0
                || !backdrop.isAttachedToWindow() || !backdrop.isHardwareAccelerated()
                || backdrop.getWidth() <= 0 || backdrop.getHeight() <= 0) {
            if (bound) detach();
            return false;
        }
        if (failedThisAttach) return false;
        Api nativeApi = resolve();
        if (nativeApi == null) {
            failedThisAttach = true;
            return false;
        }
        int w = surface.getWidth();
        int h = surface.getHeight();
        boolean profileChanged = profileThicknessPx != thicknessPx;
        if (bound && width == w && height == h && !profileChanged) return true;
        boolean batteryTrack = thicknessPx > 0f;
        // 电池没有普通高斯底材；仍保留原生玻璃自己的双半径采样。
        // 高度必须用 Path 的实际 11dp 厚度，不能用 floor/ceil 后可能多 1px 的 View 高度。
        int backdropRadius = batteryTrack ? 0 : BACKDROP_BLUR;
        int smallRadius = batteryTrack
                ? Math.max(1, Math.min(GLASS_SMALL_BLUR, Math.round(thicknessPx / 4f)))
                : GLASS_SMALL_BLUR;
        int bigRadius = batteryTrack
                ? Math.max(smallRadius, Math.min(GLASS_BIG_BLUR, Math.round(thicknessPx / 2f)))
                : GLASS_BIG_BLUR;
        try {
            if (!bound) {
                attempted = true;
                // 采样层自己不显示高斯底板；它只给子路径提供实时的背景纹理。
                nativeApi.call(nativeApi.clearBlend, backdrop);
                nativeApi.call(nativeApi.passWindow, backdrop, true);
                nativeApi.call(nativeApi.backgroundBlur, backdrop, 1);
                nativeApi.call(nativeApi.viewBlur, backdrop, 0);
                nativeApi.call(nativeApi.backgroundRadius, backdrop, backdropRadius);
                nativeApi.call(nativeApi.glassRadius, backdrop, smallRadius, bigRadius);
                nativeApi.call(nativeApi.containBelow, backdrop, true);

                // TimeView 的路径消费契约；不再让实际环形 View 只走普通 mode=1 模糊。
                nativeApi.call(nativeApi.clearBlend, surface);
                nativeApi.call(nativeApi.passWindow, surface, false);
                nativeApi.call(nativeApi.backgroundBlur, surface, 0);
                nativeApi.call(nativeApi.viewBlur, surface, 3);
                nativeApi.call(nativeApi.chooseContainer, surface, backdrop);
                nativeApi.call(nativeApi.glassRadius, surface, smallRadius, bigRadius);
                nativeApi.call(nativeApi.materialType, surface, MATERIAL_TYPE);
                nativeApi.call(nativeApi.surfaceColor, surface, 16);
            } else if (profileChanged) {
                // 路径真实厚度变化时，即便整数 View 尺寸没变，也要同步两端采样半径。
                // 不拆层、不重新注册采样器，更不重置外层 alpha 或已验证的玻璃 Paint。
                nativeApi.call(nativeApi.backgroundRadius, backdrop, backdropRadius);
                nativeApi.call(nativeApi.glassRadius, backdrop, smallRadius, bigRadius);
                nativeApi.call(nativeApi.glassRadius, surface, smallRadius, bigRadius);
            }
            nativeApi.call(nativeApi.sdfSize, surface, (float) w, (float) h);
            // 和系统路径绘制一样给光学边缘留空间，不用 Outline 把路径边缘提前裁掉。
            nativeApi.call(nativeApi.glassClip, surface, -CLIP_PADDING_PX, -CLIP_PADDING_PX,
                    w + CLIP_PADDING_PX, h + CLIP_PADDING_PX);
            if (!bound) {
                nativeApi.call(nativeApi.glass, surface, (Object) RECIPE.clone());
                // 必须是 drawPath 的同一个 Paint；给 View 填参数不等于启用路径玻璃。
                marker.setStyle(Paint.Style.FILL);
                marker.setColor(0xFFFFFFFF);
                marker.setStrokeWidth(RECIPE[19]);
                marker.setStrokeMiter(RECIPE[20]);
                nativeApi.call(nativeApi.paintGlass, marker, true);
            }
            width = w;
            height = h;
            profileThicknessPx = thicknessPx;
            bound = true;
            if (!loggedBound || loggedThicknessPx != thicknessPx) {
                loggedBound = true;
                loggedThicknessPx = thicknessPx;
                ModuleLog.d("widget material=" + label + "/native-path-configured"
                        + " source=owned-container mode=3 colorType=16 paintGlass=true"
                        + " profile=" + (batteryTrack ? "battery-track" : "ring")
                        + " backdrop=" + backdropRadius + " glass=" + smallRadius + "/" + bigRadius
                        + " thickness=" + thicknessPx + " edge=72/3.8 sdf=" + w + "x" + h);
            }
            return true;
        } catch (Throwable t) {
            nativeApi.clear(surface, backdrop, marker);
            attempted = false;
            bound = false;
            failedThisAttach = true;
            if (!loggedFailure) {
                loggedFailure = true;
                ModuleLog.w("widget material=" + label
                        + "/fallback：系统路径玻璃配置失败", t);
            }
            return false;
        }
    }

    /** 只代表已向硬件画布提交带玻璃标志的路径，不能冒充最终视觉验证。 */
    void onDrawSubmitted() {
        if (bound && !loggedDraw) {
            loggedDraw = true;
            ModuleLog.d("widget material=" + label + "/hardware-path-submitted paintGlass=true");
        }
    }

    void detach() {
        if (attempted && api != null) api.clear(surface, backdrop, marker);
        attempted = false;
        bound = false;
        failedThisAttach = false;
        width = 0;
        height = 0;
        profileThicknessPx = 0f;
    }

    private static synchronized Api resolve() {
        if (!resolved) {
            resolved = true;
            try {
                api = new Api();
            } catch (Throwable t) {
                ModuleLog.w("widget material=fallback：ROM 缺少系统路径玻璃接口", t);
            }
        }
        return api;
    }

    /** 完整解析 View 与 Paint 的必需接口，避免初始化半套 native 状态。 */
    private static final class Api {
        final Method clearBlend = method(View.class, "clearMiBackgroundBlendColor");
        final Method passWindow = method(View.class, "setPassWindowBlurEnabled", boolean.class);
        final Method passWindowState = method(View.class, "getPassWindowBlurEnabled");
        final Method viewBlur = method(View.class, "setMiViewBlurMode", int.class);
        final Method backgroundBlur = method(View.class, "setMiBackgroundBlurMode", int.class);
        final Method backgroundRadius = method(View.class, "setMiBackgroundBlurRadius", int.class);
        final Method glassRadius = method(View.class, "setMiGlassBlurRadius", int.class, int.class);
        final Method materialType = method(View.class, "setMiViewMaterialType", int.class);
        final Method sdfSize = method(View.class, "setMiGlassSdfMaxSize", float.class, float.class);
        final Method glass = method(View.class, "setMiGlass", float[].class);
        final Method chooseContainer = method(View.class, "chooseBackgroundBlurContainer", View.class);
        final Method surfaceColor = method(View.class, "setMiCustomSurfaceColorType", int.class);
        final Method glassClip = method(View.class, "setMiGlassClip",
                float.class, float.class, float.class, float.class);
        final Method paintGlass = method(Paint.class, "setGlassEffect", boolean.class);
        final Method containBelow = method(View.class, "disableMiBackgroundContainBelow", boolean.class);

        Api() throws ReflectiveOperationException { }

        private static Method method(Class<?> owner, String name, Class<?>... parameters)
                throws ReflectiveOperationException {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        }

        void call(Method method, Object target, Object... args) throws Throwable {
            try {
                Object result = method.invoke(target, args);
                if (Boolean.FALSE.equals(result)) {
                    // 实机 framework 的 pass-window setter 在「状态本来相同」时也返回 false。
                    // 只有读回状态与目标一致才接受此幂等返回；真正未开启采样仍必须回退。
                    if (method == passWindow && args.length == 1
                            && args[0].equals(passWindowState.invoke(target))) {
                        return;
                    }
                    throw new IllegalStateException(method.getName() + " returned false");
                }
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }

        void clear(View consumer, View provider, Paint paint) {
            clearOne(paintGlass, paint, false);
            clearOne(materialType, consumer, 0);
            clearOne(glass, consumer, (Object) new float[0]);
            clearOne(surfaceColor, consumer, 0);
            clearOne(chooseContainer, consumer, (Object) null);
            clearOne(glassRadius, consumer, 0, 0);
            clearOne(clearBlend, consumer);
            clearOne(passWindow, consumer, false);
            clearOne(backgroundBlur, consumer, 0);
            clearOne(viewBlur, consumer, 0);
            clearOne(glassClip, consumer, 0f, 0f, 0f, 0f);
            // 消费者停止后，才关闭采样来源。每一项独立清理，失败也继续执行。
            clearOne(passWindow, provider, false);
            clearOne(backgroundBlur, provider, 0);
            clearOne(viewBlur, provider, 0);
            clearOne(glassRadius, provider, 0, 0);
            clearOne(backgroundRadius, provider, 0);
            clearOne(clearBlend, provider);
            clearOne(containBelow, provider, false);
        }

        private void clearOne(Method method, Object target, Object... args) {
            try {
                method.invoke(target, args);
            } catch (Throwable ignored) {
                // 单项清理失败不阻断剩余清理，不拖垮 SystemUI 的 detach。
            }
        }
    }
}
