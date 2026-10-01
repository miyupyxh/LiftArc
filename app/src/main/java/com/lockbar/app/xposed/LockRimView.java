package com.lockbar.app.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 沿锁屏可见底边绘制边缘效果的覆盖层，两种风格由配置切换：
 *
 * <ul>
 *   <li><b>iOS 式压暗（{@link #dim}，新默认）</b>：把整个可见区域填成半透明黑，
 *       靠「压暗的上半屏 vs 裁切线下方的正常壁纸」的明暗对比自然显出那条弧线，
 *       <b>不画任何描边</b> —— 弧形完全来自底边圆角 + 明暗反差。</li>
 *   <li><b>传统描边</b>：只沿底边画一段光边（左下圆角 → 底边 → 右下圆角）。</li>
 * </ul>
 *
 * <p>宽度 / 不透明度 / 颜色由配置调节；<b>不画任何柔光、光晕、半透明薄雾</b> ——
 * 早前版本那圈“柔光”叠出来就是贴着底边的一小块灰色/黑色遮罩，已被整个删掉。
 * 裁切线以下直接就是原壁纸。
 */
final class LockRimView extends View {

    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private LockGlass.Shape shell;

    /** 描边宽度（dp）。 */
    private float strokeWidthDp = 3f;
    /** 描边整体不透明度 0..1（只作用于传统描边；压暗模式看 {@link #dimStrength}）。 */
    private float rimAlpha = 1f;
    /** 压暗强度 0..1（只作用于压暗模式），0.57 = 旧版固定 145/255 那档。 */
    private float dimStrength = 0.57f;
    /** 描边颜色（压暗模式固定用黑，不读这个）。 */
    private int tintColor = 0xFFFFFFFF;
    /** true = iOS 式压暗；false = 传统描边。 */
    private boolean dim = true;

    LockRimView(Context context) {
        super(context);
        rimPaint.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
    }

    void configure(float strokeWidthDp, float rimAlpha, int tintColor, boolean dim,
                   float dimStrength) {
        boolean changed = this.strokeWidthDp != strokeWidthDp
                || this.rimAlpha != rimAlpha
                || this.tintColor != tintColor
                || this.dim != dim
                || this.dimStrength != dimStrength;
        this.strokeWidthDp = strokeWidthDp;
        this.rimAlpha = rimAlpha;
        this.tintColor = tintColor;
        this.dim = dim;
        this.dimStrength = dimStrength;
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
        float base = shape.rimAlpha * (dim ? dimStrength : rimAlpha);
        if (base <= 0.01f) {
            return;
        }

        if (dim) {
            // iOS 式压暗：填满整个可见区域（顶边 edge-height、底边 edge，带屏幕圆角）。
            // 底边那两个圆角就是用户看到的"弧线" —— 弧形来自圆角 + 明暗反差，不画描边。
            // 压暗固定用黑：arcColor 是给描边用的（默认月白），拿它来压会变成提亮。
            // 深浅 = dimStrength（0.57 时与旧版固定 145 那档一致，1.0 = 纯黑）。
            LockGlass.path(path, w, shape);
            rimPaint.setStyle(Paint.Style.FILL);
            rimPaint.setStrokeWidth(0f);
            rimPaint.setColor(argb((int) (255f * base), 0, 0, 0));
            canvas.drawPath(path, rimPaint);
            return;
        }

        // 传统描边：只画底边那一段（左下圆角 → 底边 → 右下圆角），
        // 不画整圈 roundRect：整圈的左右两条边压在屏幕边缘，柔光会糊出灰带。
        LockGlass.bottomPath(path, w, shape);

        float density = getResources().getDisplayMetrics().density;
        int tr = (tintColor >> 16) & 0xFF;
        int tg = (tintColor >> 8) & 0xFF;
        int tb = tintColor & 0xFF;

        float stroke = Math.max(strokeWidthDp * density, 0.5f);

        // 只画弧形描边本体：没有任何柔光/光晕层，底边不铺半透明“薄雾”，
        // 裁切线以下直接就是原壁纸。
        // style/strokeWidth 每帧都要显式写回：同一份 Paint 在压暗分支里被改成了 FILL/0，
        // 切回描边若不复位会画出一条糊掉的粗带。
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(stroke);
        rimPaint.setColor(argb((int) (160f * base), tr, tg, tb));
        canvas.drawPath(path, rimPaint);
    }

    private static int argb(int a, int r, int g, int b) {
        int alpha = Math.max(0, Math.min(255, a));
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }
}
