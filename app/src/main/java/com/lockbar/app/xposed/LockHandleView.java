package com.lockbar.app.xposed;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 锁屏底部「小白条」的触控区。
 *
 * <p><b>条本体用系统自带的那一条</b>：直接 new 一个 SystemUI 自己的
 * {@code com.android.systemui.navigationbar.gestural.NavigationHandle} 作为子 View，
 * 尺寸（屏宽 32%）、圆角（{@code navigation_handle_radius} = 2.5dp → 高 5dp）、
 * 位置（{@code navigation_handle_bottom} = 7.5dp）、颜色（{@code homeHandleColor}）
 * 全部来自系统资源，跟手势导航那条完全一致。反射失败时才退化成按同款参数自绘。
 *
 * <p><b>触控区就是这条方框</b>：高 36dp、宽 = 条宽 + 左右各 16dp，贴着屏幕底边
 * （条周围 5dp 以上都能触发），不再是原来那块 44dp 高、盖在小白条上方的大区域。
 *
 * <p>本 View 同时承担上滑手势的捕获；系统 NavigationHandle 不可点击，
 * 事件会正常回落到本 View。
 */
final class LockHandleView extends FrameLayout {

    /** 手势回调，progress 始终为 0..1。 */
    interface Callback {
        void onDragStart(float y);

        void onDragMove(float dy, float progress);

        void onDragEnd(float dy, float progress, boolean cancel);
    }

    /** navigation_handle_radius */
    private static final float SYSTEM_RADIUS_DP = 2.5f;
    /** navigation_handle_bottom */
    private static final float SYSTEM_BOTTOM_DP = 7.5f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private Callback callback;
    private int handleColor;
    /** 系统 handle 装配失败时才自绘。 */
    private boolean fallback = true;

    private float downY;
    private float totalDy;
    private boolean dragging;
    /** 本次手势累计位移换算成进度时的分母（屏幕高度的某个比例）。 */
    private float rangePx = 1f;

    LockHandleView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
        setClipChildren(false);
        // 自绘分支用系统 homeHandleColor；透明度走 View.alpha（由控制器统一给）
        handleColor = systemHandleColor();
    }

    void setCallback(Callback callback) {
        this.callback = callback;
    }

    /** 装配系统自带的手势条。 */
    void attachSystemHandle(View systemHandle) {
        if (systemHandle == null) {
            return;
        }
        fallback = false;
        addView(systemHandle, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        post(this::restoreChildAlpha);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 系统 NavigationHandle 在它自己的 onAttachedToWindow 里会按
        // HomeHandleVisibilityController 把自己设成透明（系统那条在锁屏上就是隐藏的）。
        // 这里的 post 排在子 View attach 之后执行，保证恢复可见。
        post(this::restoreChildAlpha);
    }

    private void restoreChildAlpha() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getAlpha() != 1f) {
                child.setAlpha(1f);
                child.invalidate();
            }
        }
    }

    void setRange(float rangePx) {
        this.rangePx = Math.max(rangePx, 1f);
    }

    /** 供外部在回弹动画结束后把进度归零。 */
    void resetGesture() {
        dragging = false;
        totalDy = 0f;
    }

    /** 系统手势条的宽度：屏宽 * 0.32（竖屏）/ 0.21（横屏）。 */
    float systemBarWidth() {
        return getResources().getDisplayMetrics().widthPixels * factor();
    }

    private float factor() {
        int orientation = getResources().getConfiguration().orientation;
        return orientation == Configuration.ORIENTATION_PORTRAIT ? 0.32f : 0.21f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!fallback) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float radius = SYSTEM_RADIUS_DP * density;
        float barH = radius * 2f;
        float barW = Math.min(w, systemBarWidth());
        float left = (w - barW) / 2f;
        float bottom = h - SYSTEM_BOTTOM_DP * density;
        if (bottom - barH < 0f) {
            bottom = h;
        }
        rect.set(left, bottom - barH, left + barW, bottom);
        paint.setColor(handleColor);
        paint.setAlpha((int) (255f * getAlpha()));
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getRawY();
                totalDy = 0f;
                dragging = false;
                // 一按下就告诉父窗口别拦截：锁屏窗口的 onInterceptTouchEvent 里
                // 有一堆 MIUI 的手势判断，晚一步这条手势就可能被抢走
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (callback != null) {
                    callback.onDragStart(downY);
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dy = downY - event.getRawY();   // 上滑为正
                totalDy = dy;
                if (!dragging && Math.abs(dy) > 4f * getResources().getDisplayMetrics().density) {
                    dragging = true;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (dragging && callback != null) {
                    float progress = LockGlass.clamp01(totalDy / rangePx);
                    callback.onDragMove(totalDy, progress);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (callback != null) {
                    float progress = LockGlass.clamp01(totalDy / rangePx);
                    callback.onDragEnd(totalDy, progress, cancel || !dragging);
                }
                dragging = false;
                performClick();
                return true;
            }

            default:
                return super.onTouchEvent(event);
        }
    }

    /**
     * 系统 {@code homeHandleColor}（浅色主题那一档，通常就是半透明白）；
     * 读不到就退回 {@code navigation_bar_home_handle_light_color} = #80FFFFFF。
     */
    int systemHandleColor() {
        try {
            int attr = getResources().getIdentifier("homeHandleColor", "attr", "android");
            if (attr == 0) {
                attr = getResources().getIdentifier("homeHandleColor", "attr",
                        "com.android.systemui");
            }
            if (attr != 0) {
                TypedValue tv = new TypedValue();
                if (getContext().getTheme().resolveAttribute(attr, tv, true)) {
                    if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                            && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                        return tv.data;
                    }
                    if (tv.resourceId != 0) {
                        TypedValue value = new TypedValue();
                        getResources().getValue(tv.resourceId, value, true);
                        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                                && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                            return value.data;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读不到就用系统默认值
        }
        return 0x80FFFFFF;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
