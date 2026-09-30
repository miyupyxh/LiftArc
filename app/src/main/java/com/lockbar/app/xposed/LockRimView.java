package com.lockbar.app.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 沿锁屏可见底边绘制「弧形描边」的覆盖层。
 *
 * 描边宽度 / 不透明度 / 颜色都可由配置调节；<b>不画任何柔光、光晕、半透明薄雾</b> ——
 * 早前版本那圈“柔光”叠出来就是贴着底边的一小块灰色/黑色遮罩，已被整个删掉。
 * 描边也只画底边那一段（左下圆角 → 底边 → 右下圆角）：
 * 裁切线以下直接就是原壁纸。
 */
final class LockRimView extends View {

    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private LockGlass.Shape shell;

    /** 描边宽度（dp）。 */
    private float strokeWidthDp = 3f;
    /** 描边整体不透明度 0..1。 */
    private float rimAlpha = 1f;
    /** 描边颜色。 */
    private int tintColor = 0xFFFFFFFF;

    LockRimView(Context context) {
        super(context);
        rimPaint.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
    }

    void configure(float strokeWidthDp, float rimAlpha, int tintColor) {
        boolean changed = this.strokeWidthDp != strokeWidthDp
                || this.rimAlpha != rimAlpha
                || this.tintColor != tintColor;
        this.strokeWidthDp = strokeWidthDp;
        this.rimAlpha = rimAlpha;
        this.tintColor = tintColor;
        if (changed) {
            invalidate();
        }
    }

    /** 更新壳；返回是否发生了变化。 */
    boolean update(LockGlass.Shape shape) {
        if (same(shell, shape)) {
            return false;
        }
        shell = shape;
        invalidate();
        return true;
    }

    void clear() {
        if (shell != null) {
            shell = null;
            invalidate();
        }
    }

    private static boolean same(LockGlass.Shape a, LockGlass.Shape b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.visibleHeight == b.visibleHeight
                && a.top == b.top
                && a.topRadius == b.topRadius
                && a.bottomRadius == b.bottomRadius
                && a.rimAlpha == b.rimAlpha;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        LockGlass.Shape shape = shell;
        int w = getWidth();
        if (shape == null || w <= 0 || shape.visibleHeight <= 0f) {
            return;
        }
        // 只画可见底边那一段（左下圆角 → 底边 → 右下圆角），
        // 不画整圈 roundRect：整圈的左右两条边压在屏幕边缘，柔光会糊出灰带。
        LockGlass.bottomPath(path, w, shape);

        float density = getResources().getDisplayMetrics().density;
        float base = shape.rimAlpha * rimAlpha;
        if (base <= 0.01f) {
            return;
        }

        int tr = (tintColor >> 16) & 0xFF;
        int tg = (tintColor >> 8) & 0xFF;
        int tb = tintColor & 0xFF;

        float stroke = Math.max(strokeWidthDp * density, 0.5f);

        // 只画弧形描边本体：没有任何柔光/光晕层，底边不铺半透明“薄雾”，
        // 裁切线以下直接就是原壁纸
        rimPaint.setStrokeWidth(stroke);
        rimPaint.setColor(argb((int) (160f * base), tr, tg, tb));
        canvas.drawPath(path, rimPaint);
    }

    private static int argb(int a, int r, int g, int b) {
        int alpha = Math.max(0, Math.min(255, a));
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }
}
