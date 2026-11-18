# DeltaHarmonica Miuix UI/UX 翻新验收

2026-11-18 · `feature/miuix-ui-overhaul`

| 阶段 | 状态 | 依据 |
| --- | --- | --- |
| F1 Root 输入 | **PASS（历史）** | [F1 真机验收](f1-root-acceptance.md) |
| F2 校准和正常演奏 | **PASS（历史）** | [F2–F4 真机验收](f2-f4-final-acceptance.md) |
| F3 整曲与音频精度 | **PARTIAL** | 本轮范围为 UI/UX。整曲逐音频测量待完成。 |
| F4 回退与稳定性 | **PARTIAL** | 运行中故障与 Root 回退矩阵待完成。 |

## 1. Miuix 依赖和版本

项目审计时使用 Compose Material 3，Gradle 未启用 Miuix。本轮接入有本地缓存的 `top.yukonga.miuix.kmp:miuix-android:0.3.8`，实际调用主题、卡片、按钮和文本 API。Gradle 8.11.1、AGP 8.9.2、Kotlin / Compose 编译插件 2.1.20、Compose BOM 2025.04.01、minSdk 26。部分 Android 通用控件继续使用 Material 3，由同一套颜色、文字和形状定义提供外观。[Miuix 项目](https://github.com/compose-miuix-ui/miuix)

## 2. 设计系统

`ColorTokens` 定义背景、表面、前景和强调色，原生浮窗也读取这些颜色。集中定义文字层级、间距、控件点击区和圆角。通用组件提供页面标题、按钮、卡片、选项、状态卡、确认对话框和操作底部弹层。统一线性导航图标和选中状态。页面切换、进度变化和浮窗展开使用短动效。

## 3. 导航

首页、曲库、演奏、设置是主导航。竖屏使用底栏，宽布局使用侧栏。导入、沉浸式校准和触摸测试由主页面进入。应用与浮窗复用进程内的 `AppViewModel`，页面切换和 Activity 重建可以继续观察同一导入、选曲和演奏状态。

## 4. 页面改造

| 页面 | 改造内容 |
| --- | --- |
| 首页 | 品牌标题、当前曲目或最近演奏、曲库数量、导入、打开曲库、游戏浮窗、后端与校准状态。 |
| 曲库 | 懒加载列表、曲名搜索、按导入时间／曲名／最近演奏排序、当前曲目标记、曲目元数据、重命名与删除确认。 |
| 导入 | MIDI 和 JSON 文件选择，处理阶段、读取中、取消、成功、失败、已保存但加载失败。MIDI 可选轨道、单音化和移调，依旧由现有导入与转换代码处理。 |
| 演奏 | 曲目、事件数、当前音符、进度、8 音／16 音／整曲、1×／2×、开始及停止。同步与风险确认单独呈现。高级区保留 JSON 导出、0.5×、触摸和时序诊断。 |
| 设置 | 后端和权限、默认速度、音量减止播、主题、校准管理和版本。隔离宿主不显示 Root 授权入口。 |
| 校准 | 原归一化坐标、12 个目标和拖动触点继续使用。优化目标视觉及选中反馈，显示归一化与像素坐标，恢复默认先确认，只在显式保存时写入布局。 |

## 5. 游戏悬浮窗

浮标是 52 dp 半透明的可拖动按键，保存归一化位置；窗口根据横屏尺寸进行边界限制。展开后可换曲、同步状态、设定范围和速度、查看进度、开始、停止、最小化和关闭。演奏派发前隐藏会盖住触点的主面板，保留原来经过坐标交叠检查的紧凑停止窗口。停止窗口显示已完成事件数，主面板在会话结束后恢复。独立的游戏校准视图保留原坐标和触摸测试。

## 6. 游戏内选曲和开始

从浮标进入「选择曲目」。列表直接使用 `SongLibraryRepository`，选择后由共享 ViewModel 校验曲目并加载工作区。按需同步游戏音区与半音，再确认校准和规划警告。应用和浮窗都通过共享预检生成 `PreparedPlayback`，进入原服务的游戏焦点、屏幕尺寸、后端和规划校验，再交由原 `PlaybackRunner` 执行。MIDI 解析器、映射、导入协调器、导入事务、曲库仓库、规划器、Runner 和布局存储格式沿用既有实现。

## 7. 音量减停止

默认开启。Root 路径向现有触摸连接传入音量减回调。Accessibility 路径在现有 `onKeyEvent` 中依据偏好决定是否调用同一个停止入口。Root 释放、后续调度取消和异常清理由现有实现承担。Accessibility 当前已派发的系统手势需等结束或回调。暂停功能没有安全的引擎接口。

## 8. 横屏与竖屏

首页、曲库、导入和设置可滚动。宽屏演奏页并排显示进度和操作，短屏保留滚动区域。横屏校准继续按真实全屏像素和 Canvas 原点转换；测试覆盖从已保存的布局重建并重新显示目标。

## 9. 深色与浅色

支持跟随系统、手动浅色、手动深色，持久保存选择。状态栏与导航栏图标随有效主题调整。浮窗创建时读取有效主题。实机逐项观察了竖屏浅色首页和系统深色设置，并将系统夜间模式恢复为原设置。

## 10. UI 自动化

Xiaomi 真机隔离回归 **12/12 通过**。覆盖真实文档读取、MIDI 与 JSON 导入、取消、提交后工作区加载失败、导入跨导航／旋转／重建、横屏坐标重载和 RTL 坐标、空曲库、55 首曲目滚动和搜索、主题设置和播放状态。测试使用隔离 APK，Root 执行器替换为拒绝调用的测试实现。

复测前将仍访问旧 Activity 作用域 ViewModel 的测试改成共享入口，并等待实际完成 Activity 重建和横屏配置后再执行坐标断言。真机测试记录最终输出 `OK (12 tests)`。

## 11. JVM 测试

Debug JVM 215 项，0 失败、0 错误、0 跳过。新增状态回归覆盖曲库搜索和按最近演奏排序、导入已保存与未保存的失败区分、停止后进度保留。

## 12. Lint

`lintDebug` 通过，0 错误、46 警告。详细问题见本机 `app/build/reports/lint-results-debug.html`。

## 13. 构建

| 命令 | 结果 |
| --- | --- |
| `gradlew.bat assembleDebug testDebugUnitTest lintDebug` | 成功 |
| `gradlew.bat assembleInstrumented assembleInstrumentedAndroidTest` | 成功 |

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。两份隔离 APK 使用独立包名、隔离数据目录和无特权测试宿主。签名、Manifest、SHA-256 和正式包存在性由现有隔离运行器检查。日志保存在本机 `app/build/reports/miuix/`。

## 14. 真机视觉验收

Xiaomi 23127PN0CC · Android 16 · 1200×2670 · 480 dpi。正式包经证书比对后原地更新。首页读取原有 2 首曲目及 12 个已保存校准点，设置页显示 Root 已授权。游戏横屏观察到浮标最小化、拖动和面板展开。看过游戏实机画面后，将浮窗的范围与速度选项并排、进度和状态并列，缩短展开面板。

| 真机操作 | 观察结果 |
| --- | --- |
| 游戏内浮窗直接选曲、16 音、1×、实体音量减 | Root 路径启动。音量键监听触发，已完成 5 音、剩余 11 音、已停止、无回报错误。最后完成音有 Root 抬起记录，后续无音符派发。 |
| 游戏内 8 音、1× 自然完成 | Root 路径运行，8/8 完成、剩余 0、无回报错误。用户确认两轮操作正常。 |

游戏验证针对实际口琴界面。派发、抬起和回调时间来自输入日志。最终 APK 在此基础上缩短展开面板，并包含完整构建和隔离回归。实机截图和日志保存在本地构建报告中。

## 15. 修改文件

- 构建与入口 · `app/build.gradle.kts`、`DeltaHarmonicaApp.kt`、`MainActivity.kt`、`IsolatedTestApplication.kt`
- 公共界面 · `ui/theme/Theme.kt`、`ui/theme/Components.kt`、`ui/ProductGlyph.kt`
- 导航、状态和偏好 · `ui/AppNavigation.kt`、`ui/AppViewModel.kt`、`ui/PlaybackScreenState.kt`、`ui/PlayerPreferences.kt`、`ui/ProductUiState.kt`
- 页面 · `ui/HomeScreen.kt`、`ui/LibraryScreen.kt`、`ui/ImportScreen.kt`、`ui/PlayerScreen.kt`、`ui/SettingsScreen.kt`、`ui/CalibrationScreen.kt`、`ui/TargetMarker.kt`、`ui/TestScreen.kt`。原 `ui/MidiScreen.kt` 拆为曲库、导入和播放器。
- 悬浮服务和停止入口 · `overlay/GamePlayerPanel.kt`、`overlay/OverlayStyle.kt`、`overlay/CalibrationOverlayService.kt`、`accessibility/TouchAccessibilityService.kt`
- 测试 · `ProductUiStateTest.kt`、`ProductUiDeviceTest.kt`、`StageOneDeviceTest.kt`、`ImportLifecycleDeviceTest.kt`、`MidiImportDeviceTest.kt`、`WorkspaceProtectionDeviceTest.kt`、`IsolatedHostCheck.kt`、`scripts/safe-device-runner.ps1`
- 交付 · `.gitignore`、`README.md`、本报告。公开分支首提交包含构建所需源码、工具和测试夹具。用户导出的曲目、个人设备截图、构建文件和本地配置保留在本地。

## 16. 待完成项

- 本轮实机演奏验证使用 Root 和 1× 试听。Accessibility 实体键、2×、整曲及游戏运行中的异常路径保留既有验证记录。
- F3 的音频测量和 F4 的 Root 故障回退矩阵继续保持 **PARTIAL**。
