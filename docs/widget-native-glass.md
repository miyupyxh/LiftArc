# 锁屏小组件：系统路径柔光玻璃适配（2026-11-09）

## 范围与已确认的回归边界

只修改小组件的背景材质。外层大小、固定 DP 定位、通知处理、景深层序、AOD 渐隐/渐显、解锁手势和独立的弧形玻璃条均不改。

[控制器](../app/src/main/java/com/lockbar/app/xposed/LockBarController.java) 与 [hook 入口](../app/src/main/java/com/lockbar/app/xposed/LockBarHooks.java) 的基线 SHA-256：

```text
LockBarController.java  3D441C81119ED702F458F27D51B3DF789DD3DA5CC84CC5F51278575E053082A5
LockBarHooks.java       2668A2E5BBF434873B9638D9E4BE5281E9EC1B0CEDB8C1C3A5562C32A78B4EC3
```

## 首次适配的实机反馈：配置成功不等于玻璃绘制成功

用户 19:46 的截图显示环形仍像高斯模糊。19:45 日志里的 `native-mi-glass` 三条记录，仅代表 View setter 返回且没有异常，不代表折射/高光真正进入绘制。

首次适配采用 [HyperChanger](https://github.com/ColdP/HyperChanger) 小组件的 View 属性设置顺序及 42 槽 profile，但遗漏了 **自绘 Path 与系统圆角 Drawable 的原生绘制契约不同**：

- 参考小组件使用 GradientDrawable，配合椭圆/圆角矩形 Outline。
- LiftArc 用 PathShape 绘制 240° 环形，却仍使用普通 Paint，且使用 `alpha=1/255` 的种子。背景采样可以工作，路径的玻璃绘制标志却没有设置。
- 额外的 Path Outline 不会自动提供这个标志；`Outline.canClip()` 也不是 MiGlass 的光学支持检测。原独立 Outline 还把默认 alpha=0 拷贝给了视图。
- 首轮 71 项行为测试/11 个 API 变体只测试了该版配置契约，并不能证明该契约足以生成所需的 GPU 效果。该结果不能继续用于声称视觉适配已成功。

首次测试包 SHA-256 为 `C4AED3A9CDAE990495FD15B2F5CF8567EA5D0E7EE9A8E9A64D5847D3FE39DD9D`，对应的是上述被用户反馈为模糊效果的版本。

## 二次适配的实际 ROM 依据

对提供的 SystemUI 17.03.260226 APK 做实际方法调用与 DEX 字段引用核对，而不只根据工具类名称推断：

- [TimeView.onDraw 的实际 Paint 标志](../../_analysis/widget-softglass-paint/TimeView.smali#L480-L532)：调用 `Paint.setGlassEffect(boolean)`，目标是随后 `Canvas.drawPath` 使用的同一个 Paint。
- [边缘参数与实际 drawPath](../../_analysis/widget-softglass-paint/TimeView.smali#L774-L886)：`glassData[19]` 写入 Paint.strokeWidth，`glassData[20]` 写入 strokeMiter，保持 FILL；默认使用完整不透明的绘制 Paint，而不是 alpha=1/255 的占位种子。
- [原生 clip](../../_analysis/widget-softglass-paint/TimeView.smali#L691-L771)：独立设置 `View.setMiGlassClip(left, top, right, bottom)`，给路径边缘留出 50px 空间。
- [路径 View 设置](../../_analysis/widget-softglass-native/ClockMiuiBlurUtils.smali#L1142-L1205)：material type=1、View blur mode=3、custom surface color type=16。这里的 mode=3 不是 self-blur type=3。
- [采样容器与消费者的绑定](../../_analysis/widget-softglass-paint/MiuiBaseClock2-updateViewBackgroundBlurContainer.smali#L21-L59)：先初始化选中的 provider，再给每个绘制子 View 显式设置 `chooseBackgroundBlurContainer(child, provider)`。
- [SystemUI 中的采样容器设置](../../_analysis/widget-softglass-paint/ClockEffectUtils.smali#L1017-L1035)：开启 pass-window、背景采样 mode=1、玻璃采样半径，并禁用采样包含下层视图。
- [全 DEX 引用核对](../../_analysis/widget-softglass-paint/dex-paint-xrefs.txt)：扫描全部 3 个 DEX 的 144211 个方法代码项，确认上述 Paint wrapper 的实际使用点。没有因此推断 ROM 的所有 native 内部实现已被验证。

手机无线调试重新连接后，又读取实际安装的 framework 核对：

- [Paint.setGlassEffect 的真实实现](../../_analysis/widget-softglass-renderer/FrameworkPaint.smali#L7833-L7843) 是公开的 void 方法，直接调用 `nSetGlassEffect(nativePaint, enabled)`，并非仅保存 Java 层描述。
- [setPassWindowBlurEnabled](../../_analysis/widget-softglass-renderer/View-setPassWindowBlurEnabled.smali#L150-L218) 在目标状态已相同时返回 **false**，不表示错误。尤其新消费者本来就没开采样，设置 false 必然可能返回 false。
- 因而只对这个 setter 的 false 做[实际状态读回](../../_analysis/widget-softglass-renderer/View-getPassWindowBlurEnabled.smali#L1-L19)：读回状态与目标一致才接受幂等返回；若请求开启但仍未开启，则回退。其他模拟 API 的错误 false 仍不被静默忽略。

没有导入 OEM 类或通知中心代码。Atlas 优化、特定字体用的 simplifyPath、未知增强标志都没有加入，因为没有证据表明本组件需要它们。

## 当前实现

[LockWidgetView.java](../app/src/main/java/com/lockbar/app/xposed/LockWidgetView.java) 保留外层控件/API及原来的环形 Path、文字、图标与电量填充。每个背景变为两个模块自有节点：

```text
原外层 LockWidgetView（仍由原 Controller 控制位置和 AOD alpha）
  GlassSurface（FrameLayout，只提供背景采样）
    PathRenderer（View，只绘制玻璃路径，不绘制文字）
  其他组件的同类采样/绘制节点
  原有前景绘制（super.dispatchDraw 之后）
```

[LockWidgetGlass.java](../app/src/main/java/com/lockbar/app/xposed/LockWidgetGlass.java) 当前配置：

1. 自有采样容器：pass-window=true、background blur mode=1、view blur mode=0、原有背景半径 40、玻璃半径 36/72、`disableMiBackgroundContainBelow(true)`。不额外显示一块高斯底板。
2. 自有路径消费者：不再自行采样，background mode=0；显式选择对应容器；view blur mode=3、custom surface color=16、material type=1；保持原有 42 槽 profile；设置实际尺寸 SDF 和 native clip。
3. 专用 Paint：FILL、完整白色、strokeWidth=profile[19]、strokeMiter=profile[20]，再开启原生 `Paint.setGlassEffect(true)`。此 Paint 直接传给 `Canvas.drawPath(maskPath, marker)`，不与文字或其他前景共享。
4. 移除普通 PathShape/Outline 遮罩链路，不再让其提前裁掉原生光学边缘。当前 clip 用 View bounds±50px，包含原 Path，并非声称与 TimeView 的 Path bounds±50px 完全同值。
5. 两个节点都挂载、硬件加速且有非零尺寸后才绑定；同尺寸重复布局无重复初始化，尺寸变化只更新 SDF/clip。任何必需的 View/Paint 接口缺失则完整回退；清理按先消费者、后采样器的次序尽力执行。
6. 软件 Canvas 跳过原生 Paint 路径并使用旧版可读背景，避免把不透明标记画成白块。正常原生分支不叠加旧壁纸 Shader/人工高光。

本轮没有调整 42 槽光学 profile 来掩盖管线遗漏：仍是参考小组件 profile（luminance=.14 等）。不同 HyperChanger 设置入口的缺省半径有差异，这里不导入它的偏好系统，也不宣称与所有用户实际配置一致。

## 日志与验证界限

日志明确分成：

- `native-path-configured`：View 与 Paint 配置已提交，包含 mode/type/paint 标志。
- `hardware-path-submitted`：已经向硬件 Canvas 提交带标志的路径。
- `fallback`：配置失败，保留可读背景并记录具体异常。

**以上两种成功状态仍不是 GPU 视觉验收。** 测试不能证明该 ROM 的环孔、端帽、折射高光、动态壁纸或 AOD 合成最终呈现正确。实际截图需要在加载新版后确认。

[主机测试](../tests/widget-material/README.md) 针对真实 adapter 更新，增加 provider/consumer、Paint 标志与边缘数据、原生 clip、两端就绪、失败清理、缺失 API 和负向变体；只报告其实际执行结果。原 release 闸门同时检查代码边界、打包、依赖与签名，不将静态字符串检查等同于视觉验证。

### 本轮构建与安装记录

最终已完成：

- `:app:assembleRelease --no-daemon`：BUILD SUCCESSFUL（仍为 `125 / 1.0.25`）。
- 真实 adapter 主机测试：**172 项行为测试、18 个编译接口变体通过**；**14 个编译后的故障反例均被预期断言拒绝**，包括漏掉 Paint 标志、错误 Paint、错误采样器、透明标记，以及错误理解 pass-window false 返回值。
- 实际测试/构建的 adapter SHA-256：`014422F1F7C2EA5438070E738C2EDDE67BD85489DC4352E934E6C81AE9435654`。
- Release 回归闸门：ALL CHECKS PASSED；15 个 hook-side 类的依赖检查与 APK v2 签名校验通过。
- 环形 Path、前景指针/文字、外层测量以及电池轨道坐标，与本轮基线比对一致；Controller/Hooks 逐字节未变。
- 最初 ADB 列表为空，后来重新发现手机已变更的无线调试端口并连接。**本轮最终包已执行 `adb install -r`，结果为 Success**。
- 最终安装包 SHA-256：`CD8F4BE8081A1963DC8E006BB58D8B39D8B2CED3D8587B9ED26B3F37778FE047`。

未重启 SystemUI，因此仍没有把新版加载后的 GPU 视觉效果记为已验证。

不使用 GitHub Actions，不自动重启 SystemUI，不提交或推送。

## 电池未填充轨道的独立采样配置（后续修正）

用户随后已确认小组件呈现柔光玻璃，但电池条未填充背景仍显得像普通模糊。这份反馈不否定已经工作的路径 Paint 管线，不能再按“没开原生玻璃”处理。

本轮日志记录电池轨道完成原生配置及硬件路径提交。源码确认：旧的半透明 barBg 只在回退/软件 Canvas 分支绘制，没有证据表明它覆盖了当前原生背景；实色电量仅覆盖已填充部分。因此不删除回退、不修改前景进度、不重写 renderer。

本轮只新增 `bindBatteryTrack(actualPathHeightPx)`：

- 普通圆环仍调用 `bind()`，保持普通背景半径 40、玻璃采样 36/72，以及完整的 42 槽配方与 Paint 参数。
- 电池轨道将普通背景模糊半径设为 **0**；仍保留原生玻璃双采样，按真实路径厚度 `t` 配置 `round(t/4)` / `round(t/2)`，分别限在 1..36 和 small..72。
- 默认轨道真实厚度为 33px，尽管取整后的 View/SDF 高度可能是 34px；实际传入的是 `shapeH`，得到玻璃采样 **8/17**，不拿整数 View 高度代替路径厚度。
- 用户已认可的高光参数不动：**全部 42 个光学参数保持原值**，包括 edge=72、miter=3.8。本轮没有采用额外的边缘宽度缩小方案，以免削弱已确认的高光。
- 真实厚度变化会同步采样器与消费者的半径，即使整数 View 尺寸没变；不拆层、不重新注册采样器，不重置 Paint 或 AOD alpha。宽度变化但厚度不变仍只更新几何。
- 生产视图文件相对本轮基线只变化一行 profile 分发，电池填充/文字/图标/路径/坐标完全未变；Controller/Hooks 逐字节未变。

这是针对电池背景的受控材质配置调整，不是凭日志断言高斯模糊来源已经得到视觉证明。原生玻璃自身仍含软化采样；不能承诺“所有模糊都消失”，也不能把未填充区的最终观感写成已验证。当前手机未停留在锁屏，没有为检查效果而切换或重启屏幕。

本轮已完成本地验证与安装：

- 既有 **172** 项行为测试保持通过；新增 **49** 项电池轨道测试通过。
- **18** 个接口变体通过；**19** 个编译后的故障反例被正确拒绝。
- 原生配方 42 个浮点值按原始 IEEE 字节检查未变；Paint edge=72/miter=3.8 保持不变。额外覆盖同整数边界下厚度变化、无效厚度、范围限制、环形独立性、五个 profile 更新失败点与重挂载。
- Release 本地构建成功，回归闸门 ALL CHECKS PASSED，APK v2 签名校验通过。
- 测试与构建对应的 adapter SHA-256：`019B1CE4E8A9B3DEDA8E238915C50DFC1469D90796EA87DC76CBC7EF32BFB2DA`。
- 已执行本轮 `adb install -r`，返回 Success；安装前核对 APK SHA-256 为 `13FBE063A55F914C422A98416C063FACE3E93BB3F10F1D9CBD35FBB7580B1DBE`。

未重启 SystemUI，未提交或推送。这里只记录实现、参数测试和安装通过，电池未填充区的新观感仍待加载后的实机确认。

## 许可

42 槽参数和参考适配部分来自 Copyright 2026 btm_m、AGPL-3.0-or-later 的 HyperChanger 原始文件；许可和修改声明随 APK 附带。系统 APK 仅用于核对调用契约，没有打包 OEM 字节码。详见 [第三方声明](../THIRD_PARTY_NOTICES.md)。
