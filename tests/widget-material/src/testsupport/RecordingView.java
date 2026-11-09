package testsupport;

import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Geometry/availability and explicit native property writes, not compositor behavior. */
public class RecordingView extends RecordingTarget {
    public final List<String> visualWrites = new ArrayList<>();
    private boolean attached;
    private boolean hardware;
    private int width;
    private int height;
    private View parent;
    private View root;
    private float alpha = 1f;
    private int visibility;
    private float translationX;
    private float translationY;
    private float translationZ;

    public boolean passWindow;
    public int viewBlurMode;
    public int backgroundBlurMode;
    public int backgroundRadius;
    public boolean blendPresent;
    public int glassSmall;
    public int glassBig;
    public int materialType;
    public int surfaceColorType;
    public boolean containBelowDisabled;
    public View chosenContainer;
    public float sdfWidth;
    public float sdfHeight;
    public float[] glassClip = new float[4];
    public float[] glassRecipe = new float[0];

    protected RecordingView(String name) { super(name); }

    public void testGeometry(int width, int height) { this.width = width; this.height = height; }
    public void testAvailability(boolean attached, boolean hardware) { this.attached = attached; this.hardware = hardware; }
    public void testHierarchy(View parent, View root) { this.parent = parent; this.root = root; }

    /** Fixture setup is separate from production setters so a write-back is detectable. */
    public void seedVisualState(float alpha, int visibility, float x, float y, float z) {
        this.alpha = alpha;
        this.visibility = visibility;
        this.translationX = x;
        this.translationY = y;
        this.translationZ = z;
    }

    public List<Object> visualState() { return List.of(alpha, visibility, translationX, translationY, translationZ); }
    public boolean isAttachedToWindow() { return attached; }
    public boolean isHardwareAccelerated() { return hardware; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public View getParent() { return parent; }
    public View getRootView() { return root == null ? (View) this : root; }
    public float getAlpha() { return alpha; }
    public int getVisibility() { return visibility; }
    public float getTranslationX() { return translationX; }
    public float getTranslationY() { return translationY; }
    public float getTranslationZ() { return translationZ; }
    public void setAlpha(float value) { visualWrites.add("setAlpha"); alpha = value; }
    public void setVisibility(int value) { visualWrites.add("setVisibility"); visibility = value; }
    public void setTranslationX(float value) { visualWrites.add("setTranslationX"); translationX = value; }
    public void setTranslationY(float value) { visualWrites.add("setTranslationY"); translationY = value; }
    public void setTranslationZ(float value) { visualWrites.add("setTranslationZ"); translationZ = value; }
    public void setScaleX(float value) { visualWrites.add("setScaleX"); }
    public void setScaleY(float value) { visualWrites.add("setScaleY"); }
    public void setX(float value) { visualWrites.add("setX"); }
    public void setY(float value) { visualWrites.add("setY"); }
    public void setElevation(float value) { visualWrites.add("setElevation"); }
    public void requestLayout() { visualWrites.add("requestLayout"); }
    public Animation animate() { visualWrites.add("animate"); return new Animation(); }

    public final class Animation {
        public Animation alpha(float value) { visualWrites.add("animate.alpha"); return this; }
        public Animation setDuration(long millis) { visualWrites.add("animate.setDuration"); return this; }
        public void cancel() { visualWrites.add("animate.cancel"); }
        public void start() { visualWrites.add("animate.start"); }
    }

    @Override protected boolean resultBeforeWrite(String method, Object[] args) {
        if ("setPassWindowBlurEnabled".equals(method)) return passWindow != (Boolean) args[0];
        if ("getPassWindowBlurEnabled".equals(method)) return passWindow;
        return true;
    }

    @Override protected void applyState(String method, Object[] args) {
        switch (method) {
            case "clearMiBackgroundBlendColor": blendPresent = false; break;
            case "setPassWindowBlurEnabled": passWindow = (Boolean) args[0]; break;
            case "getPassWindowBlurEnabled": break; // Query only: deliberately no property mutation.
            case "setMiViewBlurMode": viewBlurMode = (Integer) args[0]; break;
            case "setMiBackgroundBlurMode": backgroundBlurMode = (Integer) args[0]; break;
            case "setMiBackgroundBlurRadius": backgroundRadius = (Integer) args[0]; break;
            case "setMiGlassBlurRadius": glassSmall = (Integer) args[0]; glassBig = (Integer) args[1]; break;
            case "setMiViewMaterialType": materialType = (Integer) args[0]; break;
            case "setMiGlassSdfMaxSize": sdfWidth = (Float) args[0]; sdfHeight = (Float) args[1]; break;
            case "setMiGlass": glassRecipe = ((float[]) args[0]).clone(); break;
            case "chooseBackgroundBlurContainer": chosenContainer = (View) args[0]; break;
            case "setMiCustomSurfaceColorType": surfaceColorType = (Integer) args[0]; break;
            case "disableMiBackgroundContainBelow": containBelowDisabled = (Boolean) args[0]; break;
            case "setMiGlassClip":
                glassClip = new float[]{(Float) args[0], (Float) args[1], (Float) args[2], (Float) args[3]};
                break;
            default: throw new AssertionError("Unexpected View method: " + method);
        }
    }
}
