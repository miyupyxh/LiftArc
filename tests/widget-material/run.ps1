[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$SkipApiVariants,
    [switch]$SkipNegativeControls
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$adapter = Join-Path $projectRoot 'app\src\main\java\com\lockbar\app\xposed\LockWidgetGlass.java'
$sourceRoot = Join-Path $PSScriptRoot 'src'
$viewStub = Join-Path $sourceRoot 'android\view\View.java'
$paintStub = Join-Path $sourceRoot 'android\graphics\Paint.java'
$sharedSources = @(
    (Join-Path $sourceRoot 'testsupport\RecordingTarget.java'),
    (Join-Path $sourceRoot 'testsupport\RecordingView.java'),
    (Join-Path $sourceRoot 'testsupport\RecordingPaint.java'),
    (Join-Path $sourceRoot 'com\lockbar\app\xposed\ModuleLog.java'),
    (Join-Path $sourceRoot 'com\lockbar\app\xposed\LockWidgetGlassTest.java'),
    (Join-Path $sourceRoot 'com\lockbar\app\xposed\BatteryTrackTest.java'),
    (Join-Path $sourceRoot 'com\lockbar\app\xposed\ApiVariantTest.java')
)
foreach ($source in ($sharedSources + @($viewStub, $paintStub, $adapter))) {
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Required source missing: $source" }
}

function Resolve-JdkTool([string]$Name) {
    if (-not [string]::IsNullOrWhiteSpace($JdkHome)) {
        $candidate = Join-Path $JdkHome ('bin\' + $Name + '.exe')
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    return (Get-Command $Name -CommandType Application -ErrorAction Stop).Source
}

$javac = Resolve-JdkTool 'javac'
$java = Resolve-JdkTool 'java'
$adapterHashBefore = (Get-FileHash -LiteralPath $adapter -Algorithm SHA256).Hash
$artifactRoot = Join-Path $projectRoot 'build\widget-material-tests'
$runName = 'run-' + [datetime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$runRoot = Join-Path $artifactRoot $runName
$temporary = Join-Path $runRoot 'tmp'
New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null
# Fresh classes, generated variants, and mutation controls: no deletion, no stale classpath.
New-Item -ItemType Directory -Path $runRoot | Out-Null
New-Item -ItemType Directory -Path $temporary | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
$oldTemp = $env:TEMP
$oldTmp = $env:TMP
$viewMethods = @(
    'clearMiBackgroundBlendColor', 'setPassWindowBlurEnabled', 'getPassWindowBlurEnabled', 'setMiViewBlurMode',
    'setMiBackgroundBlurMode', 'setMiBackgroundBlurRadius', 'setMiGlassBlurRadius',
    'setMiViewMaterialType', 'setMiGlassSdfMaxSize', 'setMiGlass',
    'chooseBackgroundBlurContainer', 'setMiCustomSurfaceColorType', 'setMiGlassClip',
    'disableMiBackgroundContainBelow'
)
$paintMethods = @('setGlassEffect')

function Invoke-Checked([string]$Tool, [string[]]$ToolArguments, [string]$Label) {
    & $Tool @ToolArguments
    $toolExit = $LASTEXITCODE
    if ($toolExit -ne 0) { throw "$Label failed with exit code $toolExit. Artifacts: $runRoot" }
}

function Compile-Variant([string]$Name, [string]$ViewSource, [string]$PaintSource, [string]$AdapterSource = $adapter) {
    $classes = Join-Path (Join-Path $runRoot $Name) 'classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $compilerArguments = @(
        "-J-Djava.io.tmpdir=$temporary", '-J-XX:-UsePerfData',
        '-J-Duser.language=en', '-J-Duser.country=US',
        '--release', '11', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', $classes
    ) + $sharedSources + @($ViewSource, $PaintSource, $AdapterSource)
    Invoke-Checked -Tool $javac -ToolArguments $compilerArguments -Label "Compile $Name"
    return $classes
}

function Run-Variant([string]$Classes, [string]$MainClass, [string[]]$MainArguments, [string]$Report = '') {
    $runtimeArguments = @("-Djava.io.tmpdir=$temporary", '-XX:-UsePerfData')
    if ($Report -ne '') { $runtimeArguments += "-Dwidget.tests.report=$Report" }
    $runtimeArguments += @('-cp', $Classes, $MainClass) + $MainArguments
    Invoke-Checked -Tool $java -ToolArguments $runtimeArguments -Label $MainClass
}

function Write-ApiStub([string]$Variant, [string]$Kind, [bool]$Void = $false, [string]$Omit = '') {
    $original = if ($Kind -eq 'View') { $viewStub } else { $paintStub }
    $names = if ($Kind -eq 'View') { $viewMethods } else { $paintMethods }
    $relative = if ($Kind -eq 'View') { 'src\android\view\View.java' } else { 'src\android\graphics\Paint.java' }
    $lines = [System.IO.File]::ReadAllLines($original, [System.Text.Encoding]::UTF8)
    $declared = @($lines | Where-Object { $_ -match '^\s*public boolean \w+\(' })
    if ($declared.Count -ne @($names).Count) { throw "Unexpected $Kind test API declarations" }
    if ($Omit -ne '') {
        $pattern = '^\s*public boolean ' + [regex]::Escape($Omit) + '\('
        if (@($lines | Where-Object { $_ -match $pattern }).Count -ne 1) { throw "Test declaration absent or duplicated: $Kind.$Omit" }
    }
    $generated = foreach ($line in $lines) {
        if ($Omit -ne '' -and $line -match $pattern) { continue }
        # These two boolean signatures are evidenced by the real framework, not generic setters.
        $passStateApi = $Kind -eq 'View' -and $line -match '^\s*public boolean (set|get)PassWindowBlurEnabled\('
        if ($Void -and -not $passStateApi -and $line -match '^\s*public boolean \w+\(') {
            $line.Replace('public boolean ', 'public void ').Replace('{ return nativeCall(', '{ nativeCall(')
        } else { $line }
    }
    $destination = Join-Path (Join-Path $runRoot $Variant) $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    [System.IO.File]::WriteAllLines($destination, [string[]]$generated, $utf8)
    return $destination
}

try {
    $env:TEMP = $temporary
    $env:TMP = $temporary
    Write-Host "Compiling real adapter: $adapter"
    Write-Host "Adapter SHA256: $adapterHashBefore"
    Write-Host "Artifacts: $runRoot"
    $classes = Compile-Variant -Name 'boolean-api' -ViewSource $viewStub -PaintSource $paintStub
    $behaviorReport = Join-Path $runRoot 'behavioral-cases.txt'
    Run-Variant -Classes $classes -MainClass 'com.lockbar.app.xposed.LockWidgetGlassTest' -MainArguments @() -Report $behaviorReport
    $batteryReport = Join-Path $runRoot 'battery-cases.txt'
    Run-Variant -Classes $classes -MainClass 'com.lockbar.app.xposed.BatteryTrackTest' -MainArguments @() -Report $batteryReport
    $variantCount = 0
    $negativeCount = 0

    if (-not $SkipApiVariants) {
        foreach ($combination in @(@('void', 'void'), @('void', 'boolean'), @('boolean', 'void'))) {
            $variant = 'returns-view-' + $combination[0] + '-paint-' + $combination[1]
            $v = Write-ApiStub -Variant $variant -Kind 'View' -Void ($combination[0] -eq 'void')
            $p = Write-ApiStub -Variant $variant -Kind 'Paint' -Void ($combination[1] -eq 'void')
            $variantClasses = Compile-Variant -Name $variant -ViewSource $v -PaintSource $p
            Run-Variant -Classes $variantClasses -MainClass 'com.lockbar.app.xposed.ApiVariantTest' -MainArguments @('returns', $combination[0], $combination[1])
            if ($combination[0] -eq 'void' -and $combination[1] -eq 'void') {
                Run-Variant -Classes $variantClasses -MainClass 'com.lockbar.app.xposed.BatteryTrackTest' -MainArguments @('probe-profile')
            }
            $variantCount++
        }
        foreach ($kind in @('View', 'Paint')) {
            $names = if ($kind -eq 'View') { $viewMethods } else { $paintMethods }
            foreach ($missing in $names) {
                $variant = 'missing-' + $kind + '-' + $missing
                $v = Write-ApiStub -Variant $variant -Kind 'View' -Omit $(if ($kind -eq 'View') { $missing } else { '' })
                $p = Write-ApiStub -Variant $variant -Kind 'Paint' -Omit $(if ($kind -eq 'Paint') { $missing } else { '' })
                $variantClasses = Compile-Variant -Name $variant -ViewSource $v -PaintSource $p
                Run-Variant -Classes $variantClasses -MainClass 'com.lockbar.app.xposed.ApiVariantTest' -MainArguments @('missing', $kind, $missing)
                $variantCount++
            }
        }
    }

    if (-not $SkipNegativeControls) {
        # Production is read-only. Deliberately defective COPIES compile/run solely under ignored build.
        # A control counts only if it compiles and the behavioral probe fails at its intended assertion.
        $originalSource = [System.IO.File]::ReadAllText($adapter, [System.Text.Encoding]::UTF8)
        $controls = @(
            @{ Name = 'omit-paint-flag'; Old = 'nativeApi.call(nativeApi.paintGlass, marker, true);'; New = '/* negative control: omitted Paint flag */'; Probe = 'probe-init'; Assertion = 'ordered trace length' },
            @{ Name = 'wrong-paint-object'; Old = 'nativeApi.call(nativeApi.paintGlass, marker, true);'; New = 'nativeApi.call(nativeApi.paintGlass, new Paint(), true);'; Probe = 'probe-init'; Assertion = 'wrong owner' },
            @{ Name = 'generic-blur-mode'; Old = 'nativeApi.call(nativeApi.viewBlur, surface, 3);'; New = 'nativeApi.call(nativeApi.viewBlur, surface, 1);'; Probe = 'probe-init'; Assertion = 'arguments at' },
            @{ Name = 'transparent-marker'; Old = 'marker.setColor(0xFFFFFFFF);'; New = 'marker.setColor(0x19FFFFFF);'; Probe = 'probe-init'; Assertion = 'arguments at' },
            @{ Name = 'missing-edge-width'; Old = 'marker.setStrokeWidth(RECIPE[19]);'; New = 'marker.setStrokeWidth(0f);'; Probe = 'probe-init'; Assertion = 'arguments at' },
            @{ Name = 'missing-edge-miter'; Old = 'marker.setStrokeMiter(RECIPE[20]);'; New = 'marker.setStrokeMiter(0f);'; Probe = 'probe-init'; Assertion = 'arguments at' },
            @{ Name = 'wrong-sampling-owner'; Old = 'nativeApi.call(nativeApi.chooseContainer, surface, backdrop);'; New = 'nativeApi.call(nativeApi.chooseContainer, surface, surface.getRootView());'; Probe = 'probe-init'; Assertion = 'arguments at' },
            @{ Name = 'shared-recipe-array'; Old = '(Object) RECIPE.clone()'; New = '(Object) RECIPE'; Probe = 'probe-clone'; Assertion = 'shared baseline not mutated' },
            @{ Name = 'unexpanded-clip'; Old = 'private static final float CLIP_PADDING_PX = 50f;'; New = 'private static final float CLIP_PADDING_PX = 0f;'; Probe = 'probe-init'; Assertion = 'arguments at' },
            @{ Name = 'configuration-claims-draw'; Old = 'bound = true;'; New = 'bound = true; onDrawSubmitted();'; Probe = 'probe-draw'; Assertion = 'not yet notified of a draw' },
            @{ Name = 'omit-paint-cleanup'; Old = 'clearOne(paintGlass, paint, false);'; New = '/* negative control: omitted marker cleanup */'; Probe = 'probe-detach'; Assertion = 'ordered trace length' },
            @{ Name = 'omit-provider-containment'; Old = 'nativeApi.call(nativeApi.containBelow, backdrop, true);'; New = '/* negative control: omitted owned-provider containment */'; Probe = 'probe-init'; Assertion = 'ordered trace length' },
            @{ Name = 'reject-all-false-old-policy'; Old = 'if (method == passWindow && args.length == 1'; New = 'if (false && method == passWindow && args.length == 1'; Probe = 'probe-pass-idempotent'; Assertion = 'idempotent pass state must be accepted' },
            @{ Name = 'accept-mismatched-false'; Old = '&& args[0].equals(passWindowState.invoke(target))'; New = '&& true'; Probe = 'probe-pass-mismatch'; Assertion = 'false with mismatched readback must be rejected' },
            @{ Name = 'battery-keeps-gaussian-base'; Old = 'int backdropRadius = batteryTrack ? 0 : BACKDROP_BLUR;'; New = 'int backdropRadius = BACKDROP_BLUR;'; MainClass = 'com.lockbar.app.xposed.BatteryTrackTest'; Probe = 'probe-profile'; Assertion = 'battery backdrop must stay zero' },
            @{ Name = 'battery-uses-rounded-view-height'; Old = 'Math.round(thicknessPx / 4f)'; New = 'Math.round(surface.getHeight() / 4f)'; MainClass = 'com.lockbar.app.xposed.BatteryTrackTest'; Probe = 'probe-profile'; Assertion = 'battery small radius from actual path thickness' },
            @{ Name = 'battery-narrows-optical-slot19'; Old = 'nativeApi.call(nativeApi.glass, surface, (Object) RECIPE.clone());'; New = 'float[] opticalRecipe = RECIPE.clone(); if (batteryTrack) opticalRecipe[19] = thicknessPx; nativeApi.call(nativeApi.glass, surface, (Object) opticalRecipe);'; MainClass = 'com.lockbar.app.xposed.BatteryTrackTest'; Probe = 'probe-optics'; Assertion = 'battery optical recipe bytes unchanged' },
            @{ Name = 'battery-narrows-marker-edge'; Old = 'marker.setStrokeWidth(RECIPE[19]);'; New = 'marker.setStrokeWidth(batteryTrack ? thicknessPx : RECIPE[19]);'; MainClass = 'com.lockbar.app.xposed.BatteryTrackTest'; Probe = 'probe-optics'; Assertion = 'edge slot19 stays72' },
            @{ Name = 'battery-ignores-same-bounds-thickness'; Old = 'boolean profileChanged = profileThicknessPx != thicknessPx;'; New = 'boolean profileChanged = false;'; MainClass = 'com.lockbar.app.xposed.BatteryTrackTest'; Probe = 'probe-thickness'; Assertion = 'ordered trace length' }
        )
        foreach ($control in $controls) {
            $first = $originalSource.IndexOf($control.Old, [StringComparison]::Ordinal)
            $last = $originalSource.LastIndexOf($control.Old, [StringComparison]::Ordinal)
            if ($first -lt 0 -or $first -ne $last) { throw "Negative-control site changed: $($control.Name)" }
            $variant = 'negative-' + $control.Name
            $copy = Join-Path (Join-Path $runRoot $variant) 'src\com\lockbar\app\xposed\LockWidgetGlass.java'
            New-Item -ItemType Directory -Path (Split-Path -Parent $copy) -Force | Out-Null
            [System.IO.File]::WriteAllText($copy, $originalSource.Replace($control.Old, $control.New), $utf8)
            $controlClasses = Compile-Variant -Name $variant -ViewSource $viewStub -PaintSource $paintStub -AdapterSource $copy
            $controlLog = Join-Path (Join-Path $runRoot $variant) 'expected-rejection.txt'
            $probeClass = if ($control.ContainsKey('MainClass')) { $control.MainClass } else { 'com.lockbar.app.xposed.LockWidgetGlassTest' }
            $runtimeArguments = @("-Djava.io.tmpdir=$temporary", '-XX:-UsePerfData', '-cp', $controlClasses,
                $probeClass, $control.Probe)
            & $java @runtimeArguments *> $controlLog
            $controlExit = $LASTEXITCODE
            $rejection = [System.IO.File]::ReadAllText($controlLog)
            if ($controlExit -ne 1 -or -not $rejection.Contains('java.lang.AssertionError') -or -not $rejection.Contains($control.Assertion)) {
                throw "Negative control was not rejected by the intended assertion: $($control.Name). Inspect $controlLog"
            }
            Write-Host "[PASS] Negative control rejected: $($control.Name)"
            $negativeCount++
        }
        # Positive control in a fresh JVM, against the unchanged real adapter, after the mutants.
        Run-Variant -Classes $classes -MainClass 'com.lockbar.app.xposed.LockWidgetGlassTest' -MainArguments @('probe-init')
    }

    $adapterHashAfter = (Get-FileHash -LiteralPath $adapter -Algorithm SHA256).Hash
    if ($adapterHashBefore -ne $adapterHashAfter) { throw 'Production adapter changed during tests; rerun against a stable revision.' }
    $behaviorSummary = @([System.IO.File]::ReadAllLines($behaviorReport, [System.Text.Encoding]::UTF8) |
        Where-Object { $_ -like 'Behavioral suite:*' })
    if ($behaviorSummary.Count -ne 1) { throw 'Behavioral result summary missing or duplicated.' }
    $batterySummary = @([System.IO.File]::ReadAllLines($batteryReport, [System.Text.Encoding]::UTF8) |
        Where-Object { $_ -like 'Battery suite:*' })
    if ($batterySummary.Count -ne 1) { throw 'Battery result summary missing or duplicated.' }
    $summary = @(
        'PASS: real native path adapter host-JVM regression tests',
        $behaviorSummary[0],
        $batterySummary[0],
        "API variants passed: $variantCount",
        "Compiled negative controls correctly rejected: $negativeCount",
        "Production adapter SHA256: $adapterHashAfter",
        "javac: $javac", "java: $java", "Artifacts: $runRoot",
        'Configured/submitted diagnostics are not proof of hardware rendering, path masks, or GPU appearance.',
        'No Gradle, ADB, production edits, device installation, or SystemUI restart.'
    )
    [System.IO.File]::WriteAllLines((Join-Path $runRoot 'summary.txt'), [string[]]$summary, $utf8)
    Write-Host "PASS: behavioral suite, $variantCount API variants, $negativeCount negative controls; adapter hash unchanged."
    Write-Host "Summary: $(Join-Path $runRoot 'summary.txt')"
} finally {
    $env:TEMP = $oldTemp
    $env:TMP = $oldTmp
}
