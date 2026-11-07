package com.lockbar.app.xposed;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.HashMap;
import java.util.Map;

/**
 * 天气图标：把 {@link LockWeatherIconData} 里的矢量路径画出来。
 *
 * <p>为什么不用 {@code VectorDrawable}：hook 跑在 SystemUI 进程里，模块的资源表拿不到 ——
 * 要走 {@code getResourcesForApplication} 才能读，还得赌资源没被裁剪；这里干脆把路径数据
 * 编进代码，再用一个两百行的解析器画，图形与系统 {@code weather_icons_*} 逐点一致。
 *
 * <p>只支持 vector drawable 实际会用到的那几条命令（M/L/H/V/C/S/Q/T/A/Z 及其小写形式），
 * 足以覆盖这套图标；遇到不认识的命令就跳过该段，不会抛异常。
 */
final class LockWeatherIcons {

    private static final class Layer {
        Path path;
        int color;
        float alpha;
    }

    private static final Map<String, Layer[]> CACHE = new HashMap<>();

    private LockWeatherIcons() {
    }

    /** 按天气类型取图标索引；夜间版只有 0 / 1 / 3 有。 */
    private static String nameFor(int type, boolean night) {
        String base = String.valueOf(type);
        if (night) {
            String n = base + "_n";
            for (String name : LockWeatherIconData.NAMES) {
                if (n.equals(name)) {
                    return n;
                }
            }
        }
        for (String name : LockWeatherIconData.NAMES) {
            if (base.equals(name)) {
                return name;
            }
        }
        return "1";
    }

