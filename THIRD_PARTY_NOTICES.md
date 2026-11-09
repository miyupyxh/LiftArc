# Third-party notices

## HyperChanger — native lockscreen widget material

- Project: <https://github.com/ColdP/HyperChanger>
- Copyright 2026 btm_m.
- License of the referenced original files: **GNU Affero General Public License, version 3 or later (AGPL-3.0-or-later)**.
- Reference: the user-supplied HyperChanger source snapshot examined on 2026-11-09, specifically the widget soft-material methods and `SHORTCUT_GLASS_PARAMETERS` in `HyperSystemUiModule.kt`, and `HyperOsBackdropMaterial.kt`.
- Adapted portion: the native compositor initialization sequence and 42-value optical recipe in [LockWidgetGlass.java](app/src/main/java/com/lockbar/app/xposed/LockWidgetGlass.java).
- LiftArc modifications, 2026-11-09: a Java adapter for three widget-owned sampler/path-consumer pairs; checked framework View and Paint calls; nonzero-size initialization, SDF/native clip updates, and Paint optical-edge metadata; rollback on partial failure; detach/re-attach cleanup. The custom-path API contract was verified against the supplied ROM's TimeView drawing calls, without copying or bundling OEM implementation classes. The original widget layout, foreground drawing, AOD animation, and notification hooks are not imported from HyperChanger.

The adapted file retains its AGPL-3.0-or-later notice. The full AGPL version 3 text is included in [the application assets](app/src/main/assets/licenses/HyperChanger-AGPL-3.0.txt). A copy of this notice is also shipped in those assets. Original LiftArc portions retain their existing GPLv3 terms; this notice does not relicense unrelated files. When conveying a combined build, preserve these notices and provide the complete corresponding source, including these modifications, in accordance with the applicable GPLv3/AGPLv3 terms (including their section 13 combination provisions). An upstream link alone is not the corresponding source of a modified build.

HyperChanger's snapshot-wide notice distinguishes original AGPL-3.0-or-later files from separately licensed imported components. No HyperMusicCover, HyperCeiler, or OEM implementation classes were copied as part of this material adaptation. Xiaomi/HyperOS framework methods are invoked from the installed system; decompiled OEM code is not bundled in LiftArc.
