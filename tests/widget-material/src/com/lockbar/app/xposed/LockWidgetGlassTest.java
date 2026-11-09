package com.lockbar.app.xposed;

import android.graphics.Paint;
import android.view.View;
import testsupport.RecordingTarget;
import testsupport.RecordingTarget.Call;
import testsupport.RecordingTarget.FailureMode;
import testsupport.RecordingView;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Runs the real provider/consumer/Paint adapter. No canvas or GPU is simulated. */
public final class LockWidgetGlassTest {
    enum Role { PROVIDER, CONSUMER, MARKER }
    enum Gate { DETACHED, SOFTWARE, ZERO_WIDTH, ZERO_HEIGHT, NEGATIVE_WIDTH, NEGATIVE_HEIGHT }
    private interface CheckedTest { void run() throws Exception; }
    private static int passed;
    private static int failed;
    private static final List<String> RESULTS = new ArrayList<>();
    private static final List<Fixture> FIXTURES = new ArrayList<>();

    static final class Step {
        final Role role;
        final String method;
        final boolean hidden;
        final Object[] arguments;

        Step(Role role, String method, boolean hidden, Object... arguments) {
            this.role = role;
            this.method = method;
            this.hidden = hidden;
            this.arguments = arguments;
        }

        @Override public String toString() { return role + "." + method; }
    }

    static final class Fixture {
        final View surface;
        final View provider;
        final Paint marker;
        final Paint foreground;
        final View parent;
        final View window;
        final View notification;
        final LockWidgetGlass adapter;
        final List<Object> foregroundBefore;
        final List<List<Object>> visualBefore;

        Fixture(String name, int width, int height) {
            window = readyView(name + "/foreign-window", 1080, 2400);
            parent = readyView(name + "/foreign-host", 1005, 243);
            notification = readyView(name + "/foreign-notification", 900, 300);
            surface = readyView(name + "/consumer", width, height);
            provider = readyView(name + "/provider", Math.max(1, width + 40), Math.max(1, height + 60));
            marker = paint(name + "/marker");
            foreground = paint(name + "/foreground");
            surface.testHierarchy(provider, window);
            provider.testHierarchy(parent, window);
            parent.testHierarchy(window, window);
            parent.seedVisualState(0.12f, View.GONE, 7f, -155f, 0f);
            marker.seedState(Paint.Style.STROKE, 0x5D112233, 2f, 4f, false);
            foreground.seedState(Paint.Style.FILL_AND_STROKE, 0xBEABCDEF, 6f, 9f, false);
            foregroundBefore = foreground.state();
            visualBefore = List.of(surface.visualState(), provider.visualState(), parent.visualState(),
                    window.visualState(), notification.visualState());
            adapter = new LockWidgetGlass(surface, provider, marker, name);
        }

        RecordingTarget target(Role role) {
            if (role == Role.PROVIDER) return provider;
            if (role == Role.CONSUMER) return surface;
            return marker;
        }
    }