    private static Layer[] layersFor(String name) {
        Layer[] cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        int idx = -1;
        for (int i = 0; i < LockWeatherIconData.NAMES.length; i++) {
            if (LockWeatherIconData.NAMES[i].equals(name)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            idx = 0;
        }
        String raw = LockWeatherIconData.DATA[idx];
        String[] parts = raw.split("\u0001");
        String[] head = parts[0].split("\u0002", 2);
        Layer[] layers = new Layer[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String[] f = parts[i].split("\u0002", i == 0 ? 4 : 3);
            // 首段多一个 viewport 字段
            int base = i == 0 ? 1 : 0;
            Layer layer = new Layer();
            try {
                layer.path = parse(f[base]);
                layer.color = Color.parseColor(f[base + 1]);
                layer.alpha = Float.parseFloat(f[base + 2]);
            } catch (Throwable t) {
                layer.path = new Path();
                layer.color = Color.WHITE;
                layer.alpha = 1f;
            }
            layers[i] = layer;
        }
        CACHE.put(name, layers);
        return layers;
    }

    /** 图标的 viewport（宽, 高），用于把路径缩放到目标尺寸。 */
    private static float[] viewportFor(String name) {
        int idx = -1;
        for (int i = 0; i < LockWeatherIconData.NAMES.length; i++) {
            if (LockWeatherIconData.NAMES[i].equals(name)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            idx = 0;
        }
        String raw = LockWeatherIconData.DATA[idx];
        String vp = raw.split("\u0002", 2)[0];
        try {
            String[] wh = vp.split("x");
            return new float[]{Float.parseFloat(wh[0]), Float.parseFloat(wh[1])};
        } catch (Throwable t) {
            return new float[]{25f, 25f};
        }
    }

    /**
     * 画一个天气图标。
     *
     * @param cx   中心 x（px）
     * @param cy   中心 y（px）
     * @param size 图标边长（px）
     */
    static void draw(Canvas canvas, int type, boolean night,
                     float cx, float cy, float size, Paint paint) {
        drawNamed(canvas, nameFor(type, night), cx, cy, size, size, paint);
    }

    /** 按名字画一个矩形图标（宽高可以不等）。 */
    static void drawNamed(Canvas canvas, String name,
                          float cx, float cy, float w, float h, Paint paint) {
        Layer[] layers = layersFor(name);
        float[] vp = viewportFor(name);
        if (vp[0] <= 0f || vp[1] <= 0f) {
            vp = new float[]{25f, 25f};
        }
        float scaleX = w / vp[0];
        float scaleY = h / vp[1];
        canvas.save();
        canvas.translate(cx - w / 2f, cy - h / 2f);
        canvas.scale(scaleX, scaleY);
        for (Layer layer : layers) {
            if (layer == null || layer.path == null) {
                continue;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(layer.color);
            paint.setAlpha(Math.round(255f * layer.alpha));
            canvas.drawPath(layer.path, paint);
        }
        canvas.restore();
        paint.setAlpha(255);
    }

    // --------------------------------------------------------------- 路径解析

    /** 把 SVG pathData 解析成 {@link Path}。 */
    static Path parse(String d) {
        Path path = new Path();
        if (d == null || d.isEmpty()) {
            return path;
        }
        int n = d.length();
        int i = 0;
        float lastX = 0f;
        float lastY = 0f;
        float startX = 0f;
        float startY = 0f;
        char cmd = 0;
        // 上一段的三次/二次控制点，供 S / T 反射
        float lastCtrlX = 0f;
        float lastCtrlY = 0f;
        char prevCmd = 0;
        while (i < n) {
            char c = d.charAt(i);
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
            } else if (cmd == 0) {
                i++;
                continue;
            } else if (cmd == 'M') {
                cmd = 'L';
            } else if (cmd == 'm') {
                cmd = 'l';
            }
            boolean rel = Character.isLowerCase(cmd);
            char up = Character.toUpperCase(cmd);
            try {
                switch (up) {
                    case 'M': {
                        float x = num(d, i);
                        i = idx;
                        float y = num(d, i);
                        i = idx;
                        if (rel) {
                            x += lastX;
                            y += lastY;
                        }
                        path.moveTo(x, y);
                        lastX = x;
                        lastY = y;
                        startX = x;
                        startY = y;
                        break;
                    }
                    case 'L': {
                        float x = num(d, i);
                        i = idx;
                        float y = num(d, i);
                        i = idx;
                        if (rel) {
                            x += lastX;
                            y += lastY;
                        }
                        path.lineTo(x, y);
                        lastX = x;
                        lastY = y;
                        break;
                    }
                    case 'H': {
                        float x = num(d, i);
                        i = idx;
                        if (rel) {
                            x += lastX;
                        }
                        path.lineTo(x, lastY);
                        lastX = x;
                        break;
                    }
                    case 'V': {
                        float y = num(d, i);
                        i = idx;
                        if (rel) {
                            y += lastY;
                        }
                        path.lineTo(lastX, y);
                        lastY = y;
                        break;
                    }
                    case 'C': {
                        float x1 = num(d, i);
                        i = idx;
                        float y1 = num(d, i);
                        i = idx;
                        float x2 = num(d, i);
                        i = idx;
                        float y2 = num(d, i);
                        i = idx;
                        float x = num(d, i);
                        i = idx;
                        float y = num(d, i);
                        i = idx;
                        if (rel) {
                            x1 += lastX;
                            y1 += lastY;
                            x2 += lastX;
                            y2 += lastY;
                            x += lastX;
                            y += lastY;
                        }
                        path.cubicTo(x1, y1, x2, y2, x, y);
                        lastCtrlX = x2;
                        lastCtrlY = y2;
                        lastX = x;
                        lastY = y;
                        break;
                    }
                    case 'S': {
                        float x2 = num(d, i);
                        i = idx;
                        float y2 = num(d, i);
                        i = idx;
                        float x = num(d, i);
                        i = idx;
                        float y = num(d, i);
                        i = idx;
                        if (rel) {
                            x2 += lastX;
                            y2 += lastY;
                            x += lastX;
                            y += lastY;
                        }
                        float c1x = lastX;
                        float c1y = lastY;
                        if (prevCmd == 'C' || prevCmd == 'S') {
                            c1x = 2 * lastX - lastCtrlX;
                            c1y = 2 * lastY - lastCtrlY;
                        }
                        path.cubicTo(c1x, c1y, x2, y2, x, y);
                        lastCtrlX = x2;
                        lastCtrlY = y2;
                        lastX = x;
                        lastY = y;
                        break;
                    }
                    case 'Q': {
                        float x1 = num(d, i);
                        i = idx;
                        float y1 = num(d, i);
                        i = idx;
                        float x = num(d, i);
                        i = idx;
                        float y = num(d, i);
                        i = idx;
                        if (rel) {
                            x1 += lastX;
                            y1 += lastY;
                            x += lastX;
                            y += lastY;
                        }
                        path.quadTo(x1, y1, x, y);
                        lastCtrlX = x1;
                        lastCtrlY = y1;
                        lastX = x;
                        lastY = y;
                        break;
                    }
                    case 'T': {
                        float x = num(d, i);
                        i = idx;
                        float y = num(d, i);
                        i = idx;
                        if (rel) {
                            x += lastX;
                            y += lastY;
                        }
                        float x1 = lastX;
                        float y1 = lastY;
                        if (prevCmd == 'Q' || prevCmd == 'T') {
                            x1 = 2 * lastX - lastCtrlX;
                            y1 = 2 * lastY - lastCtrlY;
                        }
                        path.quadTo(x1, y1, x, y);
                        lastCtrlX = x1;
                        lastCtrlY = y1;
                        lastX = x;
                        lastY = y;
                        break;
                    }
                    case 'A': {
                        float rx = num(d, i);
                        i = idx;
                        float ry = num(d, i);
                        i = idx;
                        float rot = num(d, i);
                        i = idx;
                        int large = (int) num(d, i);
                        i = idx;
                        int sweep = (int) num(d, i);
                        i = idx;
                        float x = num(d, i);
                        i = idx;
                        float y = num(d, i);
                        i = idx;
                        if (rel) {
                            x += lastX;
                            y += lastY;
                        }
                        arcTo(path, lastX, lastY, rx, ry, rot, large != 0, sweep != 0, x, y);
                        lastX = x;
                        lastY = y;
                        break;
                    }
                    case 'Z': {
                        path.close();
                        lastX = startX;
                        lastY = startY;
                        break;
                    }
                    default:
                        // 未知命令：跳过这个字符，避免死循环
                        i++;
                        break;
                }
            } catch (Throwable t) {
                // 数据坏了就画到哪算哪
                break;
            }
            prevCmd = up;
        }
        return path;
    }

    /** 模拟 SVG A 命令：折算成若干段三次贝塞尔。 */
    private static void arcTo(Path path, float x0, float y0, float rx, float ry,
                              float rotDeg, boolean large, boolean sweep, float x, float y) {
        if (rx == 0f || ry == 0f) {
            path.lineTo(x, y);
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double phi = Math.toRadians(rotDeg % 360.0);
        double dx2 = (x0 - x) / 2.0;
        double dy2 = (y0 - y) / 2.0;
        double x1p = Math.cos(phi) * dx2 + Math.sin(phi) * dy2;
        double y1p = -Math.sin(phi) * dx2 + Math.cos(phi) * dy2;
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            rx *= (float) s;
            ry *= (float) s;
        }
        double sign = (large == sweep) ? -1 : 1;
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = sign * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * (rx * y1p / ry);
        double cyp = coef * -(ry * x1p / rx);
        double cx = Math.cos(phi) * cxp - Math.sin(phi) * cyp + (x0 + x) / 2.0;
        double cy = Math.sin(phi) * cxp + Math.cos(phi) * cyp + (y0 + y) / 2.0;
        double theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double dtheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry,
                (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && dtheta > 0) {
            dtheta -= 2 * Math.PI;
        } else if (sweep && dtheta < 0) {
            dtheta += 2 * Math.PI;
        }
        int segments = (int) Math.ceil(Math.abs(dtheta) / (Math.PI / 2));
        double delta = dtheta / Math.max(1, segments);
        double t = 8.0 / 3.0 * Math.sin(delta / 4) * Math.sin(delta / 4) / Math.sin(delta / 2);
        double theta = theta1;
        for (int i = 0; i < segments; i++) {
            double cos1 = Math.cos(theta);
            double sin1 = Math.sin(theta);
            double theta2 = theta + delta;
            double cos2 = Math.cos(theta2);
            double sin2 = Math.sin(theta2);
            double e1x = cx + rx * cos1 * Math.cos(phi) - ry * sin1 * Math.sin(phi);
            double e1y = cy + rx * cos1 * Math.sin(phi) + ry * sin1 * Math.cos(phi);
            double e2x = cx + rx * cos2 * Math.cos(phi) - ry * sin2 * Math.sin(phi);
            double e2y = cy + rx * cos2 * Math.sin(phi) + ry * sin2 * Math.cos(phi);
            double d1x = -rx * sin1 * Math.cos(phi) - ry * cos1 * Math.sin(phi);
            double d1y = -rx * sin1 * Math.sin(phi) + ry * cos1 * Math.cos(phi);
            double d2x = -rx * sin2 * Math.cos(phi) - ry * cos2 * Math.sin(phi);
            double d2y = -rx * sin2 * Math.sin(phi) + ry * cos2 * Math.cos(phi);
            path.cubicTo((float) (e1x + t * d1x), (float) (e1y + t * d1y),
                    (float) (e2x - t * d2x), (float) (e2y - t * d2y),
                    (float) e2x, (float) e2y);
            theta = theta2;
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy;
        double len = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
        double a = Math.acos(Math.max(-1, Math.min(1, dot / len)));
        return (ux * vy - uy * vx < 0) ? -a : a;
    }

    // --------------------------------------------------------------- 数字扫描

    private static int idx;

    /** 从 i 开始读一个浮点数，返回后把游标推进到数字之后。 */
    private static float num(String d, int start) {
        int i = start;
        int n = d.length();
        while (i < n && (Character.isWhitespace(d.charAt(i)) || d.charAt(i) == ',')) {
            i++;
        }
        int begin = i;
        if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) {
            i++;
        }
        boolean dot = false;
        boolean exp = false;
        while (i < n) {
            char c = d.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' && !dot) {
                dot = true;
                i++;
            } else if ((c == 'e' || c == 'E') && !exp) {
                exp = true;
                i++;
                if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) {
                    i++;
                }
            } else {
                break;
            }
        }
        idx = i;
        if (begin == i) {
            throw new IllegalArgumentException("no number at " + start);
        }
        return Float.parseFloat(d.substring(begin, i));
    }
}
