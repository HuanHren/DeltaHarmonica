# DeltaHarmonica F2–F4 最终验收

2026-11-18 · Xiaomi 23127PN0CC · Android 16 / API 36 · 正式包 `com.huanhren.deltaharmonica` · `feature/miuix-ui-overhaul`

| 阶段 | 结论 | 证据 |
| --- | --- | --- |
| F2 校准布局与正常流程 | **PASS** | 游戏悬浮校准、12 点保存、应用重启后原样读取、从普通曲库入口完成 Root 演奏。 |
| F3 整曲与音频 | **PARTIAL** | 正常入口使用已保存布局完整完成 360 音 1× 和 2×。逐音频测量缺少录音。 |
| F4 回退与稳定性 | **PARTIAL** | 真机双向切换后端，Accessibility 独立确认游戏焦点并完成 8 音，Root 在切回应用时终止后续输入。启动回退和触摸释放确认有 JVM 回归，运行中的故障矩阵仍有缺项。 |

## F2 校准与持久化

1. 只读检查显示起始时未保存布局，曲库目录为空，已保留曲库目录备份。用户通过 Android 正常流程授予悬浮窗权限，在实际口琴界面拖动并保存标记。
2. 首轮实机发现固定面板覆盖左侧音键。备份已保存的部分布局后覆盖安装修复版，安装前后该文件 SHA-256 一致。用户用新增拖动区域移开面板并完成左侧坐标。
3. 已解码保存数据并核验全部 12 个标记。布局为横屏，参考屏幕为 2670 × 1200，8 个音键与四个控制点均落在实际游戏目标内。返回首页并重启正式应用后，首页显示已保存布局，重建 Activity 后的布局文件逐字节一致。
4. 随后用户在游戏界面进一步调整降调与半音两点。留有前后副本，布局自身保存时间为 11:48:21，早于最终覆盖安装时间 11:56:16。后续仍使用较新的坐标，未恢复旧副本。
5. 正常入口导入短曲、完成 Root 前 8 音，之后从曲库载入正式三分钟素材，选整曲和速度，执行真实游戏播放。日志显示使用 Root、完成进度和末音抬起。游戏坐标来自保存布局。
6. 发现小窗里“屏幕不匹配，请重新校准”的提示把窗口状态和已保存布局混淆。现在提示用户切回游戏后核对，已有全屏尺寸检查继续负责决定能否派发。

证据：[面板遮挡](../app/build/f2-f4-evidence/f2-panel-obstruction.png) · [校准画面](../app/build/f2-f4-evidence/f2-saved-layout-verified.png) · [12 点坐标](../app/build/f2-f4-evidence/layout-saved-coordinates.json) · [重启记录](../app/build/f2-f4-evidence/f2-restart.log) · [普通入口短曲](../app/build/f2-f4-evidence/f2-normal-playback.log) · [后续校准差异](../app/build/f2-f4-evidence/layout-later-manual-save-diff.json) · [小窗提示问题](../app/build/f2-f4-evidence/f2-small-window-mismatch.png)

## F3 整曲播放

使用 `DeltaHarmonica-F3-180s.mid`，源文件 SHA-256 为 `1bf2efd04a4a8725dcf04c2545aaa73c546fe63d6229ad7d14851c03db202a3b`。正式曲库内的源文件哈希和 360 音解析结果均已核对。MIDI 原始时长 179,750 ms，音符 MIDI 60 和 62 交替，1× 每音 250 ms、相邻起音间隔 500 ms。

| 正常入口整曲 | 调度结果 | 会话耗时 | 逐音派发样本 | 相对首音的起始绝对误差（平均／最大） | 按住绝对误差（平均／最大） |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1× | 360/360，剩余 0，末音抬起 | 179.967 秒 | 359 | 5.42 / 11 ms | 0.40 / 6 ms |
| 2× | 360/360，剩余 0，末音抬起 | 90.066 秒 | 360 | 3.46 / 10 ms | 0.44 / 6 ms |

进度取自正常悬浮播放界面和会话结束日志。已采集音符序号单调，未见重叠按压。1× 的本地日志快照缺少零基序号 138（第 139 音）的单行派发时刻，相应误差未纳入统计。调度器的完成计数仍为 360。三分钟素材不含切换音区或半音的事件，本轮未对游戏发声的切换延迟计时。

证据：[曲库元数据](../app/build/f2-f4-evidence/f3-library-metadata.json) · [进度画面](../app/build/f2-f4-evidence/f3-progress-crop.png) · [1× 逐音派发](../app/build/f2-f4-evidence/f3-1x-dispatch.csv) · [2× 逐音派发](../app/build/f2-f4-evidence/f3-2x-dispatch.csv) · [汇总数据](../app/build/f2-f4-evidence/f3-dispatch-metrics.json) · [2× 结束日志](../app/build/f2-f4-evidence/f3-2x-final.log)

### 逐音频精度报告

按用户指示没有录屏，本轮没有取得独立的游戏录音。以下指标需要匹配实际音轨，派发时间与调度完成数已单独记录。

| 指标 | 结果 |
| --- | --- |
| 总音符数 | MIDI 每轮 360。录音实际音数未测。 |
| 正确音高数、漏音、多音 | 未测。没有独立音轨用于对应。 |
| 发声起始、持续和结束时间 | 未测。 |
| 发声误差最大值、平均值 | 未测。 |
| 粘连、长音提前结束、短音吞音 | 没有逐音轨证据。已采集的 Root 派发没有按压重叠，末音均有 UP。 |

