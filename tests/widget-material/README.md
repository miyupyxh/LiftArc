# Native path-glass adapter: isolated host-JVM regressions

These tests compile and execute the **real current** [LockWidgetGlass.java](<../../app/src/main/java/com/lockbar/app/xposed/LockWidgetGlass.java>) with small fake Android `View`, `Paint`, and `ModuleLog` classes. The constructor under test receives **separate owned consumer/provider Views and the exact marker Paint**. No Android SDK, external dependency, Gradle, ADB, installation, or restart is involved.

**Passing setter-contract tests is not evidence of working glass pixels.** The suite covers the native Paint/path contract and the pass-window setter's framework-specific return semantics. In particular, `false` can mean an already-correct pass-window state, not failure. The earlier strict-false mock contract was insufficient and is no longer used for this setter. These tests still do not render anything or certify HyperOS/GPU behavior.

## Run

From the project root, using JDK 11 or newer through `JAVA_HOME` or `PATH`:

```powershell
pwsh -NoProfile -File .\tests\widget-material\run.ps1
```

For only the base and battery behavioral suites during local iteration:

```powershell
pwsh -NoProfile -File .\tests\widget-material\run.ps1 -SkipApiVariants -SkipNegativeControls
```

An explicit JDK can be selected with `-JdkHome 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'`; no machine configuration is changed.

The [runner](<run.ps1>) writes fresh classes, generated fake-ROM stubs, test-only mutated source copies, rejection logs, a case-by-case behavioral ledger, and a summary below the existing ignored `build/widget-material-tests/` directory. No files are deleted. Child JVM `TEMP`, `TMP`, and `java.io.tmpdir` point into that run directory, and JVM performance-data file creation is disabled. The environment is restored afterward. Production source is read-only and its SHA-256 must remain unchanged throughout the run.

## Executable behavioral coverage

The [main suite](<src/com/lockbar/app/xposed/LockWidgetGlassTest.java>) pins **23 ordered configuration writes plus state-dependent pass-window readbacks**, and **18 ordered cleanup steps**. Default initialization has 24 calls because the already-disabled consumer returns `false` and must be queried. Already-enabled providers add a readback too; successful changes returning `true` need none. Tests assert identities, signatures, argument types, and values:

- **Provider:** clear blend; pass-window `true`; background mode `1`; view mode `0`; radius `40`; glass radii `36/72`; `disableMiBackgroundContainBelow(true)` on the owned provider only.
- **Consumer:** no inherited pass-window or generic background blur; mode `3`; explicitly choose its own provider; glass radii `36/72`; material type `1`; custom surface color type `16`; SDF measured from the consumer; native clip padded by **50px** on every side; all **42** native recipe values.
- **The supplied marker object:** ordinary `FILL`, opaque `0xFFFFFFFF` color, `recipe[19] = 72` stroke width, `recipe[20] = 3.8` stroke miter, followed by checked native `Paint.setGlassEffect(true)` on that **same object**, not an unrelated Paint.
- **Cleanup:** disable marker glass and fully retire the consumer before shutting down its provider; release explicit container reference, custom surface type, native clip, modes, recipe, radii, blend, and provider containment. Double detach is idempotent; new attachment fully rebinds.

The same tests also cover:

- Rejecting aliased provider/consumer arguments.
- Detached, non-hardware, zero-width/height, and negative-width/height conditions for **both nodes** before initial binding and after an established binding. Initial gates cause no reflection or View/Paint writes; losing readiness clears established state; readiness recovery rebinds. Positive 1px dimensions remain valid.
- Same-size idempotence; consumer resizing updates **only SDF and clip**; provider-only positive resizing does not reinstall the consumer or marker.
- Independent cloned recipe arrays across instances and reattachment. Mutating a submitted array cannot change the baseline, another pair, or subsequent marker edge metadata.
- Three independently owned widget material pairs. Neither native nor ordinary Paint operations may touch the foreign outer host, window, notification, or foreground Paint. No View alpha, visibility, translation, layout, scale, or animation setter may be called, even to write back the same value.
- Every configuration write and required readback throwing before/after its operation. Fake boolean setters other than pass-window still reject `false`, even after a partial write or when their resulting property equals the requested value. Pass-window uses the dedicated readback cases below; a getter legitimately returning `false` is data, not an invocation failure. Ordinary Paint setters are `void`, so only throwable failure modes apply to them.
- Both resize steps failing before/after writes or returning `false`, with rollback of already-bound provider/consumer/Paint state.
- Every cleanup call throwing before/after its write or returning `false`, without preventing cleanup of all remaining properties or throwing out of detach.
- Exception unwrapping, failure retry gates, later rebind recovery, and rate-limited diagnostics.
- **Configuration vs submission:** binding logs `native-path-configured`, never `hardware-path-submitted`. Only an explicit `onDrawSubmitted()` notification while bound may add the submission log. Unbound/failed states cannot claim submission; repeated notifications do not reconfigure native state. Both diagnostics remain rate-limited for the adapter lifetime.

### Pass-window return and readback semantics

The fake pass-window setter now models the framework contract: it returns `false` when the requested state already matches. It can also be injected to return `false` after changing state, or to deny a write and leave the previous state intact. The required `getPassWindowBlurEnabled()` returns the actual recorded state; both method calls and returned values are captured.

The real adapter must accept `false` **only for this setter and only when the getter equals the requested boolean**. Tests cover already-enabled providers, already-disabled consumers, both idempotent at once, successful changing calls without unnecessary queries, false-after-writing-desired acceptance, false-without-writing acceptance only when already correct, rejection/rollback for both mismatched directions, and getter exceptions. An unchanged provider left by a failed cleanup must also be accepted on rebind after readback. A getter returning `false` for an already-disabled consumer is valid data, not a generic invocation failure.

