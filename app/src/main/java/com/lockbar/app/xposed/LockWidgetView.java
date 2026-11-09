package com.lockbar.app.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 锁屏小组件：左边电池（条形），右边天气、步数两个环形。
 *
 * <p>保留原有 145×66dp 电池版式、81dp 环形与 14dp 间距。系统柔光材质只绑定
 * 三个独立的背景子 View；图标、文字、指针和电量填充在其上绘制，不参与玻璃遮罩。
 * 外层仍是 Controller 持有的同一个控件，位置、触摸与 AOD 渐隐/渐显不另起一套状态。
 *
 * <p>没数据的项目不占位。原生接口不可用时才保留旧背景，并明确记录 fallback。
 */
final class LockWidgetView extends ViewGroup {

    /** 环形基准直径（dp），与参照版式一致。 */
    private static final float RING_BASE = 81f;
    /** 电池块基准宽（dp），与 {@code lock_screen_slogan} 的进度条等宽。 */
    private static final float BATTERY_BASE = 145f;
    /** 电池块基准高（dp）：23（图标行）+ 11（间距）+ 11（进度条）。 */
    private static final float BATTERY_BASE_H = 66f;
    /** 件与件之间的间距（dp，按 81dp 基准）。 */
    private static final float GAP_BASE = 14f;

    private final Paint ringFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();
    private final RectF oval = new RectF();

    private final Path ringPath = new Path();
    private final GlassSurface batteryGlass;
    private final GlassSurface weatherGlass;
    private final GlassSurface stepsGlass;

    private float sizeDp = -1f;
    private float k = 1f;
    private float density = 3f;
    private int samplingOriginX = Integer.MIN_VALUE;
    private int samplingOriginY = Integer.MIN_VALUE;

    // ---- 电池
    private boolean hasBattery;
    private int batteryPct = -1;
    private boolean charging;
    // ---- 天气
    private boolean hasWeather;
    private String weatherTemp = "";
    private String weatherRange = "";
    private int weatherType = 1;
    private float weatherRatio = 1f;
    private boolean night;
    // ---- 步数
    private boolean hasSteps;
    private String stepsValue = "";
    private String stepsEnergy = "";
    private float stepsRatio;

    /** 壁纸（降采样 + 模糊）—— 环里那层「玻璃」用它当折射源，没有它就只是半透明白。 */
    private static android.graphics.Bitmap wallpaper;
    private static boolean wallpaperLoading;
    private static int wallId = Integer.MIN_VALUE;
    private long lastWallCheck;
    private static android.graphics.BitmapShader wallpaperShader;

    /** 内嵌 PNG 解出来的电池图标（两态，只解一次）。 */
    private static android.graphics.Bitmap iconNormal;
    private static android.graphics.Bitmap iconCharging;
    private static boolean iconsDecoded;

    /** 机型文字（跟参照包的 BatteryCustomTextView 一样贴在电量下面）。 */
    private final String modelText;

    LockWidgetView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setColor(Color.WHITE);
        valuePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        captionPaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setColor(0xE6FFFFFF);
        ringFillPaint.setStyle(Paint.Style.FILL);
        glassPaint.setStyle(Paint.Style.FILL);
        barBg.setStyle(Paint.Style.FILL);
        barBg.setColor(0x33FFFFFF);
        modelText = resolveModelName();
        setClipChildren(false);
        setClipToPadding(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        batteryGlass = new GlassSurface(context, "battery-track", false);
        weatherGlass = new GlassSurface(context, "weather-ring", true);
        stepsGlass = new GlassSurface(context, "steps-ring", true);
        addView(batteryGlass, new LayoutParams(0, 0));
        addView(weatherGlass, new LayoutParams(0, 0));
        addView(stepsGlass, new LayoutParams(0, 0));
        setSizeDp(RING_BASE);
    }

