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
 *   <li><b>iOS 式压暗（{@link #dim}，新默认）</b>：只压弧线<b>以下</b>露出的区域（屏幕下部），
 *       上部锁屏内容保持原样；压暗值 = {@link #dimStrength} 固定强度，不随上滑进度递增。
 *       靠「自然内容 vs 压暗的下部」的明暗对比显出那条弧线，<b>不画任何描边</b>。</li>
 *   <li><b>传统描边</b>：只沿底边画一段光边（左下圆角 → 底边 → 右下圆角）。</li>
 * </ul>
 *
 * <p>宽度 / 不透明度 / 颜色由配置调节；<b>不画任何柔光、光晕、半透明薄雾</b> ——
 * 早前版本那圈“柔光”叠出来就是贴着底边的一小块灰色/黑色遮罩，已被整个删掉。
 * 裁切线以下压不压暗由 {@link #dim} 决定：压暗模式压黑，描边模式露原壁纸。
 */
final class LockRimView extends View {

    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    /** 压暗模式专用：整窗减去面板壳后剩下的「露出区」Path。 */
    private final Path revealPath = new Path();

    private LockGlass.Shape shell;

    /** 描边宽度（dp）。 */
    private float strokeWidthDp = 3f;
    /** 描边整体不透明度 0..1（只作用于传统描边；压暗模式看 {@link #dimStrength}）。 */
    private float rimAlpha = 1f;
    /** 压暗强度 0..1（只作用于压暗模式），0.57 = 旧版固定 145/255 那档。
     *  固定值直接套用，不随上滑进度递增。 */
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
        if (dim) {
            // iOS 式压暗（1.0.20 反转）：只压弧线以下露出的区域（屏幕下部），
            // 上部锁屏内容完全不动 —— 旧版整块面板压暗 + smoothstep(g) 递增已废，
            // 那样上部内容跟着变黑、越滑越黑，与 iOS 的「上部不变、下部压暗」相反。
            // 深浅 = dimStrength 固定值，手势一到就到满，不随进度变（不要逐级递增）。
            // 压暗固定用黑：arcColor 是给描边用的（默认月白），拿它来压会变成提亮。
            int vh = getHeight();
            if (dimStrength <= 0.01f || vh <= 0) {
                return;
            }
            // 面板壳与裁切同一条 Path；露出区 = 整个窗口减去面板壳
            // （底边圆角外侧那两个小角也归压暗 —— 弧线就从这条分界线上来）。
            LockGlass.path(path, w, shape);
            revealPath.reset();
            revealPath.addRect(0f, 0f, w, vh, Path.Direction.CW);
            revealPath.op(path, Path.Op.DIFFERENCE);
            rimPaint.setStyle(Paint.Style.FILL);
            rimPaint.setStrokeWidth(0f);
            rimPaint.setColor(argb((int) (255f * dimStrength), 0, 0, 0));
            canvas.drawPath(revealPath, rimPaint);
            return;
        }

        float base = shape.rimAlpha * rimAlpha;
        if (base <= 0.01f) {
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
