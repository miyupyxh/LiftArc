package com.lockbar.app.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.view.View;

/**
 * 景深主体的「同款毛玻璃」影子层（depthband2）：上滑模糊只糊了壁纸盖层（window 底层），
 * 景深主体抠图层（deducted_image_view 等）被锚定钉在原位、保持锐利 —— 弧线以下露出
 * 的区域里，周围壁纸是磨砂的，主体却是一块锐利的洞（「中间/上部分没有玻璃」）。
 *
 * <p>本层插在主体正上方一个位置（同父、同 LayoutParams），逐帧把主体原样画进自己，
 * 但只画弧线「糊的那半」（与盖层同一份 Shape、同一侧 {@code config.dimUpper}），
 * 再由控制器挂上与盖层完全相同的 RenderEffect。于是：弧线以下主体与背景一起变磨砂、
 * 逐边对齐；弧线以上透出源层自己保持清晰 —— 景深遮挡关系一个像素不动（源层零改动）。
 *
 * <p>取景与 WallCoverView 同一套：窗口坐标弧线壳 + 同侧裁切；手势归零随 restoreBlurAll
 * 整拆，锁屏静止时零 footprint。找不到主体层就不挂（普通壁纸零开销）。
 */
final class DepthFrostView extends View {
    /** 被镜像的主体层；null = 不画。 */
    private View source;
    /** 当前弧线壳（窗口坐标，与 rim/strip/盖层同一份 Shape 快照）。 */
    private LockGlass.Shape shell;
    /** 糊哪半：与盖层同侧（false = 弧线以下，默认）。 */
    private boolean upper;
    private final Path shellPath = new Path();
    private final Path clipPath = new Path();
    private final int[] selfLoc = new int[2];
    private final int[] srcLoc = new int[2];

    DepthFrostView(Context context, View src, android.view.ViewGroup.LayoutParams lp) {
        super(context);
        setWillNotDraw(false);
        source = src;
        setLayoutParams(lp);
        if (src != null) {
            setAlpha(src.getAlpha());
        }
    }

    /**
     * 逐帧喂分界弧线（与盖层同一份，控制器同点分发）。apply() 只在进度真变了才跑，
     * 手指停住时本方法根本不会被调，所以这里用对象同一性比较即可。
     */
    void setShell(LockGlass.Shape shape, boolean blurUpper) {
        if (shape == shell && blurUpper == upper) {
            return;
        }
        shell = shape;
        upper = blurUpper;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        View src = source;
        if (src == null || shell == null || src.getVisibility() != VISIBLE) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        View root = getRootView();
        int ww = root.getWidth();
        int wh = root.getHeight();
        if (ww <= 0 || wh <= 0) {
            return;
        }
        // 画布挪到窗口坐标系：弧线壳是窗口坐标，源层位置也是窗口坐标
        getLocationInWindow(selfLoc);
        canvas.save();
        canvas.translate(-selfLoc[0], -selfLoc[1]);
        // 与盖层同一条弧、同一侧：糊的那半才画，另一侧一个像素不留
        LockGlass.path(shellPath, ww, shell);
        if (upper) {
            canvas.clipPath(shellPath);
        } else {
            clipPath.reset();
            clipPath.addRect(0f, 0f, ww, wh, Path.Direction.CW);
            clipPath.op(shellPath, Path.Op.DIFFERENCE);
            canvas.clipPath(clipPath);
        }
        // 主体原样镜像：它自己的平移已含在窗口位置里，draw() 不带自身 translation
        src.getLocationInWindow(srcLoc);
        canvas.translate(srcLoc[0], srcLoc[1]);
        src.draw(canvas);
        canvas.restore();
    }

    /** 拆层：只清引用；源层从头到尾没被碰过，位图/效果无残留。 */
    void clear() {
        source = null;
        shell = null;
    }
}
