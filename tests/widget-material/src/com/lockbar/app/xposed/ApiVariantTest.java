package com.lockbar.app.xposed;

import android.graphics.Paint;
import android.view.View;
import testsupport.RecordingTarget;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Executes real adapter reflection in separately compiled View/Paint API environments. */
public final class ApiVariantTest {
    private ApiVariantTest() { }

    public static void main(String[] args) throws Exception {
        LockWidgetGlassTest.resetHarness();
        if (args.length == 3 && "returns".equals(args[0])) {
            returningApi(args[1], args[2]);
            System.out.println("[PASS] API returns: View=" + args[1] + ", Paint=" + args[2]);
        } else if (args.length == 3 && "missing".equals(args[0])) {
            missingApi(args[1], args[2]);
            System.out.println("[PASS] Missing API: " + args[1] + "." + args[2]);
        } else throw new IllegalArgumentException("Expected returns <View-type> <Paint-type> | missing <owner> <method>");
        LockWidgetGlassTest.assertProtectedUntouched();
    }

    private static void returningApi(String viewReturn, String paintReturn) {
        verifyDeclarations(View.class, 14, "void".equals(viewReturn) ? Void.TYPE : Boolean.TYPE);
        verifyDeclarations(Paint.class, 1, "void".equals(paintReturn) ? Void.TYPE : Boolean.TYPE);
        LockWidgetGlassTest.Fixture f = LockWidgetGlassTest.fixture("return-variant", 435, 33);
        LockWidgetGlassTest.check(f.adapter.bind(), "supported reflection return values accepted");
        LockWidgetGlassTest.expectTrace(f, LockWidgetGlassTest.initializationCalls(435, 33));
        LockWidgetGlassTest.assertBoundState(f, 435, 33);
        LockWidgetGlassTest.equal(0, LockWidgetGlassTest.countDebug("/hardware-path-submitted"), "configuration is not submission");
        f.adapter.onDrawSubmitted();
        LockWidgetGlassTest.equal(1, LockWidgetGlassTest.countDebug("/hardware-path-submitted"), "explicit host notification logged");
        f.adapter.detach();
        List<LockWidgetGlassTest.Step> expected = LockWidgetGlassTest.initializationCalls(435, 33);
        expected.addAll(LockWidgetGlassTest.cleanupCalls());
        LockWidgetGlassTest.expectTrace(f, expected);
        LockWidgetGlassTest.assertCleared(f, null);
        LockWidgetGlassTest.check(f.adapter.bind(), "return-compatible rebind");
        expected.addAll(LockWidgetGlassTest.initializationCalls(435, 33));
        LockWidgetGlassTest.expectTrace(f, expected);
        LockWidgetGlassTest.equal(0, ModuleLog.WARNINGS.size(), "void does not become false");
    }

    private static void verifyDeclarations(Class<?> owner, int count, Class<?> returnType) {
        Method[] methods = owner.getDeclaredMethods();
        LockWidgetGlassTest.equal(count, methods.length, "actual declared API count for " + owner.getName());
        for (Method method : methods) {
            boolean passStateApi = owner == View.class && (method.getName().equals("setPassWindowBlurEnabled")
                    || method.getName().equals("getPassWindowBlurEnabled"));
            Class<?> expectedReturn = passStateApi ? Boolean.TYPE : returnType;
            LockWidgetGlassTest.check(method.getReturnType() == expectedReturn, "actual native return signature: " + method);
        }
    }

    private static void missingApi(String ownerName, String missing) {
        boolean paintMissing = "Paint".equals(ownerName);
        Class<?> owner = paintMissing ? Paint.class : View.class;
        List<String> names = new ArrayList<>();
        for (Method method : owner.getDeclaredMethods()) names.add(method.getName());
        LockWidgetGlassTest.check(!names.contains(missing), "method truly absent from compiled stub");
        LockWidgetGlassTest.equal(paintMissing ? 0 : 13, names.size(), "only the selected declaration omitted");
        verifyDeclarations(paintMissing ? View.class : Paint.class, paintMissing ? 14 : 1, Boolean.TYPE);

        LockWidgetGlassTest.Fixture f = LockWidgetGlassTest.fixture("missing-api", 0, 243);
        List<Object> initialMarker = f.marker.state();
        LockWidgetGlassTest.check(!f.adapter.bind(), "zero-size gate happens before lookup");
        LockWidgetGlassTest.equal(0, ModuleLog.WARNINGS.size(), "no lookup warning before readiness");
        f.surface.testGeometry(243, 243);
        LockWidgetGlassTest.check(!f.adapter.bind(), "missing View or Paint method rejects entire configuration");
        LockWidgetGlassTest.check(!f.adapter.isBound(), "missing API never reported bound");
        LockWidgetGlassTest.check(RecordingTarget.ALL_CALLS.isEmpty(), "complete lookup precedes all View/Paint writes");
        LockWidgetGlassTest.equal(initialMarker, f.marker.state(), "even ordinary marker metadata remains untouched");
        LockWidgetGlassTest.equal(1, ModuleLog.WARNINGS.size(), "one missing capability warning");
        Throwable error = ModuleLog.WARNINGS.get(0).error;
        LockWidgetGlassTest.check(error instanceof NoSuchMethodException, "underlying lookup failure retained: " + error);
        LockWidgetGlassTest.check(error.getMessage().contains(missing), "missing method named in diagnostic");
        for (int i = 0; i < 5; i++) LockWidgetGlassTest.check(!f.adapter.bind(), "same-attachment absence guard");
        f.adapter.onDrawSubmitted();
        f.adapter.detach();
        LockWidgetGlassTest.check(!f.adapter.bind(), "reattach cannot invent absent APIs");
        LockWidgetGlassTest.Fixture other = LockWidgetGlassTest.fixture("other-missing", 300, 200);
        LockWidgetGlassTest.check(!other.adapter.bind(), "absence cached process-wide");
        other.adapter.detach();
        other.adapter.onDrawSubmitted();
        LockWidgetGlassTest.check(RecordingTarget.ALL_CALLS.isEmpty(), "no partial configuration or unnecessary cleanup");
        LockWidgetGlassTest.equal(1, ModuleLog.WARNINGS.size(), "missing API warning once per process");
        LockWidgetGlassTest.equal(0, ModuleLog.DEBUG.size(), "no configured or submitted claims");
    }
}
