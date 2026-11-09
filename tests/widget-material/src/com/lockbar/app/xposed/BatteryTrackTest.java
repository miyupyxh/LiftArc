package com.lockbar.app.xposed;

import android.graphics.Paint;
import android.view.View;
import testsupport.RecordingTarget;
import testsupport.RecordingTarget.Call;
import testsupport.RecordingTarget.FailureMode;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.lockbar.app.xposed.LockWidgetGlassTest.*;

/** Battery-only sampling regressions. The existing ring suite is deliberately unchanged. */
public final class BatteryTrackTest {
    private interface CheckedTest { void run() throws Exception; }
    private static final List<String> RESULTS = new ArrayList<>();
    private static int passed;
    private static int failed;

    private BatteryTrackTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            resetHarness();
            switch (args[0]) {
                case "probe-profile": profile(33f, 34, 8, 17); break;
                case "probe-optics":
                    Fixture f = fixture("battery-optics", 435, 34);
                    check(f.adapter.bindBatteryTrack(33f), "battery binds");
                    assertOptics(f);
                    break;
                case "probe-thickness": thicknessChange(33.25f, 8, 17); break;
                default: throw new IllegalArgumentException("Unknown battery probe: " + args[0]);
            }
            assertProtectedUntouched();
            System.out.println("Battery contract probe accepted: " + args[0]);
            return;
        }
        float[] invalid = {0f, -0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float value : invalid) {
            run("invalid thickness before bind " + value, () -> invalidThickness(value, false));
            run("invalid thickness after bind " + value, () -> invalidThickness(value, true));
        }
        // Expected radii are explicit fixtures, not a duplicate of the production formula.
        run("subpixel thickness clamps to 1/1", () -> profile(0.01f, 1, 1, 1));
        run("one-pixel thickness clamps to 1/1", () -> profile(1f, 1, 1, 1));
        run("three-pixel thickness rounds to 1/2", () -> profile(3f, 3, 1, 2));
        run("six-pixel thickness rounds to 2/3", () -> profile(6f, 6, 2, 3));
        run("33px path in 34px View is 8/17, not 9/17", () -> profile(33f, 34, 8, 17));
        run("34px path is 9/17", () -> profile(34f, 34, 9, 17));
        run("72px path is 18/36", () -> profile(72f, 72, 18, 36));
        run("140px path is 35/70", () -> profile(140f, 140, 35, 70));
        run("142px path is 36/71", () -> profile(142f, 142, 36, 71));
        run("144px path reaches 36/72", () -> profile(144f, 144, 36, 72));
        run("large path caps at 36/72", () -> profile(500f, 500, 36, 72));
        run("largest finite thickness does not overflow radii", () -> profile(Float.MAX_VALUE, 2048, 36, 72));
        run("width-only resize writes geometry, not sampler", BatteryTrackTest::widthOnly);
        run("same bounds and changed rounded radii update sampler", () -> thicknessChange(34f, 9, 17));
        run("same bounds and same rounded radii still track actual thickness", () -> thicknessChange(33.25f, 8, 17));
        run("battery and ring remain independent", BatteryTrackTest::independentRing);
        run("battery-ring-battery transition changes sampling only", BatteryTrackTest::profileTransitions);
        run("detach clears profile cache; reattach reinstalls", BatteryTrackTest::reattach);
        for (Role role : List.of(Role.PROVIDER, Role.CONSUMER)) {
            run("battery hardware gate " + role, () -> hardwareGate(role, false));
            run("battery hardware loss clears bound state " + role, () -> hardwareGate(role, true));
        }
        for (int index = 0; index < 5; index++) {
            final int point = index;
            for (FailureMode mode : List.of(FailureMode.THROW_BEFORE_WRITE, FailureMode.THROW_AFTER_WRITE, FailureMode.RETURN_FALSE)) {
                run("battery profile-update rollback " + index + "/" + mode, () -> updateFailure(point, mode));
            }
        }
        String summary = "Battery suite: " + passed + " passed, " + failed + " failed";
        RESULTS.add(summary);
        String report = System.getProperty("widget.tests.report");
        if (report != null) Files.write(Path.of(report), RESULTS, StandardCharsets.UTF_8);
        System.out.println(summary);
        if (failed != 0) throw new AssertionError("Battery profile regressions: " + failed);
    }

    private static void run(String name, CheckedTest test) {
        try {
            resetHarness();
            test.run();
            assertProtectedUntouched();
            RESULTS.add("PASS " + name);
            passed++;
        } catch (Throwable error) {
            RESULTS.add("FAIL " + name + ": " + error);
            failed++;
            System.err.println("[FAIL] " + name);
            error.printStackTrace(System.err);
        }
    }

    private static void invalidThickness(float thickness, boolean previouslyBound) throws Exception {
        Fixture f = fixture("invalid-battery", 435, 34);
        List<Step> expected = new ArrayList<>();
        if (previouslyBound) {
            check(f.adapter.bindBatteryTrack(33f), "initial battery binds");
            expected.addAll(batteryInit(435, 34, 8, 17));
        }
        check(!f.adapter.bindBatteryTrack(thickness), "invalid thickness must be rejected");
        check(!f.adapter.isBound(), "invalid thickness leaves no bound material");
        if (previouslyBound) {
            expected.addAll(cleanupCalls());
            assertCleared(f, null);
        } else {
            Field resolved = LockWidgetGlass.class.getDeclaredField("resolved");
            resolved.setAccessible(true);
            check(!resolved.getBoolean(null), "invalid thickness gate precedes native lookup");
        }
        expectTrace(f, expected);
        check(!f.adapter.bindBatteryTrack(thickness), "repeated invalid thickness is harmless");
        expectTrace(f, expected);
        check(f.adapter.bindBatteryTrack(33f), "valid thickness recovers without poisoning attachment");
        expected.addAll(batteryInit(435, 34, 8, 17));
        expectTrace(f, expected);
        assertBattery(f, 435, 34, 8, 17);
    }

    private static void profile(float thickness, int viewHeight, int small, int big) throws Exception {
        Fixture f = fixture("battery-profile", 435, viewHeight);
        check(f.adapter.bindBatteryTrack(thickness), "valid battery profile binds");
        // Check optics before trace: controls must explicitly fail if slot19 or the marker narrows.
        assertOptics(f);
        assertBattery(f, 435, viewHeight, small, big);
        expectTrace(f, batteryInit(435, viewHeight, small, big));
        for (int i = 0; i < 10; i++) check(f.adapter.bindBatteryTrack(thickness), "same thickness/bounds is idempotent");
        expectTrace(f, batteryInit(435, viewHeight, small, big));
    }

    private static void widthOnly() throws Exception {
        Fixture f = fixture("battery-width", 435, 34);
        check(f.adapter.bindBatteryTrack(33f), "initial battery binds");
        float[] submitted = submittedRecipe(f);
        f.surface.testGeometry(600, 34);
        check(f.adapter.bindBatteryTrack(33f), "width-only resize accepted");
        List<Step> expected = batteryInit(435, 34, 8, 17);
        expected.addAll(geometryCalls(600, 34));
        expectTrace(f, expected);
        f.provider.testGeometry(700, 90);
        check(f.adapter.bindBatteryTrack(33f), "provider dimensions alone do not reapply profile");
        expectTrace(f, expected);
        check(submitted == submittedRecipe(f), "width-only update must not submit a recipe");
        assertBattery(f, 600, 34, 8, 17);
    }

    private static void thicknessChange(float next, int small, int big) throws Exception {
        Fixture f = fixture("battery-thickness", 435, 34);
        check(f.adapter.bindBatteryTrack(33f), "initial thickness");
        float[] submitted = submittedRecipe(f);
        int markerCalls = f.marker.calls.size();
        check(f.adapter.bindBatteryTrack(next), "changed path thickness accepted");
        List<Step> expected = batteryInit(435, 34, 8, 17);
        expected.addAll(profileUpdate(435, 34, 0, small, big));
        expectTrace(f, expected);
        equal(markerCalls, f.marker.calls.size(), "thickness updates do not touch marker metadata/flag");
        check(submitted == submittedRecipe(f), "thickness update must not resubmit optical recipe");
        assertBattery(f, 435, 34, small, big);
        check(f.adapter.bindBatteryTrack(next), "same updated thickness is idempotent");
        expectTrace(f, expected);
    }

    private static void independentRing() throws Exception {
        Fixture ring = fixture("protected-ring", 243, 243);
        Fixture battery = fixture("separate-battery", 435, 34);
        check(ring.adapter.bind(), "ring retains old binding entry point");
        assertBoundState(ring, 243, 243);
        int ringCalls = ownCallCount(ring);
        check(battery.adapter.bindBatteryTrack(33f), "battery initial profile");
        check(battery.adapter.bindBatteryTrack(34f), "battery updates independently");
        check(ring.adapter.bind(), "ring remains bound without re-registration");
        equal(ringCalls, ownCallCount(ring), "battery never changes ring's View/Paint calls");
        assertBoundState(ring, 243, 243);
        assertOptics(ring);
        assertBattery(battery, 435, 34, 9, 17);
    }

    private static void profileTransitions() throws Exception {
        Fixture f = fixture("battery-ring-transition", 435, 34);
        check(f.adapter.bindBatteryTrack(33f), "battery begins");
        float[] submitted = submittedRecipe(f);
        List<Step> expected = batteryInit(435, 34, 8, 17);
        check(f.adapter.bind(), "battery-to-ring transition");
        expected.addAll(profileUpdate(435, 34, 40, 36, 72));
        expectTrace(f, expected);
        assertBoundState(f, 435, 34);
        check(f.adapter.bind(), "ring repeat idempotent");
        f.surface.testGeometry(450, 34);
        check(f.adapter.bind(), "ring resize remains geometry only");
        expected.addAll(geometryCalls(450, 34));
        expectTrace(f, expected);
        check(f.adapter.bindBatteryTrack(33f), "ring-to-battery transition");
        expected.addAll(profileUpdate(450, 34, 0, 8, 17));
        expectTrace(f, expected);
        check(submitted == submittedRecipe(f), "transitions preserve one optical recipe instance");
        assertBattery(f, 450, 34, 8, 17);
    }

    private static void reattach() throws Exception {
        Fixture f = fixture("battery-reattach", 435, 34);
        check(f.adapter.bindBatteryTrack(33f), "initial binding");
        float[] first = submittedRecipe(f);
        f.surface.testAvailability(false, true);
        f.provider.testAvailability(false, true);
        f.adapter.detach();
        List<Step> expected = batteryInit(435, 34, 8, 17);
        expected.addAll(cleanupCalls());
        assertCleared(f, null);
        f.adapter.detach();
        check(!f.adapter.bindBatteryTrack(33f), "detached battery cannot bind");
        expectTrace(f, expected);
        f.surface.testAvailability(true, true);
        f.provider.testAvailability(true, true);
        check(f.adapter.bindBatteryTrack(33f), "same thickness reattaches with full initialization");
        expected.addAll(batteryInit(435, 34, 8, 17));
        expectTrace(f, expected);
        check(first != submittedRecipe(f), "new attachment gets a new unchanged recipe clone");
        assertBattery(f, 435, 34, 8, 17);
    }

    private static void hardwareGate(Role role, boolean previouslyBound) throws Exception {
        Fixture f = fixture("battery-hardware", 435, 34);
        List<Step> expected = new ArrayList<>();
        if (previouslyBound) {
            check(f.adapter.bindBatteryTrack(33f), "initial hardware binding");
            expected.addAll(batteryInit(435, 34, 8, 17));
        }
        ((View) f.target(role)).testAvailability(true, false);
        check(!f.adapter.bindBatteryTrack(33f), "both nodes require hardware acceleration");
        if (previouslyBound) expected.addAll(cleanupCalls());
        expectTrace(f, expected);
        check(!f.adapter.isBound(), "hardware loss is unbound");
        ((View) f.target(role)).testAvailability(true, true);
        check(f.adapter.bindBatteryTrack(33f), "hardware readiness recovers");
        expected.addAll(batteryInit(435, 34, 8, 17));
        expectTrace(f, expected);
        assertBattery(f, 435, 34, 8, 17);
    }

    private static void updateFailure(int index, FailureMode mode) throws Exception {
        Fixture f = fixture("battery-update-failure", 435, 34);
        check(f.adapter.bindBatteryTrack(33f), "initial battery profile");
        List<Step> update = profileUpdate(435, 34, 0, 9, 17);
        Step step = update.get(index);
        Throwable injected = f.target(step.role).failNext(step.method, mode);
        check(!f.adapter.bindBatteryTrack(34f), "profile-update failure must reject binding");
        List<Step> expected = batteryInit(435, 34, 8, 17);
        expected.addAll(update.subList(0, index + 1));
        expected.addAll(cleanupCalls());
        expectTrace(f, expected);
        assertCleared(f, null);
        equal(1, ModuleLog.WARNINGS.size(), "failed profile logs one warning");
        Throwable actual = ModuleLog.WARNINGS.get(0).error;
        if (mode == FailureMode.RETURN_FALSE) {
            check(actual instanceof IllegalStateException, "profile setter false is still rejected");
            equal(step.method + " returned false", actual.getMessage(), "failure identifies profile setter");
        } else check(actual == injected, "profile invocation exception is unwrapped");
        check(!f.adapter.bindBatteryTrack(34f), "failed attachment does not retry forever");
        check(!f.adapter.bind(), "switching to ring does not bypass failure gate");
        expectTrace(f, expected);
        f.adapter.detach();
        check(f.adapter.bindBatteryTrack(34f), "new attachment recovers after failed profile update");
        expected.addAll(batteryInit(435, 34, 9, 17));
        expectTrace(f, expected);
        assertBattery(f, 435, 34, 9, 17);
    }

    private static List<Step> batteryInit(int width, int height, int small, int big) {
        List<Step> result = new ArrayList<>();
        for (Step step : initializationCalls(width, height)) {
            if (step.role == Role.PROVIDER && step.method.equals("setMiBackgroundBlurRadius"))
                result.add(call(Role.PROVIDER, step.method, 0));
            else if (step.method.equals("setMiGlassBlurRadius"))
                result.add(call(step.role, step.method, small, big));
            else result.add(step);
        }
        return result;
    }

    private static List<Step> profileUpdate(int width, int height, int background, int small, int big) {
        List<Step> steps = new ArrayList<>(List.of(
                call(Role.PROVIDER, "setMiBackgroundBlurRadius", background),
                call(Role.PROVIDER, "setMiGlassBlurRadius", small, big),
                call(Role.CONSUMER, "setMiGlassBlurRadius", small, big)));
        steps.addAll(geometryCalls(width, height));
        return steps;
    }

    private static Step call(Role role, String method, Object... args) { return new Step(role, method, true, args); }
    private static int ownCallCount(Fixture f) { return f.surface.calls.size() + f.provider.calls.size() + f.marker.calls.size(); }

    private static void assertBattery(Fixture f, int width, int height, int small, int big) throws Exception {
        check(f.adapter.isBound(), "battery remains bound");
        equal(0, f.provider.backgroundRadius, "battery backdrop must stay zero");
        equal(small, f.provider.glassSmall, "battery small radius from actual path thickness");
        equal(big, f.provider.glassBig, "battery big radius from actual path thickness");
        equal(small, f.surface.glassSmall, "consumer small radius matches provider");
        equal(big, f.surface.glassBig, "consumer big radius matches provider");
        check(big >= small && small >= 1 && small <= 36 && big <= 72, "positive bounded ordered glass radii");
        equal(1, f.provider.backgroundBlurMode, "provider registration unchanged");
        equal(0, f.provider.viewBlurMode, "provider remains sampler-only");
        check(f.provider.passWindow && f.provider.containBelowDisabled, "provider pass/containment unchanged");
        check(f.surface.chosenContainer == f.provider, "owned sampling source unchanged");
        equal(0, f.surface.backgroundBlurMode, "consumer has no generic background blur");
        equal(3, f.surface.viewBlurMode, "consumer native path mode unchanged");
        equal(1, f.surface.materialType, "material type unchanged");
        equal(16, f.surface.surfaceColorType, "surface type unchanged");
        equal((float) width, f.surface.sdfWidth, "SDF uses integer View width");
        equal((float) height, f.surface.sdfHeight, "SDF uses integer View height, not path thickness");
        equal(new float[]{-50f, -50f, width + 50f, height + 50f}, f.surface.glassClip, "native clip unchanged");
        assertOptics(f);
    }

    private static void assertOptics(Fixture f) throws Exception {
        byte[] expected = recipeBytes(expectedRecipe());
        check(Arrays.equals(expected, recipeBytes(f.surface.glassRecipe)), "battery optical recipe bytes unchanged");
        check(Arrays.equals(expected, recipeBytes(submittedRecipe(f))), "submitted optical recipe bytes unchanged");
        Field recipe = LockWidgetGlass.class.getDeclaredField("RECIPE");
        recipe.setAccessible(true);
        check(Arrays.equals(expected, recipeBytes((float[]) recipe.get(null))), "shared 42-slot recipe bytes unchanged");
        equal(Float.floatToRawIntBits(72f), Float.floatToRawIntBits(f.marker.getStrokeWidth()), "edge slot19 stays72");
        equal(Float.floatToRawIntBits(3.8f), Float.floatToRawIntBits(f.marker.getStrokeMiter()), "edge slot20 stays3.8");
        equal(Paint.Style.FILL, f.marker.getStyle(), "marker fill unchanged");
        equal(0xFFFFFFFF, f.marker.getColor(), "opaque marker unchanged");
        check(f.marker.glassEffect, "native Paint glass flag retained");
    }

    private static byte[] recipeBytes(float[] recipe) {
        equal(42, recipe.length, "exactly 42 optical floats");
        ByteBuffer bytes = ByteBuffer.allocate(42 * Float.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (float value : recipe) bytes.putInt(Float.floatToRawIntBits(value));
        return bytes.array();
    }

    private static float[] submittedRecipe(Fixture f) {
        for (int i = f.surface.calls.size() - 1; i >= 0; i--) {
            Call call = f.surface.calls.get(i);
            if (call.method.equals("setMiGlass") && ((float[]) call.references[0]).length > 0)
                return (float[]) call.references[0];
        }
        throw new AssertionError("No optical recipe submitted");
    }
}