## F4 回退、恢复与稳定性

- 旧路径在显式选 Accessibility 时仍要求 Root 确认游戏焦点。修复后以已授权的 Accessibility 服务独立读取焦点窗口应用包名。普通设置里选择 Accessibility 并完成真机 8/8，日志显示该次未请求 Root，用户反馈正常。Root 授权和其他已启用的无障碍服务保持原状。
- 修复版安装后另一次 Accessibility 游戏播放在 56/360 时停止，状态为已停止、无失败，Android 输入队列为空。日志不区分该次是屏幕按钮还是实体键触发。
- 由 Accessibility 切回 Root 后，真机演奏前三音。当在第四音按住时切回应用，Android 拒绝原窗口的 UP 和 CANCEL，Root 停止后续事件并报告释放待确认。在旧助手明确结束后，新路径只在 Android 明确报告输入队列无触摸时解除等待。针对 Xiaomi 实测中留下的空窗口记录补充了识别与回归。助手无应答或系统仍报告有触摸时继续阻止续播。另一次复测已确认窗口先切换时新 DOWN 被拒绝，系统中无触摸残留。
- 空窗口记录适配已构建并覆盖安装，用真机采集的输出形态做了 JVM 回归。最后的观察窗口内实际收到的是 Accessibility 播放与停止，未再次触发修复版 Root 按住期间切窗这一场景。
- 自动回退仅在可确认未派发触摸时切换，已派发后若无法确认释放则停止。JVM 覆盖 Root 授权失败后选择已连无障碍、无可用后端时阻断、显式选择 Accessibility 时跳过 Root，以及保留 Root 观测到的非游戏焦点结果。
- Root 在相同应用进程连续完成短曲和两轮整曲。抽样的 PSS 为 1× 播放期间 133,124 KiB，结束后 121,063 KiB，2× 结束后 121,643 KiB。2× 结束后的进程快照未发现本应用的 Root 触摸助手残留。这些是有限的抽样，未建立长期内存增长趋势。
- 尚缺真机逐项故障注入，包括 Root 无授权、超时、异常或断开后实际接管、服务断开和应用中止期间的游戏声音与释放测量。既有授权未撤销，F1 报告原样保留。

证据：[无障碍真机日志](../app/build/f2-f4-evidence/f4-device.log) · [无障碍播放停止](../app/build/f2-f4-evidence/f4-accessibility-stop.log) · [切窗故障系统日志](../app/build/f2-f4-evidence/f4-focus-switch-system.log) · [切窗复测](../app/build/f2-f4-evidence/f4-focus-switch-retest.log) · [HyperOS 实测触摸队列](../app/build/f2-f4-evidence/f4-hyperos-empty-touch-bucket.log) · [1× 运行中内存](../app/build/f2-f4-evidence/f4-memory-during-1x.log) · [2× 结束后内存](../app/build/f2-f4-evidence/f4-memory-after-2x.log) · [进程快照](../app/build/f2-f4-evidence/f4-processes-after-2x.log) · [Android 焦点窗口接口](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindows()) · [Android 移除窗口时的触摸清理](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/inputflinger/dispatcher/InputDispatcher.cpp)

## 构建与修改文件

- `gradlew.bat assembleDebug testDebugUnitTest lintDebug` 成功。原 200 项全量保留，新增 12 项，合计 212 项，失败、错误、跳过均为 0。Lint 0 错误、35 条警告，其中新增 1 条为拖动区域的自定义触摸可访问性检查。
- `gradlew.bat assembleInstrumented assembleInstrumentedAndroidTest` 成功。隔离包仅构建，真机使用正式包。
- 校准与播放 · `CalibrationOverlayService.kt`、`OverlayGeometry.kt`、`MidiScreen.kt`、`PlaybackScreenState.kt`。
- 后端焦点、授权说明与清理 · `PlaybackInput.kt`、`AccessibilityBackend.kt`、`TouchAccessibilityService.kt`、`accessibility_service.xml`、`strings.xml`、`SettingsScreen.kt`、`RootBackend.kt`、`RootGestureConnection.kt`、`RootCleanup.kt`。
- 回归 · `PlaybackInputTest.kt`、`OverlayGeometryTest.kt`、`PlaybackScreenStateTest.kt`、`RootCleanupTest.kt`。
- 曲库事务、导入保护和 MIDI 音高映射未改动，均由全量回归覆盖。使用同证书覆盖安装，未卸载、执行 `pm clear`、重置布局、提交或推送 Git。

证据：[Debug、JVM 和 Lint 日志](../app/build/f2-f4-evidence/debug-final.log) · [隔离包构建日志](../app/build/f2-f4-evidence/instrumented-final.log) · [F1 原报告](f1-root-acceptance.md)

| Gradle 最终命令输出中逐条任务的计数 | 实际执行 | UP-TO-DATE | SKIPPED | NO-SOURCE | FAILED |
| --- | ---: | ---: | ---: | ---: | ---: |
| Debug、单测和 Lint | 14 | 39 | 1 | 4 | 0 |
| Instrumented 和测试 APK | 7 | 62 | 2 | 6 | 0 |