The [shared recorder](<src/testsupport/RecordingTarget.java>) preserves a single cross-object call order, raw argument identities, and value snapshots. The [View recorder](<src/testsupport/RecordingView.java>) and [Paint recorder](<src/testsupport/RecordingPaint.java>) model only explicit property writes. The [View stub](<src/android/view/View.java>) and [Paint stub](<src/android/graphics/Paint.java>) declare actual reflectable OEM signatures. Only the adapter's static API-lookup cache is reset between independent cases; its bind, call, rollback, and detach logic are not replaced.

## Battery-only sampler profile

The separate [battery suite](<src/com/lockbar/app/xposed/BatteryTrackTest.java>) leaves the existing 172-case ring/readback suite unchanged. It checks:

- Zero, negative, NaN, and either infinity before/after binding; invalid thickness never poisons later valid initialization and clears an existing binding.
- Explicit radius fixtures from subpixel through large finite thickness, including **33px actual path thickness in a 34px View => 8/17**, and lower/upper clamps. SDF/clip still use the View bounds.
- Battery provider background radius **0**, while both native glass sampling radii follow the actual path thickness. Ring defaults remain background **40**, glass **36/72**.
- Width-only updates write only SDF/clip. Thickness changes with unchanged integer bounds write exactly provider background radius, provider/consumer dual radii, then SDF/clip—even when the newly rounded radii are unchanged. No new recipe, Paint write, or sampler registration is allowed in this update.
- All **42 optical floats compared as exact IEEE-754 raw bytes**, for the submitted array, recorded native recipe, and shared constant. Marker edge slot19 stays **72**, miter slot20 stays **3.8**; these accepted-highlight values must not be narrowed to battery thickness.
- Independent ring/battery instances, battery-to-ring-to-battery transitions, detach/rebind, both-node hardware gates, and rollback/recovery at each of the five profile-update calls in three failure modes.

The framework-shaped void-setter variant also runs a positive battery profile probe. These tests assert native call parameters, not whether a device's unfilled battery area visibly stopped looking Gaussian.

## Separately compiled API variants

The [variant suite](<src/com/lockbar/app/xposed/ApiVariantTest.java>) recompiles the same real adapter for **18 fake-ROM environments**, each run in a fresh JVM:

1. Framework-shaped signatures: pass-window setter/getter return `boolean`; every other used native View setter and the Paint flag return `void`.
2. The same View signatures, with a fake boolean Paint flag for compatibility coverage.
3. Other View setters return fake booleans, while Paint's flag returns `void`.
4. Each of the **14 required View methods**, including the getter, is actually omitted, one at a time.
5. Required **Paint.setGlassEffect** is actually omitted.

Pass-window setter/getter remain boolean in every present-API variant. The console's `View=void` label abbreviates the return type of the **other View setters**, not those two methods; Java assertions verify the individual signatures.

Missing methods must fail before any native state or ordinary marker metadata is written, with one process-wide missing-capability diagnostic and no configured/submitted claim. These are missing declarations in real compiled classes, not source-string-only checks or injected binding results. The baseline retains strict `false` rejection for other fake boolean setters, including provider containment and Paint's flag. Only pass-window has readback-based acceptance.

## Compiled negative controls

To demonstrate that the assertions can reject the old blur-only/incomplete contract, the runner generates **19 deliberately defective copies** of the adapter **only under ignored build output**. Each copy must compile, then fail an executable contract probe with the intended assertion:

- Omitted marker glass flag.
- Native flag applied to a different Paint.
- Generic consumer blur mode `1` instead of path mode `3`.
- Translucent marker instead of opaque white.
- Missing edge width or missing edge miter metadata, independently.
- Root-window sampling instead of the owned provider.
- Shared mutable recipe array instead of cloning.
- No 50px native clip padding.
- Configuration falsely triggering a draw-submission notification.
- Missing marker-flag cleanup.
- Missing owned-provider containment setup.
- Old strict-false policy incorrectly rejecting the already-disabled consumer.
- Pass-window `false` accepted without verifying that the getter matches the requested state.
- Battery profile accidentally keeping its ordinary Gaussian background radius.
- Battery radii computed from rounded View height instead of actual path thickness.
- Battery optical recipe slot19 narrowed to path thickness.
- Battery marker edge width narrowed to path thickness while the recipe is unchanged.
- Same-bounds battery thickness changes incorrectly ignored by the profile cache.

A compile failure, unrelated exception, or unexpectedly passing defective copy **does not** count as a rejected negative control; it fails the runner. A final positive probe executes against the unchanged real adapter after the negative controls. Production source is never modified, and each JVM is isolated from all earlier mutations.

## What this does not establish

- No actual canvas/GPU command, optical result, live wallpaper sampling, non-convex path mask, compositor resource lifetime, or device stability is validated.
- The supplied marker's identity and metadata are tested inside the adapter. These host tests do **not** compile the production widget renderer or prove that its `drawPath` uses that marker; that remains an integration/device check.
- `onDrawSubmitted()` has no Canvas parameter and trusts its caller. Calling it in a test is a simulated host event, **not proof of hardware submission or visual success**.
- Parent/foreground objects and View property writes are checked at the adapter boundary. Real notification layering, FrameLayout lifecycle dispatch, AOD fade timing, gestures, and controller behavior are not executed here.
- If a native cleanup setter throws **before** changing its property, that particular property cannot be asserted cleared. Tests require every other cleanup to proceed and logical adapter state to reset; they do not pretend a failed native setter worked.

Production renderer/controller/hooks/configuration and the existing verification script remain outside this test-only change.
