package testsupport;

import java.util.List;

/** Records ordinary marker metadata and the separately reflected OEM glass flag. */
public class RecordingPaint extends RecordingTarget {
    public enum Style { FILL, STROKE, FILL_AND_STROKE }

    private Style style = Style.STROKE;
    private int color = 0x5D112233;
    private float strokeWidth = 2f;
    private float strokeMiter = 4f;
    public boolean glassEffect;

    protected RecordingPaint(String name) { super(name); }

    public void seedState(Style style, int color, float width, float miter, boolean glass) {
        this.style = style;
        this.color = color;
        this.strokeWidth = width;
        this.strokeMiter = miter;
        this.glassEffect = glass;
    }

    public void setStyle(Style value) { ordinaryCall("setStyle", value); }
    public void setColor(int value) { ordinaryCall("setColor", value); }
    public void setStrokeWidth(float value) { ordinaryCall("setStrokeWidth", value); }
    public void setStrokeMiter(float value) { ordinaryCall("setStrokeMiter", value); }
    public void setAlpha(int value) { ordinaryCall("setAlpha", value); }
    public Style getStyle() { return style; }
    public int getColor() { return color; }
    public int getAlpha() { return color >>> 24; }
    public float getStrokeWidth() { return strokeWidth; }
    public float getStrokeMiter() { return strokeMiter; }
    public List<Object> state() { return List.of(style, color, strokeWidth, strokeMiter, glassEffect); }

    @Override protected void applyState(String method, Object[] args) {
        switch (method) {
            case "setStyle": style = (Style) args[0]; break;
            case "setColor": color = (Integer) args[0]; break;
            case "setStrokeWidth": strokeWidth = (Float) args[0]; break;
            case "setStrokeMiter": strokeMiter = (Float) args[0]; break;
            case "setAlpha": color = ((Integer) args[0] << 24) | (color & 0x00FFFFFF); break;
            case "setGlassEffect": glassEffect = (Boolean) args[0]; break;
            default: throw new AssertionError("Unexpected Paint method: " + method);
        }
    }
}
