package android.view;

import testsupport.RecordingView;

/** Exact required OEM signatures; boolean returns allow explicit false-result tests. */
public class View extends RecordingView {
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;
    public View(String name) { super(name); }

    // Keep hidden declarations one-per-line for separately compiled API variants.
    public boolean clearMiBackgroundBlendColor() { return nativeCall("clearMiBackgroundBlendColor"); }
    public boolean setPassWindowBlurEnabled(boolean enabled) { return nativeCall("setPassWindowBlurEnabled", enabled); }
    public boolean getPassWindowBlurEnabled() { return nativeCall("getPassWindowBlurEnabled"); }
    public boolean setMiViewBlurMode(int mode) { return nativeCall("setMiViewBlurMode", mode); }
    public boolean setMiBackgroundBlurMode(int mode) { return nativeCall("setMiBackgroundBlurMode", mode); }
    public boolean setMiBackgroundBlurRadius(int radius) { return nativeCall("setMiBackgroundBlurRadius", radius); }
    public boolean setMiGlassBlurRadius(int small, int big) { return nativeCall("setMiGlassBlurRadius", small, big); }
    public boolean setMiViewMaterialType(int type) { return nativeCall("setMiViewMaterialType", type); }
    public boolean setMiGlassSdfMaxSize(float width, float height) { return nativeCall("setMiGlassSdfMaxSize", width, height); }
    public boolean setMiGlass(float[] recipe) { return nativeCall("setMiGlass", (Object) recipe); }
    public boolean chooseBackgroundBlurContainer(View container) { return nativeCall("chooseBackgroundBlurContainer", container); }
    public boolean setMiCustomSurfaceColorType(int type) { return nativeCall("setMiCustomSurfaceColorType", type); }
    public boolean setMiGlassClip(float left, float top, float right, float bottom) { return nativeCall("setMiGlassClip", left, top, right, bottom); }
    public boolean disableMiBackgroundContainBelow(boolean disabled) { return nativeCall("disableMiBackgroundContainBelow", disabled); }
}