    /**
     * 读机型名：先试 HyperOS 的市场名（{@code ro.product.marketname}），
     * 再退到 {@code Build.MANUFACTURER + Build.MODEL}。都拿不到就留空（不画那一行）。
     */
    private static String resolveModelName() {
        String name = null;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, "ro.product.marketname");
            if (v instanceof String) {
                name = (String) v;
            }
        } catch (Throwable ignored) {
            // 没有这个属性就走下面的合成
        }
        if (name == null || name.trim().isEmpty()) {
            String maker = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER;
            String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL;
            if (model.toLowerCase(java.util.Locale.ROOT).startsWith(maker.toLowerCase(java.util.Locale.ROOT))) {
                name = model;
            } else {
                name = (maker + " " + model).trim();
            }
        }
        if (name != null && name.length() > 22) {
            name = name.substring(0, 22);
        }
        return name == null ? "" : name;
    }

    /** 设置玻璃采样用的窗口原点；显式传入 overlay 平移后的坐标。 */
    void setSamplingOrigin(int x, int y) {
        if (samplingOriginX != x || samplingOriginY != y) {
            samplingOriginX = x;
            samplingOriginY = y;
            invalidate();
        }
    }

    void setSizeDp(float dp) {
        if (dp <= 0f) {
            dp = RING_BASE;
        }
        if (Math.abs(sizeDp - dp) < 0.01f) {
            return;
        }
        sizeDp = dp;
        k = dp / RING_BASE;
        requestLayout();
        invalidate();
    }

    void setBattery(int pct, boolean chargingNow) {
        hasBattery = pct >= 0;
        batteryPct = pct;
        charging = chargingNow;
        requestLayout();
        invalidate();
    }

    void setWeather(String temp, String range, int type, float progress, int hour) {
        hasWeather = temp != null && !temp.isEmpty();
        weatherTemp = temp == null ? "" : temp;
        weatherRange = range == null ? "" : range;
        weatherType = type;
        weatherRatio = Math.max(0f, Math.min(1f, progress));
        night = hour >= 19 || hour < 6;
        requestLayout();
        invalidate();
    }

    void setSteps(String steps, String energy, float progress) {
        hasSteps = steps != null && !steps.isEmpty();
        stepsValue = steps == null ? "" : steps;
        stepsEnergy = energy == null ? "" : energy;
        stepsRatio = Math.max(0f, Math.min(1f, progress));
        requestLayout();
        invalidate();
    }

    private float px(float base) {
        return base * k * density;
    }

    private float gapPx() {
        return px(GAP_BASE);
    }

    private int visibleCount() {
        int n = 0;
        if (hasBattery) {
            n++;
        }
        if (hasWeather) {
            n++;
        }
        if (hasSteps) {
            n++;
        }
        return n;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int n = visibleCount();
        float w = 0f;
        float h = px(RING_BASE);
        if (n > 0) {
            if (hasBattery) {
                w += px(BATTERY_BASE) + gapPx();
            }
            if (hasWeather) {
                w += px(RING_BASE) + gapPx();
            }
            if (hasSteps) {
                w += px(RING_BASE) + gapPx();
            }
            w -= gapPx();
        }
        setMeasuredDimension(Math.max(0, Math.round(w)), Math.max(1, Math.round(h)));

        // 背景与前景共用同一组浮点几何。子 View 取整后的余量留在 Path 中，
        // 不因换成原生材质而改变环心、间距或电池轨道的半像素位置。
        float x = 0f;
        float batteryTop = (h - px(BATTERY_BASE_H)) / 2f;
        batteryGlass.prepare(hasBattery, x, batteryTrackTop(batteryTop),
                px(BATTERY_BASE), px(11f));
        if (hasBattery) x += px(BATTERY_BASE) + gapPx();
        weatherGlass.prepare(hasWeather, x, 0f, h, h);
        if (hasWeather) x += h + gapPx();
        stepsGlass.prepare(hasSteps, x, 0f, h, h);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        batteryGlass.place();
        weatherGlass.place();
        stepsGlass.place();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // 先绘制独立玻璃背景，再画前景；不能把整行文字当成系统玻璃的遮罩。
        super.dispatchDraw(canvas);
        if (visibleCount() == 0) {
            return;
        }
        float x = 0f;
        float h = px(RING_BASE);
        if (hasBattery) {
            float bw = px(BATTERY_BASE);
            float bh = px(BATTERY_BASE_H);
            drawBatteryBlock(canvas, x, (h - bh) / 2f, bw, bh);
            x += bw + gapPx();
        }
        if (hasWeather) {
            drawRing(canvas, 0, x, 0f, px(RING_BASE), weatherTemp, weatherRange,
                    weatherRatio, true, weatherType, night);
            x += px(RING_BASE) + gapPx();
        }
        if (hasSteps) {
            drawRing(canvas, 1, x, 0f, px(RING_BASE), stepsValue, stepsEnergy,
                    stepsRatio, false, 0, false);
        }
    }

    /**
     * 每个组件自己的采样容器与路径消费者。采样容器不画模糊底板；
     * 子 View 直接用带 setGlassEffect 标志的 Paint 绘制原有 Path。
     */
    private final class GlassSurface extends FrameLayout {
        private final LockWidgetGlass material;
        private final boolean ring;
        private final Path maskPath = new Path();
        private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final PathRenderer renderer;
        private boolean present;
        private float shapeX = Float.NaN;
        private float shapeY;
        private float shapeW;
        private float shapeH;
        private int layoutLeft;
        private int layoutTop;
        private int layoutRight;
        private int layoutBottom;

        GlassSurface(Context context, String label, boolean ring) {
            super(context);
            this.ring = ring;
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            marker.setStyle(Paint.Style.FILL);
            marker.setColor(Color.WHITE);
            renderer = new PathRenderer(context);
            material = new LockWidgetGlass(renderer, this, marker, label);
            addView(renderer, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        void prepare(boolean show, float x, float y, float w, float h) {
            present = show;
            setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            if (!show) {
                material.detach();
                shapeX = Float.NaN;
                layoutLeft = layoutTop = layoutRight = layoutBottom = 0;
                measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY));
                return;
            }
            if (shapeX != x || shapeY != y || shapeW != w || shapeH != h) {
                shapeX = x;
                shapeY = y;
                shapeW = w;
                shapeH = h;
                layoutLeft = (int) Math.floor(x);
                layoutTop = (int) Math.floor(y);
                layoutRight = (int) Math.ceil(x + w);
                layoutBottom = (int) Math.ceil(y + h);
                float localX = x - layoutLeft;
                float localY = y - layoutTop;
                maskPath.reset();
                if (ring) {
                    buildRingPath(maskPath, localX, localY, w);
                } else {
                    maskPath.addRoundRect(localX, localY, localX + w, localY + h,
                            h / 2f, h / 2f, Path.Direction.CW);
                }
                renderer.invalidate();
            }
            measure(MeasureSpec.makeMeasureSpec(layoutRight - layoutLeft, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(layoutBottom - layoutTop, MeasureSpec.EXACTLY));
        }

        void place() {
            layout(layoutLeft, layoutTop, layoutRight, layoutBottom);
            bindMaterial();
        }

        boolean hasNativeMaterial() {
            return present && material.isBound();
        }

        private void bindMaterial() {
            if (!present) {
                material.detach();
                return;
            }
            boolean wasBound = material.isBound();
            boolean nativeReady = ring ? material.bind() : material.bindBatteryTrack(shapeH);
            if (wasBound != nativeReady) {
                renderer.invalidate();
                LockWidgetView.this.invalidate();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            material.detach();
            super.onDetachedFromWindow();
        }

        private final class PathRenderer extends View {
            PathRenderer(Context context) {
                super(context);
                setWillNotDraw(false);
                setClickable(false);
                setFocusable(false);
                setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                // 路径自身定义玻璃形状。Outline 的几何裁切不能代替 native 路径 SDF，
                // 更不能把光学边缘提前裁掉；边界由 setMiGlassClip 单独提供。
                setClipToOutline(false);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (!present || !material.isBound() || !canvas.isHardwareAccelerated()) return;
                canvas.drawPath(maskPath, marker);
                material.onDrawSubmitted();
            }

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                // 父采样层与子绘制层均已 attach 后重绑，覆盖层序修正的 detach/re-attach。
                if (material != null) bindMaterial();
            }

            @Override
            protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                if (visibility == View.VISIBLE && material != null) bindMaterial();
            }
        }
    }

    private boolean nativeMaterialsReady() {
        return visibleCount() > 0
                && (!hasBattery || batteryGlass.hasNativeMaterial())
                && (!hasWeather || weatherGlass.hasNativeMaterial())
                && (!hasSteps || stepsGlass.hasNativeMaterial());
    }

    // ------------------------------------------------------------------ 环形

    /**
     * 单个环：几何 = {@code pgRing}（外弧 R+18、内弧 R−18、150°→30°、两端半径 18 收口），
     * 填充 = {@code 0x33FFFFFF} + 系统玻璃 Paint；指针 = 白点（半径 16px）+ 2px 淡描边。
     */
    private void drawRing(Canvas canvas, int slot, float left, float top, float size,
                          String text, String caption, float ratio,
                          boolean weather, int type, boolean isNight) {
        float cx = left + size / 2f;
        float cy = top + size / 2f;
        float inset = size * (20f / 243f);
        oval.set(left + inset, top + inset, left + size - inset, top + size - inset);
        float r = oval.width() / 2f;
        buildRingPath(ringPath, left, top, size);

        GlassSurface background = slot == 0 ? weatherGlass : stepsGlass;
        if (!canvas.isHardwareAccelerated() || !background.hasNativeMaterial()) {
            // 仅原生接口不可用时保留旧版背景；成功时绝不叠加位图/假高光遮住系统材质。
            int[] viewLoc = new int[2];
            if (samplingOriginX != Integer.MIN_VALUE && samplingOriginY != Integer.MIN_VALUE) {
                viewLoc[0] = samplingOriginX;
                viewLoc[1] = samplingOriginY;
            } else {
                getLocationInWindow(viewLoc);
            }
            updateGlassShader(viewLoc[0], viewLoc[1]);
            boolean glassDrawn = wallpaperShader != null;
            if (glassDrawn) {
                glassPaint.setShader(wallpaperShader);
                glassPaint.setStyle(Paint.Style.FILL);
                glassPaint.setAlpha(255);
                glassPaint.setFilterBitmap(true);
                canvas.drawPath(ringPath, glassPaint);
                glassPaint.setShader(null);
            }
            ringFillPaint.setShader(null);
            ringFillPaint.setStyle(Paint.Style.FILL);
            ringFillPaint.setColor(glassDrawn ? 0x1CFFFFFF : 0x55FFFFFF);
            canvas.drawPath(ringPath, ringFillPaint);
            ringFillPaint.setStyle(Paint.Style.STROKE);
            ringFillPaint.setStrokeWidth(Math.max(1f, size * (2.5f / 243f)));
            ringFillPaint.setColor(0x5CFFFFFF);
            canvas.drawPath(ringPath, ringFillPaint);
            ringFillPaint.setStyle(Paint.Style.FILL);
        }

        // 指针
        double angle = Math.toRadians(150f + 240f * ratio);
        float dotX = cx + (float) Math.cos(angle) * r;
        float dotY = cy + (float) Math.sin(angle) * r;
        float dotR = size * (16f / 243f);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(0xFFFFFFFF);
        canvas.drawCircle(dotX, dotY, dotR, dot);
        dot.setStyle(Paint.Style.STROKE);
        dot.setStrokeWidth(Math.max(1f, size * (2f / 243f)));
        dot.setColor(0x33000000);
        canvas.drawCircle(dotX, dotY, dotR, dot);

        // 图标 22dp
        float iconSize = px(22f);
        float iconCy = top + size * 0.288f;
        if (weather) {
            LockWeatherIcons.draw(canvas, type, isNight, cx, iconCy, iconSize, iconPaint);
        } else {
            drawStepsIcon(canvas, cx, iconCy, iconSize);
        }

        // 主数值 18sp / 说明 14sp（版式里纵向压扁 0.86）
        canvas.save();
        canvas.scale(1f, 0.86f, cx, top + size * 0.60f);
        valuePaint.setTextSize(18f * k * density);
        canvas.drawText(text, cx, top + size * 0.62f, valuePaint);
        canvas.restore();
        if (caption != null && !caption.isEmpty()) {
            canvas.save();
            canvas.scale(1f, 0.86f, cx, top + size * 0.79f);
            captionPaint.setTextSize(14f * k * density);
            canvas.drawText(caption, cx, top + size * 0.81f, captionPaint);
            canvas.restore();
        }
    }

    private final android.graphics.Matrix shaderMatrix = new android.graphics.Matrix();

    /**
     * 把壁纸 shader 的局部矩阵对齐到“屏幕坐标”：
     * 壁纸按屏幕 center-crop 铺，控件在 (viewX, viewY)，
     * 所以本地坐标 local → 屏幕 = local + view。
     */
    private void updateGlassShader(int viewX, int viewY) {
        android.graphics.Bitmap bmp = wallpaper;
        if (bmp == null || bmp.isRecycled()) {
            wallpaperShader = null;
            return;
        }
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        float scale = Math.max(sw / (float) bmp.getWidth(), sh / (float) bmp.getHeight());
        float offX = (sw - bmp.getWidth() * scale) * 0.5f;
        float offY = (sh - bmp.getHeight() * scale) * 0.5f;
        if (wallpaperShader == null) {
            wallpaperShader = new android.graphics.BitmapShader(bmp,
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP);
        }
        shaderMatrix.setScale(scale, scale);
        shaderMatrix.postTranslate(offX - viewX, offY - viewY);
        wallpaperShader.setLocalMatrix(shaderMatrix);
    }

    /** 把环路径建到 (left, top) 为原点的坐标系里（左/上都是该环自己的左上角）。 */
    private static void buildRingPath(Path path, float left, float top, float size) {
        float cx = left + size / 2f;
        float cy = top + size / 2f;
        float inset = size * (20f / 243f);
        float r = (size - inset * 2f) / 2f;
        float capR = size * (18f / 243f);
        path.reset();
        arc(path, cx, cy, r + capR, r + capR, 150f, 390f, 60, true);
        arc(path, cx + 0.8660254f * r, cy + 0.5f * r, capR, capR, 390f, 570f, 15, false);
        arc(path, cx, cy, r - capR, r - capR, 390f, 150f, 60, false);
        arc(path, cx - 0.8660254f * r, cy + 0.5f * r, capR, capR, 330f, 510f, 15, false);
        path.close();
    }

    private static void arc(Path path, float cx, float cy, float rx, float ry,                            float startDeg, float endDeg, int steps, boolean move) {
        double rad = Math.toRadians(startDeg);
        float x = cx + (float) Math.cos(rad) * rx;
        float y = cy + (float) Math.sin(rad) * ry;
        if (move) {
            path.moveTo(x, y);
        } else {
            path.lineTo(x, y);
        }
        float delta = (endDeg - startDeg) / steps;
        for (int i = 1; i <= steps; i++) {
            double a = Math.toRadians(startDeg + i * delta);
            path.lineTo(cx + (float) Math.cos(a) * rx, cy + (float) Math.sin(a) * ry);
        }
    }

    private int getScreenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int getScreenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private void drawStepsIcon(Canvas canvas, float cx, float cy, float size) {
        iconPaint.setStyle(Paint.Style.FILL);
        iconPaint.setColor(0xFFFFFFFF);
        float half = size / 2f;
        canvas.save();
        canvas.rotate(-16f, cx, cy);
        tmpRect.set(cx - half * 0.62f, cy - half * 0.66f,
                cx - half * 0.12f, cy + half * 0.08f);
        canvas.drawOval(tmpRect, iconPaint);
        canvas.restore();
        canvas.save();
        canvas.rotate(16f, cx, cy);
        tmpRect.set(cx + half * 0.12f, cy - half * 0.08f,
                cx + half * 0.62f, cy + half * 0.66f);
        canvas.drawOval(tmpRect, iconPaint);
        canvas.restore();
    }

    // ------------------------------------------------------------------ 电池块

    /** 解一次内嵌的 PNG（第一次画时才解，不压主线程启动）。 */
    private static void decodeIconsOnce() {
        if (iconsDecoded) {
            return;
        }
        iconsDecoded = true;
        try {
            iconNormal = decodeB64(LockBatteryIconData.NORMAL);
        } catch (Throwable ignored) {
            iconNormal = null;
        }
        try {
            iconCharging = decodeB64(LockBatteryIconData.CHARGING);
        } catch (Throwable ignored) {
            iconCharging = null;
        }
    }

    private static android.graphics.Bitmap decodeB64(String s) {
        byte[] raw = android.util.Base64.decode(s, android.util.Base64.DEFAULT);
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
        return android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
    }

    private float batteryTrackTop(float blockTop) {
        float modelTop = blockTop + px(23f) + px(2f);
        return modelTop + 16f * k * density + px(8f);
    }

    private void drawBatteryBlock(Canvas canvas, float left, float top, float w, float h) {
        float iconW = px(16f);
        float iconH = px(23f);

        int pct = Math.max(0, Math.min(100, batteryPct));
        // 未充电 = 白色条；充电才用绿色。低电不再变红（照参照图）
        int accent = charging ? 0xFF34C759 : 0xFFFFFFFF;

        // 手机形电池图标：用参照包的 PNG（96×138）缩放绘制
        decodeIconsOnce();
        android.graphics.Bitmap shell = charging ? iconCharging : iconNormal;
        if (shell != null && !shell.isRecycled()) {
            // 图标就当图标：不在里面标电量（电量看右边的百分比与下面的条）
            iconPaint.setStyle(Paint.Style.FILL);
            iconPaint.setAlpha(255);
            iconPaint.setFilterBitmap(true);
            canvas.drawBitmap(shell, null,
                    new android.graphics.Rect(Math.round(left), Math.round(top),
                            Math.round(left + iconW), Math.round(top + iconH)),
                    iconPaint);
        }

        valuePaint.setTextAlign(Paint.Align.LEFT);
        valuePaint.setTextSize(16f * k * density);
        canvas.drawText(pct + "%", left + iconW + px(6f), top + iconH * 0.82f, valuePaint);
        if (charging) {
            captionPaint.setTextAlign(Paint.Align.LEFT);
            captionPaint.setTextSize(12f * k * density);
            canvas.drawText("充电中",
                    left + iconW + px(6f) + valuePaint.measureText(pct + "%") + px(6f),
                    top + iconH * 0.82f, captionPaint);
        }
        valuePaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setTextAlign(Paint.Align.CENTER);

        // 机型一行（16sp，照 BatteryCustomTextView）
        float modelTop = top + iconH + px(2f);
        captionPaint.setTextAlign(Paint.Align.LEFT);
        captionPaint.setTextSize(16f * k * density);
        canvas.drawText(modelText, left, modelTop + captionPaint.getTextSize(), captionPaint);
        captionPaint.setTextAlign(Paint.Align.CENTER);

        // 进度条 145×11dp
        float barTop = batteryTrackTop(top);
        float barH = px(11f);
        if (!canvas.isHardwareAccelerated() || !batteryGlass.hasNativeMaterial()) {
            tmpRect.set(left, barTop, left + w, barTop + barH);
            canvas.drawRoundRect(tmpRect, barH / 2f, barH / 2f, barBg);
        }
        float fill = w * (pct / 100f);
        if (fill > 0f) {
            ringFillPaint.setColor(accent);
            tmpRect.set(left, barTop, left + fill, barTop + barH);
            canvas.drawRoundRect(tmpRect, barH / 2f, barH / 2f, ringFillPaint);
        }
    }

    // ------------------------------------------------------------------ 系统玻璃

    /** 诊断只报告实际绑定结果，不再把壁纸 shader 称为系统玻璃。 */
    String diag() {
        int count = (batteryGlass.hasNativeMaterial() ? 1 : 0)
                + (weatherGlass.hasNativeMaterial() ? 1 : 0)
                + (stepsGlass.hasNativeMaterial() ? 1 : 0);
        return (nativeMaterialsReady() ? "native-path-configured" : "fallback/pending")
                + "(" + count + "/" + visibleCount() + ")"
                + (charging ? "/charging" : "/idle")
                + "@" + getWidth() + "x" + getHeight();
    }

    /** 直接用玻璃条已经读好的壁纸（动态壁纸只有这份能拿到）。 */
    void setWallpaper(android.graphics.Bitmap bmp) {
        if (nativeMaterialsReady() || bmp == null || bmp == wallpaper) {
            return;
        }
        wallpaper = bmp;
        wallpaperShader = null;
        invalidate();
    }

    /** 仅兼容回退需要旧版壁纸背景；原生材质直接采样合成器，不再读图仿玻璃。 */
    void ensureWallpaper() {
        if (nativeMaterialsReady() || wallpaperLoading) {
            return;
        }
        // 已经有壁纸也要隔一会儿查一次 id：换了壁纸 / 动态壁纸更新，
        // 玻璃才能跟着变（之前只读一次，玻璃永远是旧图）
        long nowMs = android.os.SystemClock.uptimeMillis();
        if (wallpaper != null && nowMs - lastWallCheck < 15000L) {
            return;
        }
        lastWallCheck = nowMs;
        wallpaperLoading = true;
        LockConfig.io(() -> {
            try {
                android.app.WallpaperManager wm =
                        android.app.WallpaperManager.getInstance(getContext());
                int id = Integer.MIN_VALUE;
                try {
                    id = wm.getWallpaperId(android.app.WallpaperManager.FLAG_LOCK);
                } catch (Throwable ignored) {
                }
                if (wallpaper != null && id != Integer.MIN_VALUE && id == wallId) {
                    return;   // 壁纸没变，不重读
                }
                android.graphics.Bitmap bmp = decodeWallpaper(wm,
                        android.app.WallpaperManager.FLAG_LOCK);
                if (bmp == null) {
                    bmp = decodeWallpaper(wm, android.app.WallpaperManager.FLAG_SYSTEM);
                }
                android.util.Log.i("LockBar", "widget wallpaper: " + (bmp != null));
                if (bmp != null) {
                    wallpaper = blurDown(bmp);
                    if (wallpaper != bmp) {
                        bmp.recycle();
                    }
                    wallpaperShader = null;
                    wallId = id;
                    // 异步读完必须主动重绘，否则玻璃要等下一次其它刷新才出现
                    postInvalidate();
                }
            } catch (Throwable ignored) {
                // 动态壁纸读不到：退回半透明白
            } finally {
                wallpaperLoading = false;
            }
        });
    }

    private static android.graphics.Bitmap decodeWallpaper(
            android.app.WallpaperManager wm, int flag) {
        int sample = 1;
        try (android.os.ParcelFileDescriptor pfd = wm.getWallpaperFile(flag)) {
            if (pfd == null) {
                return null;
            }
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFileDescriptor(
                    pfd.getFileDescriptor(), null, bounds);
            while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= 540) {
                sample *= 2;
            }
        } catch (Throwable t) {
            return null;
        }
        try (android.os.ParcelFileDescriptor pfd = wm.getWallpaperFile(flag)) {
            if (pfd == null) {
                return null;
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            return android.graphics.BitmapFactory.decodeFileDescriptor(
                    pfd.getFileDescriptor(), null, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 便宜的高斯近似：先缩到 1/8，再双线性放大回原尺寸。 */
    private static android.graphics.Bitmap blurDown(android.graphics.Bitmap src) {
        int w = Math.max(1, src.getWidth());
        int h = Math.max(1, src.getHeight());
        // 只做轻微模糊（降 2 倍再放回）。之前降 8 倍，
        // 环里只剩一块灰白，看起来就像“没玻璃/不实时”
        int sw = Math.max(1, w / 2);
        int sh = Math.max(1, h / 2);
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(
                src, sw, sh, true);
        android.graphics.Bitmap big = android.graphics.Bitmap.createScaledBitmap(
                small, w, h, true);
        if (small != src) {
            small.recycle();
        }
        return big;
    }
}