    private LockWidgetGlassTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].startsWith("probe-")) {
            resetHarness();
            switch (args[0]) {
                case "probe-init": initialization(); break;
                case "probe-clone": recipeCloning(); break;
                case "probe-detach": detachRebind(); break;
                case "probe-draw": drawDiagnostics(); break;
                case "probe-pass-idempotent": passAlreadyDesired(Role.CONSUMER); break;
                case "probe-pass-mismatch": passDeniedMismatch(Role.PROVIDER); break;
                default: throw new IllegalArgumentException("Unknown contract probe: " + args[0]);
            }
            assertProtectedUntouched();
            System.out.println("Contract probe accepted: " + args[0]);
            return;
        }
        run("exact provider/consumer/Paint initialization", LockWidgetGlassTest::initialization);
        run("same View cannot be provider and consumer", LockWidgetGlassTest::rejectAliasing);
        for (Role role : List.of(Role.PROVIDER, Role.CONSUMER)) {
            run("idempotent pass false accepted after readback " + role, () -> passAlreadyDesired(role));
            run("pass false after desired write accepted " + role, () -> passFalseApplied(role));
            run("pass false without write accepted only when already desired " + role, () -> passNoWriteAlreadyDesired(role));
            run("pass false with mismatched readback rejected " + role, () -> passDeniedMismatch(role));
        }
        run("both pass states already desired: two checked readbacks", () -> passBothStates(true));
        run("both pass states change successfully: no readback needed", () -> passBothStates(false));
        run("provider readback throws before return", () -> passGetterFailure(FailureMode.THROW_BEFORE_WRITE));
        run("provider readback throws after query", () -> passGetterFailure(FailureMode.THROW_AFTER_WRITE));
        for (Role role : List.of(Role.PROVIDER, Role.CONSUMER)) {
            for (Gate gate : Gate.values()) {
                run("initial gate " + role + "/" + gate, () -> readinessGate(role, gate, false));
                run("bound readiness loss " + role + "/" + gate, () -> readinessGate(role, gate, true));
            }
        }
        run("one-pixel positive dimensions accepted without clipping clamp", LockWidgetGlassTest::minimumSize);
        run("same-size binding is idempotent", LockWidgetGlassTest::idempotence);
        run("consumer resize only SDF+clip; provider resize does not reinstall", LockWidgetGlassTest::resize);
        run("recipe clones preserve shared baseline and edge metadata", LockWidgetGlassTest::recipeCloning);
        run("three independent pairs preserve parents and foreground Paints", LockWidgetGlassTest::ownership);
        run("consumer-first detach, double detach, fresh rebind", LockWidgetGlassTest::detachRebind);
        run("detach and draw notification before bind are no-ops", LockWidgetGlassTest::unused);
        run("configured and host draw-submitted diagnostics are distinct", LockWidgetGlassTest::drawDiagnostics);

        List<Step> init = initializationCalls(243, 243);
        for (int i = 0; i < init.size(); i++) {
            final int index = i;
            for (FailureMode mode : standardFailureModes()) {
                if (mode == FailureMode.RETURN_FALSE && (!init.get(i).hidden
                        || isPassApi(init.get(i).method))) continue;
                run("init rollback " + init.get(i) + "/" + mode, () -> initializationFailure(index, mode));
            }
        }
        for (int i = 0; i < 2; i++) {
            final int index = i;
            for (FailureMode mode : standardFailureModes()) {
                run("resize rollback step " + i + "/" + mode, () -> resizeFailure(index, mode));
            }
        }
        List<Step> clear = cleanupCalls();
        for (int i = 0; i < clear.size(); i++) {
            final int index = i;
            for (FailureMode mode : standardFailureModes()) {
                run("cleanup continues " + clear.get(i) + "/" + mode, () -> cleanupFailure(index, mode));
            }
        }
        run("diagnostics rate-limited across detach/rebind", LockWidgetGlassTest::rateLimitedDiagnostics);
        String summary = "Behavioral suite: " + passed + " passed, " + failed + " failed";
        RESULTS.add(summary);
        String report = System.getProperty("widget.tests.report");
        if (report != null) Files.write(Path.of(report), RESULTS, StandardCharsets.UTF_8);
        System.out.println(summary);
        if (failed != 0) throw new AssertionError("Native path adapter regressions: " + failed);
    }

    private static void run(String name, CheckedTest test) {
        try {
            resetHarness();
            test.run();
            assertProtectedUntouched();
            passed++;
            RESULTS.add("PASS " + name);
            if (Boolean.getBoolean("widget.tests.verbose")) System.out.println("[PASS] " + name);
        } catch (Throwable error) {
            failed++;
            RESULTS.add("FAIL " + name + ": " + error);
            System.err.println("[FAIL] " + name);
            error.printStackTrace(System.err);
        }
    }

    static void resetHarness() throws ReflectiveOperationException {
        // Test isolation only: lookup/bind/clear are executed unchanged, not mocked or replaced.
        Field api = LockWidgetGlass.class.getDeclaredField("api");
        api.setAccessible(true);
        api.set(null, null);
        Field resolved = LockWidgetGlass.class.getDeclaredField("resolved");
        resolved.setAccessible(true);
        resolved.setBoolean(null, false);
        ModuleLog.reset();
        RecordingTarget.resetRecords();
        FIXTURES.clear();
    }

    static Fixture fixture(String name, int width, int height) {
        Fixture f = new Fixture(name, width, height);
        FIXTURES.add(f);
        return f;
    }

    static View readyView(String name, int width, int height) {
        View v = new View(name);
        RecordingTarget.ALL_TARGETS.add(v);
        v.testGeometry(width, height);
        v.testAvailability(true, true);
        v.seedVisualState(0.37f, View.VISIBLE, 13.25f, -27.5f, 2.75f);
        return v;
    }

    static Paint paint(String name) {
        Paint paint = new Paint(name);
        RecordingTarget.ALL_TARGETS.add(paint);
        return paint;
    }

    private static FailureMode[] standardFailureModes() {
        return new FailureMode[]{FailureMode.THROW_BEFORE_WRITE, FailureMode.THROW_AFTER_WRITE, FailureMode.RETURN_FALSE};
    }

    private static boolean isPassApi(String method) {
        return method.equals("setPassWindowBlurEnabled") || method.equals("getPassWindowBlurEnabled");
    }

    private static void passAlreadyDesired(Role role) {
        Fixture f = fixture("pass-idempotent", 243, 243);
        View target = (View) f.target(role);
        boolean desired = role == Role.PROVIDER;
        target.passWindow = desired;
        List<Step> expected = initializationCalls(243, 243, f.provider.passWindow, !f.surface.passWindow);
        check(f.adapter.bind(), "idempotent pass state must be accepted");
        expectTrace(f, expected);
        assertPassReturn(target, false, desired);
        assertBoundState(f, 243, 243);
        equal(0, ModuleLog.WARNINGS.size(), "idempotent false is not a failure diagnostic");
    }

    private static void passFalseApplied(Role role) {
        Fixture f = fixture("pass-false-applied", 243, 243);
        View target = (View) f.target(role);
        boolean desired = role == Role.PROVIDER;
        target.passWindow = !desired;
        target.failNext("setPassWindowBlurEnabled", FailureMode.RETURN_FALSE);
        check(f.adapter.bind(), "false after applying desired pass state must be accepted");
        expectTrace(f, initializationCalls(243, 243, role == Role.PROVIDER, true));
        assertPassReturn(target, false, desired);
        assertBoundState(f, 243, 243);
        equal(0, ModuleLog.WARNINGS.size(), "matching readback has no fallback warning");
    }

    private static void passNoWriteAlreadyDesired(Role role) {
        Fixture f = fixture("pass-no-write-already-desired", 243, 243);
        View target = (View) f.target(role);
        boolean desired = role == Role.PROVIDER;
        target.passWindow = desired;
        target.failNext("setPassWindowBlurEnabled", FailureMode.RETURN_FALSE_WITHOUT_WRITE);
        check(f.adapter.bind(), "no-op false accepted only when existing pass state matches");
        expectTrace(f, initializationCalls(243, 243, role == Role.PROVIDER, true));
        assertPassReturn(target, false, desired);
        assertBoundState(f, 243, 243);
    }

    private static void passDeniedMismatch(Role role) {
        Fixture f = fixture("pass-denied", 243, 243);
        View target = (View) f.target(role);
        boolean desired = role == Role.PROVIDER;
        target.passWindow = !desired;
        target.failNext("setPassWindowBlurEnabled", FailureMode.RETURN_FALSE_WITHOUT_WRITE);
        check(!f.adapter.bind(), "false with mismatched readback must be rejected");
        check(!f.adapter.isBound(), "denied sampler setting cannot remain bound");
        List<Step> planned = initializationCalls(243, 243, role == Role.PROVIDER, true);
        int getterIndex = stepIndex(planned, role, "getPassWindowBlurEnabled");
        List<Step> expected = new ArrayList<>(planned.subList(0, getterIndex + 1));
        expected.addAll(cleanupCalls());
        expectTrace(f, expected);
        assertPassReturn(target, false, !desired);
        assertFailureDiagnostic("setPassWindowBlurEnabled", FailureMode.RETURN_FALSE, null);
        assertCleared(f, null);
        check(!f.adapter.bind(), "mismatched false obeys same-attachment retry guard");
        expectTrace(f, expected);
        f.adapter.detach();
        check(f.adapter.bind(), "fresh attach recovers when the setter accepts the change");
        expected.addAll(initializationCalls(243, 243));
        expectTrace(f, expected);
        assertBoundState(f, 243, 243);
    }

    private static void passBothStates(boolean alreadyDesired) {
        Fixture f = fixture("pass-pair", 243, 243);
        f.provider.passWindow = alreadyDesired;
        f.surface.passWindow = !alreadyDesired;
        check(f.adapter.bind(), "both pass states can reach their targets");
        expectTrace(f, initializationCalls(243, 243, alreadyDesired, alreadyDesired));
        assertPassReturn(f.provider, !alreadyDesired, alreadyDesired ? Boolean.TRUE : null);
        assertPassReturn(f.surface, !alreadyDesired, alreadyDesired ? Boolean.FALSE : null);
        assertBoundState(f, 243, 243);
    }

    private static void passGetterFailure(FailureMode mode) {
        Fixture f = fixture("pass-getter-error", 243, 243);
        f.provider.passWindow = true;
        Throwable error = f.provider.failNext("getPassWindowBlurEnabled", mode);
        check(!f.adapter.bind(), "unreadable idempotent result must not count as accepted");
        List<Step> planned = initializationCalls(243, 243, true, true);
        List<Step> expected = new ArrayList<>(planned.subList(0, 3));
        expected.addAll(cleanupCalls());
        expectTrace(f, expected);
        assertFailureDiagnostic("getPassWindowBlurEnabled", mode, error);
        assertCleared(f, null);
        f.adapter.detach();
        check(f.adapter.bind(), "readback exception can recover on fresh attach");
        expected.addAll(initializationCalls(243, 243));
        expectTrace(f, expected);
    }

    private static void assertPassReturn(View view, boolean setterResult, Boolean getterResult) {
        Call setter = null;
        List<Call> reads = new ArrayList<>();
        for (Call call : view.calls) {
            if (setter == null && call.method.equals("setPassWindowBlurEnabled")) setter = call;
            if (call.method.equals("getPassWindowBlurEnabled")) reads.add(call);
        }
        check(setter != null, "pass setter was invoked");
        equal(setterResult, setter.returned, "actual fake setter return value");
        equal(getterResult == null ? 0 : 1, reads.size(), "readback occurs only when setter returned false");
        if (getterResult != null) equal(getterResult, reads.get(0).returned, "readback returns the actual pass state");
    }

    private static int stepIndex(List<Step> steps, Role role, String method) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).role == role && steps.get(i).method.equals(method)) return i;
        throw new AssertionError("Expected trace lacks " + role + "." + method);
    }

    private static void initialization() {
        Fixture f = fixture("weather", 243, 243);
        check(!f.adapter.isBound(), "not initially bound");
        check(f.adapter.bind(), "configuration succeeds");
        expectTrace(f, initializationCalls(243, 243));
        assertBoundState(f, 243, 243);
        equal(1, countDebug("/native-path-configured"), "configured diagnostic");
        equal(0, countDebug("/hardware-path-submitted"), "setters do not prove submission");
        equal(1, ModuleLog.DEBUG.size(), "only a configuration log before draw callback");
        equal(0, ModuleLog.WARNINGS.size(), "no spurious failures");
    }

    private static void rejectAliasing() {
        Fixture f = fixture("alias", 243, 243);
        boolean rejected = false;
        try { new LockWidgetGlass(f.surface, f.surface, f.marker, "alias"); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "sampler and consumer must be distinct objects");
        check(RecordingTarget.ALL_CALLS.isEmpty(), "rejected constructor never touches native state");
    }

    private static void readinessGate(Role role, Gate gate, boolean afterBound) throws Exception {
        Fixture f = fixture("gate", 243, 243);
        List<Step> expected = new ArrayList<>();
        if (afterBound) {
            check(f.adapter.bind(), "initial configuration before losing readiness");
            expected.addAll(initializationCalls(243, 243));
        }
        View target = (View) f.target(role);
        invalidateReadiness(target, gate);
        check(!f.adapter.bind(), "unready node rejects binding");
        check(!f.adapter.isBound(), "unready pair is not bound");
        if (afterBound) expected.addAll(cleanupCalls());
        expectTrace(f, expected);
        if (afterBound) assertCleared(f, null);
        else {
            Field resolved = LockWidgetGlass.class.getDeclaredField("resolved");
            resolved.setAccessible(true);
            check(!resolved.getBoolean(null), "initial gate is before API resolution");
        }
        f.adapter.onDrawSubmitted();
        equal(0, countDebug("/hardware-path-submitted"), "unbound notification cannot claim submission");
        check(!f.adapter.bind(), "repeated unready bind remains harmless");
        expectTrace(f, expected);
        target.testAvailability(true, true);
        target.testGeometry(role == Role.CONSUMER ? 271 : 400, role == Role.CONSUMER ? 195 : 500);
        int w = f.surface.getWidth();
        int h = f.surface.getHeight();
        check(f.adapter.bind(), "readiness recovers without a manual retry reset");
        expected.addAll(initializationCalls(w, h));
        expectTrace(f, expected);
        assertBoundState(f, w, h);
        equal(0, ModuleLog.WARNINGS.size(), "readiness changes are not capability failures");
    }

    private static void invalidateReadiness(View v, Gate gate) {
        switch (gate) {
            case DETACHED: v.testAvailability(false, true); break;
            case SOFTWARE: v.testAvailability(true, false); break;
            case ZERO_WIDTH: v.testGeometry(0, 243); break;
            case ZERO_HEIGHT: v.testGeometry(243, 0); break;
            case NEGATIVE_WIDTH: v.testGeometry(-1, 243); break;
            case NEGATIVE_HEIGHT: v.testGeometry(243, -1); break;
            default: throw new AssertionError(gate);
        }
    }

    private static void minimumSize() {
        Fixture f = fixture("minimum", 1, 1);
        f.provider.testGeometry(1, 1);
        check(f.adapter.bind(), "both nodes accept strictly positive dimensions");
        expectTrace(f, initializationCalls(1, 1));
        assertBoundState(f, 1, 1);
    }

    private static void idempotence() {
        Fixture f = fixture("idempotent", 243, 243);
        check(f.adapter.bind(), "first bind");
        float[] original = recipeArgument(f);
        for (int i = 0; i < 100; i++) check(f.adapter.bind(), "repeated bind remains successful");
        expectTrace(f, initializationCalls(243, 243));
        check(original == recipeArgument(f), "same-size calls allocate no new recipe");
        equal(0, countDebug("/hardware-path-submitted"), "binding repetition is not drawing");
    }

    private static void resize() {
        Fixture f = fixture("resize", 243, 243);
        check(f.adapter.bind(), "initial bind");
        List<Step> expected = initializationCalls(243, 243);
        f.provider.testGeometry(600, 700);
        check(f.adapter.bind(), "provider-only resize keeps same consumer configuration");
        expectTrace(f, expected);
        f.surface.testGeometry(300, 243);
        check(f.adapter.bind(), "width resize");
        expected.addAll(geometryCalls(300, 243));
        f.surface.testGeometry(300, 180);
        check(f.adapter.bind(), "height resize");
        expected.addAll(geometryCalls(300, 180));
        check(f.adapter.bind(), "same resized dimensions remain idempotent");
        expectTrace(f, expected);
        assertBoundState(f, 300, 180);
    }

    private static void recipeCloning() {
        Fixture first = fixture("first", 243, 243);
        Fixture second = fixture("second", 435, 33);
        check(first.adapter.bind(), "first bind");
        float[] original = recipeArgument(first);
        equal(expectedRecipe(), original, "first submitted recipe");
        original[4] = 999f;
        original[19] = 999f;
        original[20] = 999f;
        original[41] = -100f;
        check(second.adapter.bind(), "second bind after native input mutation");
        equal(expectedRecipe(), recipeArgument(second), "shared baseline not mutated");
        check(original != recipeArgument(second), "distinct arrays across material instances");
        assertBoundState(second, 435, 33);
        first.adapter.detach();
        check(first.adapter.bind(), "fresh rebind");
        check(original != recipeArgument(first), "new array on rebind");
        check(recipeArgument(first) != recipeArgument(second), "independent rebound array");
        equal(expectedRecipe(), recipeArgument(first), "rebind retains original recipe");
        assertBoundState(first, 243, 243);
    }

    private static void ownership() {
        Fixture weather = fixture("weather", 243, 243);
        Fixture steps = fixture("steps", 243, 243);
        Fixture battery = fixture("battery", 435, 33);
        for (Fixture f : List.of(weather, steps, battery)) {
            check(f.adapter.bind(), "independent pair binds");
            check(f.surface.chosenContainer == f.provider, "consumer uses its own provider only");
        }
        steps.adapter.detach();
        assertCleared(steps, null);
        assertBoundState(weather, 243, 243);
        assertBoundState(battery, 435, 33);
    }

    private static void detachRebind() {
        Fixture f = fixture("reattach", 243, 243);
        check(f.adapter.bind(), "first bind");
        f.surface.testAvailability(false, true);
        f.provider.testAvailability(false, true);
        f.adapter.detach();
        check(!f.adapter.isBound(), "detach clears logical binding");
        List<Step> expected = initializationCalls(243, 243);
        expected.addAll(cleanupCalls());
        expectTrace(f, expected);
        assertCleared(f, null);
        f.adapter.detach();
        check(!f.adapter.bind(), "detached pair cannot bind");
        expectTrace(f, expected);
        f.surface.testAvailability(true, true);
        f.provider.testAvailability(true, true);
        f.surface.testGeometry(321, 123);
        check(f.adapter.bind(), "reattach reconfigures both nodes and same marker");
        expected.addAll(initializationCalls(321, 123));
        expectTrace(f, expected);
        assertBoundState(f, 321, 123);
    }

    private static void unused() {
        Fixture f = fixture("unused", 243, 243);
        f.adapter.onDrawSubmitted();
        f.adapter.detach();
        f.adapter.detach();
        check(RecordingTarget.ALL_CALLS.isEmpty(), "unused lifecycle calls write nothing");
        equal(0, ModuleLog.DEBUG.size(), "unused adapter does not report configuration/drawing");
        check(f.adapter.bind(), "unused lifecycle calls do not poison future binding");
        expectTrace(f, initializationCalls(243, 243));
    }

    private static void drawDiagnostics() {
        Fixture f = fixture("draw", 243, 243);
        f.adapter.onDrawSubmitted();
        equal(0, ModuleLog.DEBUG.size(), "not configured: no draw claim");
        check(f.adapter.bind(), "configuration succeeds");
        equal(1, countDebug("/native-path-configured"), "configuration recorded");
        equal(0, countDebug("/hardware-path-submitted"), "not yet notified of a draw");
        int calls = RecordingTarget.ALL_CALLS.size();
        f.adapter.onDrawSubmitted(); // Host event only; this test does not submit a GPU command.
        equal(1, countDebug("/hardware-path-submitted"), "first explicit draw notification recorded");
        equal(calls, RecordingTarget.ALL_CALLS.size(), "diagnostic callback does not reconfigure material");
        for (int i = 0; i < 10; i++) f.adapter.onDrawSubmitted();
        equal(1, countDebug("/hardware-path-submitted"), "draw logging rate-limited");
        f.adapter.detach();
        f.adapter.onDrawSubmitted();
        equal(1, countDebug("/hardware-path-submitted"), "unbound event adds no log");
        check(f.adapter.bind(), "rebind succeeds");
        f.adapter.onDrawSubmitted();
        // Current adapter intentionally rate-limits both messages for its whole lifetime.
        equal(1, countDebug("/native-path-configured"), "configuration log stays rate-limited");
        equal(1, countDebug("/hardware-path-submitted"), "submission log stays rate-limited");
    }

    private static void initializationFailure(int index, FailureMode mode) {
        Fixture f = fixture("init-failure", 243, 243);
        List<Step> init = initializationCalls(243, 243);
        Step failedStep = init.get(index);
        Throwable injected = f.target(failedStep.role).failNext(failedStep.method, mode);
        check(!f.adapter.bind(), "failed step is not accepted");
        check(!f.adapter.isBound(), "failed configuration is not bound");
        List<Step> expected = new ArrayList<>(init.subList(0, index + 1));
        expected.addAll(cleanupCalls());
        expectTrace(f, expected);
        assertCleared(f, null);
        assertFailureDiagnostic(failedStep.method, mode, injected);
        equal(0, countDebug("/native-path-configured"), "no success claim after partial initialization");
        f.adapter.onDrawSubmitted();
        equal(0, countDebug("/hardware-path-submitted"), "no draw claim after partial initialization");
        check(!f.adapter.bind(), "same attachment does not loop over failed setup");
        expectTrace(f, expected);
        f.adapter.detach();
        expectTrace(f, expected);
        check(f.adapter.bind(), "reattach clears transient failure gate");
        expected.addAll(initializationCalls(243, 243));
        expectTrace(f, expected);
        assertBoundState(f, 243, 243);
    }

    private static void resizeFailure(int index, FailureMode mode) {
        Fixture f = fixture("resize-failure", 243, 243);
        check(f.adapter.bind(), "initial bind");
        Step step = geometryCalls(300, 200).get(index);
        Throwable injected = f.surface.failNext(step.method, mode);
        f.surface.testGeometry(300, 200);
        check(!f.adapter.bind(), "failed SDF or clip update not accepted");
        List<Step> expected = initializationCalls(243, 243);
        expected.addAll(geometryCalls(300, 200).subList(0, index + 1));
        expected.addAll(cleanupCalls());
        expectTrace(f, expected);
        assertCleared(f, null);
        assertFailureDiagnostic(step.method, mode, injected);
        f.adapter.onDrawSubmitted();
        equal(0, countDebug("/hardware-path-submitted"), "no submission claim after failed resize");
        check(!f.adapter.bind(), "failed resize waits for detach");
        expectTrace(f, expected);
        f.adapter.detach();
        check(f.adapter.bind(), "rebind after failed resize");
        expected.addAll(initializationCalls(300, 200));
        expectTrace(f, expected);
        assertBoundState(f, 300, 200);
    }

    private static void cleanupFailure(int index, FailureMode mode) {
        Fixture f = fixture("cleanup-failure", 243, 243);
        check(f.adapter.bind(), "initial bind");
        Step step = cleanupCalls().get(index);
        f.target(step.role).failNext(step.method, mode);
        f.adapter.detach();
        check(!f.adapter.isBound(), "logical state resets despite cleanup exception");
        List<Step> expected = initializationCalls(243, 243);
        expected.addAll(cleanupCalls());
        expectTrace(f, expected);
        // Cannot claim a setter cleared its own field if it threw before writing.
        assertCleared(f, mode == FailureMode.THROW_BEFORE_WRITE ? step : null);
        f.adapter.detach();
        expectTrace(f, expected);
        List<Step> rebound = initializationCalls(243, 243, f.provider.passWindow, !f.surface.passWindow);
        check(f.adapter.bind(), "next attach may recover after cleanup failure");
        expected.addAll(rebound);
        expectTrace(f, expected);
        assertBoundState(f, 243, 243);
    }

    private static void rateLimitedDiagnostics() {
        Fixture f = fixture("logs", 243, 243);
        check(f.adapter.bind(), "first bind");
        for (int cycle = 0; cycle < 2; cycle++) {
            f.surface.testGeometry(300 + cycle, 200);
            f.surface.failNext("setMiGlassClip", FailureMode.THROW_AFTER_WRITE);
            check(!f.adapter.bind(), "failed resize");
            for (int i = 0; i < 5; i++) check(!f.adapter.bind(), "guarded failed resize repeat");
            f.adapter.detach();
            check(f.adapter.bind(), "fresh bind after failure");
        }
        equal(1, countDebug("/native-path-configured"), "configuration log once per adapter");
        equal(1, ModuleLog.WARNINGS.size(), "failure log once per adapter");
    }

    private static void assertFailureDiagnostic(String method, FailureMode mode, Throwable injected) {
        equal(1, ModuleLog.WARNINGS.size(), "one warning");
        Throwable error = ModuleLog.WARNINGS.get(0).error;
        if (mode == FailureMode.RETURN_FALSE) {
            check(error instanceof IllegalStateException, "false result rejected");
            equal(method + " returned false", error.getMessage(), "rejected method named");
        } else check(error == injected, "original exception preserved, reflection wrapper removed");
    }

    static List<Step> initializationCalls(int width, int height) {
        // Default fake state: provider false -> true changes; consumer false -> false is idempotent.
        return initializationCalls(width, height, false, true);
    }

    static List<Step> initializationCalls(int width, int height, boolean providerReadback, boolean consumerReadback) {
        List<Step> steps = new ArrayList<>();
        steps.add(nativeStep(Role.PROVIDER, "clearMiBackgroundBlendColor"));
        steps.add(nativeStep(Role.PROVIDER, "setPassWindowBlurEnabled", true));
        if (providerReadback) steps.add(nativeStep(Role.PROVIDER, "getPassWindowBlurEnabled"));
        steps.add(nativeStep(Role.PROVIDER, "setMiBackgroundBlurMode", 1));
        steps.add(nativeStep(Role.PROVIDER, "setMiViewBlurMode", 0));
        steps.add(nativeStep(Role.PROVIDER, "setMiBackgroundBlurRadius", 40));
        steps.add(nativeStep(Role.PROVIDER, "setMiGlassBlurRadius", 36, 72));
        steps.add(nativeStep(Role.PROVIDER, "disableMiBackgroundContainBelow", true));
        steps.add(nativeStep(Role.CONSUMER, "clearMiBackgroundBlendColor"));
        steps.add(nativeStep(Role.CONSUMER, "setPassWindowBlurEnabled", false));
        if (consumerReadback) steps.add(nativeStep(Role.CONSUMER, "getPassWindowBlurEnabled"));
        steps.add(nativeStep(Role.CONSUMER, "setMiBackgroundBlurMode", 0));
        steps.add(nativeStep(Role.CONSUMER, "setMiViewBlurMode", 3));
        steps.add(nativeStep(Role.CONSUMER, "chooseBackgroundBlurContainer", Role.PROVIDER));
        steps.add(nativeStep(Role.CONSUMER, "setMiGlassBlurRadius", 36, 72));
        steps.add(nativeStep(Role.CONSUMER, "setMiViewMaterialType", 1));
        steps.add(nativeStep(Role.CONSUMER, "setMiCustomSurfaceColorType", 16));
        steps.addAll(geometryCalls(width, height));
        steps.add(nativeStep(Role.CONSUMER, "setMiGlass", (Object) expectedRecipe()));
        steps.add(new Step(Role.MARKER, "setStyle", false, Paint.Style.FILL));
        steps.add(new Step(Role.MARKER, "setColor", false, 0xFFFFFFFF));
        steps.add(new Step(Role.MARKER, "setStrokeWidth", false, 72f));
        steps.add(new Step(Role.MARKER, "setStrokeMiter", false, 3.8f));
        steps.add(nativeStep(Role.MARKER, "setGlassEffect", true));
        return steps;
    }

    static List<Step> geometryCalls(int width, int height) {
        return List.of(nativeStep(Role.CONSUMER, "setMiGlassSdfMaxSize", (float) width, (float) height),
                nativeStep(Role.CONSUMER, "setMiGlassClip", -50f, -50f, width + 50f, height + 50f));
    }

    static List<Step> cleanupCalls() {
        return List.of(
                nativeStep(Role.MARKER, "setGlassEffect", false),
                nativeStep(Role.CONSUMER, "setMiViewMaterialType", 0),
                nativeStep(Role.CONSUMER, "setMiGlass", (Object) new float[0]),
                nativeStep(Role.CONSUMER, "setMiCustomSurfaceColorType", 0),
                nativeStep(Role.CONSUMER, "chooseBackgroundBlurContainer", (Object) null),
                nativeStep(Role.CONSUMER, "setMiGlassBlurRadius", 0, 0),
                nativeStep(Role.CONSUMER, "clearMiBackgroundBlendColor"),
                nativeStep(Role.CONSUMER, "setPassWindowBlurEnabled", false),
                nativeStep(Role.CONSUMER, "setMiBackgroundBlurMode", 0),
                nativeStep(Role.CONSUMER, "setMiViewBlurMode", 0),
                nativeStep(Role.CONSUMER, "setMiGlassClip", 0f, 0f, 0f, 0f),
                nativeStep(Role.PROVIDER, "setPassWindowBlurEnabled", false),
                nativeStep(Role.PROVIDER, "setMiBackgroundBlurMode", 0),
                nativeStep(Role.PROVIDER, "setMiViewBlurMode", 0),
                nativeStep(Role.PROVIDER, "setMiGlassBlurRadius", 0, 0),
                nativeStep(Role.PROVIDER, "setMiBackgroundBlurRadius", 0),
                nativeStep(Role.PROVIDER, "clearMiBackgroundBlendColor"),
                nativeStep(Role.PROVIDER, "disableMiBackgroundContainBelow", false));
    }

    private static Step nativeStep(Role role, String method, Object... args) { return new Step(role, method, true, args); }

    static float[] expectedRecipe() {
        return new float[]{.67f, .16f, .09f, 0f, .14f, 1.4f, -.02f, .3f, .6f, 1f,
                .03f, 1f, 1f, 1f, .1f, .2f, .3f, 1f, 1f, 72f, 3.8f, 80f, 800f,
                1.2f, 1f, -.4f, .6f, -.8f, 1.4f, .7f, .8f, 1.15f, 4f, 2f,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
    }

    static void expectTrace(Fixture f, List<Step> expected) {
        List<Call> actual = RecordingTarget.ALL_CALLS;
        equal(expected.size(), actual.size(), "ordered trace length: " + actual);
        for (int i = 0; i < expected.size(); i++) {
            Step step = expected.get(i);
            Call call = actual.get(i);
            check(call.target == f.target(step.role), "wrong owner at " + i + ": " + call);
            equal(step.method, call.method, "method at " + i);
            equal(step.hidden, call.hiddenApi, "native vs ordinary Paint call at " + i);
            Object[] args = step.arguments.clone();
            for (int j = 0; j < args.length; j++) if (args[j] instanceof Role) args[j] = f.target((Role) args[j]);
            check(Arrays.deepEquals(args, call.arguments), "arguments at " + i + ": expected "
                    + Arrays.deepToString(args) + ", actual " + Arrays.deepToString(call.arguments));
        }
    }

    private static float[] recipeArgument(Fixture f) {
        for (int i = f.surface.calls.size() - 1; i >= 0; i--) {
            Call call = f.surface.calls.get(i);
            if (call.method.equals("setMiGlass") && ((float[]) call.references[0]).length > 0)
                return (float[]) call.references[0];
        }
        throw new AssertionError("No recipe submitted");
    }

    static void assertBoundState(Fixture f, int width, int height) {
        check(f.adapter.isBound(), "adapter bound");
        check(f.provider.passWindow, "provider passes window");
        equal(1, f.provider.backgroundBlurMode, "provider background mode");
        equal(0, f.provider.viewBlurMode, "provider does not render a blurred tile");
        equal(40, f.provider.backgroundRadius, "provider radius");
        equal(36, f.provider.glassSmall, "provider small radius");
        equal(72, f.provider.glassBig, "provider large radius");
        check(f.provider.containBelowDisabled, "owned provider containment configured");
        check(!f.provider.blendPresent && !f.surface.blendPresent, "no old blend layers");
        check(!f.surface.passWindow, "consumer explicitly chooses provider");
        check(f.surface.chosenContainer == f.provider, "provider identity");
        equal(0, f.surface.backgroundBlurMode, "consumer has no generic background blur");
        equal(3, f.surface.viewBlurMode, "consumer path blur mode");
        equal(1, f.surface.materialType, "consumer material type");
        equal(16, f.surface.surfaceColorType, "consumer custom surface color type");
        equal(36, f.surface.glassSmall, "consumer small radius");
        equal(72, f.surface.glassBig, "consumer large radius");
        equal((float) width, f.surface.sdfWidth, "consumer SDF width");
        equal((float) height, f.surface.sdfHeight, "consumer SDF height");
        equal(new float[]{-50f, -50f, width + 50f, height + 50f}, f.surface.glassClip, "padded native clip");
        equal(42, f.surface.glassRecipe.length, "exact recipe length");
        equal(expectedRecipe(), f.surface.glassRecipe, "all recipe slots");
        check(f.marker.glassEffect, "the actual supplied marker has its native flag");
        equal(Paint.Style.FILL, f.marker.getStyle(), "filled marker path");
        equal(0xFFFFFFFF, f.marker.getColor(), "opaque white marker");
        equal(255, f.marker.getAlpha(), "opaque marker alpha");
        equal(expectedRecipe()[19], f.marker.getStrokeWidth(), "edge width slot 19");
        equal(expectedRecipe()[20], f.marker.getStrokeMiter(), "edge miter slot 20");
    }

    static void assertCleared(Fixture f, Step refused) {
        if (!refused(refused, Role.MARKER, "setGlassEffect")) check(!f.marker.glassEffect, "marker flag cleared");
        for (Role role : List.of(Role.CONSUMER, Role.PROVIDER)) {
            View v = (View) f.target(role);
            if (!refused(refused, role, "setPassWindowBlurEnabled")) check(!v.passWindow, role + " pass-window off");
            if (!refused(refused, role, "setMiBackgroundBlurMode")) equal(0, v.backgroundBlurMode, role + " background mode off");
            if (!refused(refused, role, "setMiViewBlurMode")) equal(0, v.viewBlurMode, role + " view mode off");
            if (!refused(refused, role, "setMiGlassBlurRadius")) {
                equal(0, v.glassSmall, role + " small radius off");
                equal(0, v.glassBig, role + " large radius off");
            }
            if (!refused(refused, role, "clearMiBackgroundBlendColor")) check(!v.blendPresent, role + " blend cleared");
        }
        if (!refused(refused, Role.CONSUMER, "setMiViewMaterialType")) equal(0, f.surface.materialType, "material cleared");
        if (!refused(refused, Role.CONSUMER, "setMiGlass")) equal(0, f.surface.glassRecipe.length, "recipe cleared");
        if (!refused(refused, Role.CONSUMER, "setMiCustomSurfaceColorType")) equal(0, f.surface.surfaceColorType, "surface type cleared");
        if (!refused(refused, Role.CONSUMER, "chooseBackgroundBlurContainer")) equal(null, f.surface.chosenContainer, "provider reference cleared");
        if (!refused(refused, Role.CONSUMER, "setMiGlassClip")) equal(new float[4], f.surface.glassClip, "clip cleared");
        if (!refused(refused, Role.PROVIDER, "setMiBackgroundBlurRadius")) equal(0, f.provider.backgroundRadius, "provider radius cleared");
        if (!refused(refused, Role.PROVIDER, "disableMiBackgroundContainBelow")) check(!f.provider.containBelowDisabled, "provider containment reset");
    }

    private static boolean refused(Step step, Role role, String method) {
        return step != null && step.role == role && step.method.equals(method);
    }

    static int countDebug(String fragment) {
        int count = 0;
        for (ModuleLog.Entry entry : ModuleLog.DEBUG) if (entry.message.contains(fragment)) count++;
        return count;
    }

    static void assertProtectedUntouched() {
        Set<RecordingTarget> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Fixture f : FIXTURES) {
            owned.add(f.surface);
            owned.add(f.provider);
            owned.add(f.marker);
            for (RecordingTarget foreign : List.of(f.parent, f.window, f.notification, f.foreground))
                check(foreign.calls.isEmpty(), "foreign object mutated: " + foreign);
            equal(f.foregroundBefore, f.foreground.state(), "foreground Paint state preserved");
            equal(f.visualBefore, List.of(f.surface.visualState(), f.provider.visualState(), f.parent.visualState(),
                    f.window.visualState(), f.notification.visualState()), "all outer/owned visual properties preserved");
        }
        for (Call call : RecordingTarget.ALL_CALLS) check(owned.contains(call.target), "unowned call: " + call);
        for (RecordingTarget target : RecordingTarget.ALL_TARGETS) {
            if (target instanceof RecordingView)
                check(((RecordingView) target).visualWrites.isEmpty(), "visual/animation setter called on " + target);
        }
    }

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void equal(Object expected, Object actual, String message) {
        if (expected instanceof float[] && actual instanceof float[])
            check(Arrays.equals((float[]) expected, (float[]) actual), message + ": float arrays differ");
        else check(java.util.Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }
}
