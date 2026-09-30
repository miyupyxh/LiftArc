# 升弧 · LiftArc

> 澎湃OS4 / HyperOS 锁屏「小白条」—— 仿 iOS 风格的上滑解锁联动，带屏幕圆角弧形柔光。
> 内部代号 `LockBar`，管理界面类MiuiX风格。

基于 **libxposed（新版 Xposed API 102）** 实现的 LSPosed 模块，UI 与 hook 严格分包：
`xposed/` 包不引用任何 Compose / MiuiX / AppCompat 类，避免在 SystemUI 进程里触发类加载冲突。

---

## 功能

- 锁屏底部**小白条**，上滑时与解锁手势联动（iOS 风格阻尼动画）
- 屏幕圆角**弧形柔光**（借鉴 HyperBetter 的弧光观感）
- Compose 管理界面，底栏三个标签：**首页 / 参数 / 设置**
  - 首页：模块激活状态大绿框 + 模块信息 + 参数快捷入口 + 日志导出 + 诊断卡
  - 参数：逐项可调，改动即时下发，无需重新激活
  - 设置：主题设置（全部生效）+ 关于 / 引用与致谢
- 日志**一键导出到「下载」**（MediaStore，Android 13+ 零权限），导完自动拉起分享面板
- **安全模式**：系统界面连续短命启动时自动自我禁用，避免模块被反复牵连

## 环境要求

| 项 | 要求 |
|---|---|
| Android | 13+（minSdk 33） |
| 框架 | LSPosed（Zygisk），libxposed API 102 |
| 作用域 | `com.android.systemui` |
| 目标系统 | 澎湃OS4 / HyperOS（锁屏 SystemUI 已反编译分析并适配） |

> 不同系统版本的锁屏实现差异较大，其他 ROM / 版本未做适配。

## 安装

1. 安装 `app-release.apk`
2. 在 LSPosed 中把本模块作用域勾选为 **系统界面**（`com.android.systemui`）
3. 重启系统界面或重启手机
4. 打开本 App，首页大绿框显示已激活即生效

## 构建

要求 **JDK 21**（miuix-nav 0.9.4 的 inline 代码用 JVM 21 编译，目标低于 21 会报
`Cannot inline bytecode built with JVM target 21`）。

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
```

产物：`app\build\outputs\apk\release\app-release.apk`

Release 开了 R8 混淆与资源收缩；`proguard-rules.pro` 里对 `com.lockbar.app.xposed.**`
做了 `-keep`，**不可删除** —— 混淆名跨模块可能相撞，保留真名是崩溃归因时自证清白的依据。

## 安全模式

状态存放在**宿主进程**数据目录（`<systemui-dataDir>/files/lockbar_safety.properties`），
不依赖任何配置通道，因此在模块初始化最早期就能判定。

- 每次启动记录存活时间；若距开机不足 3 分钟就退出，记一次「短命」
- **连续 4 次短命启动** → 进入安全模式
- 安全模式下只保留上下文捕获、日志、状态回传与退出监听，**功能 hook 一个不装**
- 退出走远端配置 `safety_exit`，需要在 App 首页手动点「退出安全模式」，**重启系统界面生效**

> 安全模式的定位是**避免把本模块牵连进别人的启动失败**，它不是对系统界面崩溃的修复。
> 判定遵循「失败即放行」—— 任何读取异常都不拦截启动。

## 诊断回传架构

libxposed 的远端配置对 **hook 进程只读**，因此回传方向是单向受限的：

| 方向 | 通道 |
|---|---|
| App → hook | 远端配置 + `OnSharedPreferenceChangeListener`（即时 reload） |
| hook → App | `ContentProvider`（`DiagProvider`）+ `log()` |

`DiagProvider` 是白名单键、16KB 上限、**只收不给**；App 侧所有读取都走异步，不阻塞主线程。

## 致谢

**直接依赖**

| 项目 | License |
|---|---|
| [miuix](https://github.com/compose-miuix-ui/miuix) | Apache-2.0 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | Apache-2.0 |
| [libxposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API) | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 |

**实现与界面参考**

| 项目 | License |
|---|---|
| [KernelSU](https://github.com/tiann/KernelSU)（`manager/` 下的底栏与阻尼动画） | GPL-3.0-or-later |
| [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 |
| [HyperBetter](https://www.coolapk.com/u/1030764)（动画参考） | 闭源参考 |

## License

本项目采用 **[GPL-3.0](LICENSE)**。

