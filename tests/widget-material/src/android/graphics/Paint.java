package android.graphics;

import testsupport.RecordingPaint;

/** OEM flag declared here, ordinary Paint setters inherited from the recording fake. */
public class Paint extends RecordingPaint {
    public static final int ANTI_ALIAS_FLAG = 1;
    public Paint() { super("anonymous-paint"); }
    public Paint(int flags) { super("paint-flags-" + flags); }
    public Paint(String name) { super(name); }

    // One-line declaration permits real separately compiled void/missing-method ROM variants.
    public boolean setGlassEffect(boolean enabled) { return nativeCall("setGlassEffect", enabled); }
}
