package com.lockbar.app.xposed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 景深主体上的「玻璃补画层」（depthband1）：underlay1 把玻璃条沉到锁屏内容之下
 * （时钟/通知压在玻璃上是拍板效果），但景深主体抠图层（deducted_image_view 等）不是
 * 内容、是壁纸场景 —— 它长在 keyguard_root 子树里、z 比沉底的玻璃条高，亮带扫过
 * 主体时被它整段挡住，看起来就是「主体上没有玻璃」。
 *
 * <p>本层只干一件事：每帧把玻璃条自己复画进一个 saveLayer，再用主体剪影
 * （ALPHA_8 位图，DST_IN）把带外像素全部剔掉 —— 输出 = 亮带 AND 主体剪影，
 * 贴在锁屏内容之上、overlay（rim/黑纱/小白条/提示字）之下。于是：主体处亮带续上，
 * 时钟/通知处本层全透明、玻璃仍在它们下面（underlay1 一个像素不破）。
 *
 * <p>材质与取样完全复用 {@link LockGlassStrip#draw} 自己的 onDraw（strip.draw(canvas)），
 * 不复制任何 shader、不动任何 K_* 数值；位移、逐帧更新全部跟着 strip 现成的状态走。
 *
 * <p>源层零改动：剪影是只读快照（手势激活时栅格化一次 —— 主体被锚定钉在原位、内容
 * 静态），每帧只按源层当前窗口位置摆放。位图不手动 recycle（hwui 可能还攥着引用，
 * 回收会炸「recycled bitmap」），交 GC；手势归零整层拆除，无残留。
 */
final class DepthBandPass extends View {
    /** 被复画的玻璃条；null = 不画。 */
    private LockGlassStrip strip;
    /** 主体层与其剪影（ALPHA_8，源层自身坐标系）。 */
    private final ArrayList<View> sources = new ArrayList<>();
    private final ArrayList<Bitmap> masks = new ArrayList<>();
    /** 每帧合成的剪影并集（窗口尺寸 ALPHA_8）：DST_IN 的一次性遮罩。 */
    private Bitmap union;
    private Canvas unionCanvas;
    /** 逐源层当前窗口位置（复用数组，onDraw 零分配）。 */
    private final int[] loc = new int[2];
    private final Paint dstPaint = new Paint();

    DepthBandPass(Context context) {
        super(context);
        setWillNotDraw(false);
        dstPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    void setStrip(LockGlassStrip s) {
        strip = s;
    }

    /**
     * 装载主体层：逐层把源层自绘进 ALPHA_8 剪影（连内容带透明度全真），并建并集画布
     * （窗口尺寸）。任何一层失败只丢那一层，其余照挂 —— 但失败**必留日志**：
     * depthband1 首版就是静默吞异常，挂了层却什么都没画，排查只能靠猜。
     */
    void setSources(List<View> src) {
        sources.clear();
        masks.clear();
        union = null;
        unionCanvas = null;
        for (int i = 0; i < src.size(); i++) {
            View v = src.get(i);
            Bitmap m = rasterize(v);
            if (m == null) {
                continue;
            }
            sources.add(v);
            masks.add(m);
        }
        ModuleLog.d("剪影 sources=" + sources.size() + "/" + src.size());
        if (sources.isEmpty()) {
            return;
        }
        try {
            View root = sources.get(0).getRootView();
            int ww = root.getWidth();
            int wh = root.getHeight();
            if (ww <= 0 || wh <= 0) {
                ww = getResources().getDisplayMetrics().widthPixels;
                wh = getResources().getDisplayMetrics().heightPixels;
            }
            union = Bitmap.createBitmap(Math.max(1, ww), Math.max(1, wh),
                    Bitmap.Config.ALPHA_8);
            unionCanvas = new Canvas(union);
            ModuleLog.d("剪影并集 " + union.getWidth() + "x" + union.getHeight());
        } catch (Throwable t) {
            ModuleLog.d("剪影并集失败 " + t);
            union = null;
            unionCanvas = null;
        }
    }

    /**
     * 把源层自绘进 ALPHA_8 剪影。系统侧主体常把内容放在 HARDWARE 位图里，软件画布
     * 直接 {@code v.draw()} 会抛「Software rendering doesn't support hardware bitmaps」
     * —— 正路抛了就从 drawable 抄一份软件副本（{@code Bitmap.copy} 是唯一允许的
     * GPU→CPU 拷贝）再用 ImageView 自己的 {@code getImageMatrix()} 复现同一套变换。
     * 两条路都失败才丢这一层，且必留日志。
     */
    private Bitmap rasterize(View v) {
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        try {
            Bitmap m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            v.draw(new Canvas(m));
            return m;
        } catch (Throwable t) {
            ModuleLog.d("剪影直绘失败 " + v.getClass().getSimpleName() + " " + t);
        }
        try {
            if (!(v instanceof android.widget.ImageView)) {
                return null;
            }
            android.widget.ImageView iv = (android.widget.ImageView) v;
            android.graphics.drawable.Drawable d = iv.getDrawable();
            Bitmap db = null;
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                db = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            }
            if (db == null) {
                ModuleLog.d("剪影兜底无位图 drawable");
                return null;
            }
            Bitmap soft = db;
            if (db.getConfig() == Bitmap.Config.HARDWARE) {
                soft = db.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (soft == null) {
                ModuleLog.d("剪影软件副本失败");
                return null;
            }
            Bitmap m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            Canvas c = new Canvas(m);
            android.graphics.Matrix mx = iv.getImageMatrix();
            if (mx != null) {
                c.drawBitmap(soft, mx, null);
            } else {
                c.drawBitmap(soft, 0f, 0f, null);
            }
            ModuleLog.d("剪影走软件副本 " + w + "x" + h);
            return m;
        } catch (Throwable t) {
            ModuleLog.d("剪影兜底也失败 " + t);
            return null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        LockGlassStrip s = strip;
        if (s == null || union == null || unionCanvas == null || sources.isEmpty()) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // 1) 并集剪影：清空后按各主体层的实时窗口位置重摆（钉住层只平移，不重栅格化）
        union.eraseColor(0);
        for (int i = 0; i < sources.size(); i++) {
            sources.get(i).getLocationInWindow(loc);
            unionCanvas.drawBitmap(masks.get(i), loc[0], loc[1], null);
        }
        // 2) 亮带 AND 剪影：玻璃条整画进 layer，再 DST_IN 一次性剔掉主体之外的像素
        //    （逐层 DST_IN 是交集不是并集，所以必须先合成并集再一次过）
        int save = canvas.saveLayer(0f, 0f, w, h, null);
        s.draw(canvas);
        canvas.drawBitmap(union, 0f, 0f, dstPaint);
        canvas.restoreToCount(save);
    }

    /** 手势归零整拆：清引用即可，位图交 GC（不 recycle，避免 hwui 持旧引用炸掉）。 */
    void clear() {
        sources.clear();
        masks.clear();
        union = null;
        unionCanvas = null;
        strip = null;
        invalidate();
    }
}
