# DeltaHarmonica F1 Root 输入验收

2026-11-18 · Xiaomi 23127PN0CC · Android 16 / API 36 · 正式应用包 `com.huanhren.deltaharmonica`

## 设备结果

| 项目 | 结果与证据 |
| --- | --- |
| Root 管理器与 UID | 首次尝试返回退出码 13、`Permission denied`。用户在 Magisk 授权后，正式应用的 `su -c 'id -u'` 返回 0，Root 触摸子进程以 UID 0 完成初始化。 |
| 8 个音键、点击、连续输入 | 正式应用的 Debug 接收界面实际收到 8/8 对应的 DOWN 和 UP。请求保持 35 ms，依次收到 35、35、35、36、37、35、35、36 ms。全套 120 个计划音符中没有观测到两个音符同时处于按下状态。 |
| 音区与半音 | 真实接收事件与计划的控制键顺序一致。全套 7 个计划累计收到 33 次音区触摸和 107 次半音触摸。113 次组合触摸在音符按下时仍有至少一个控制触点处于按下状态。相对首个控制触点，实际音符按下延后 39–43 ms，设置值 40 ms。 |
| 长按与释放 | 接收器实测一段请求为 57,000 ms 的按住在 57,001 ms 抬起。7 个演奏计划末音均实际释放。 |
| 实体音量减 | Root 内核按键监视器在第二个长音期间记录 `t=338788133`，接收器收到同一事件时间的 UP，日志时间相差约 4 ms。Runner 返回已停止、1 个已完成、1 个未完成，无播放错误。首轮断言只允许在第一音期间停止，因此诊断器将该轮标为失败。修正诊断断言后，程序触发停止的复测收到当前音符 UP、后续两项未完成，重连后实际收到下一次触摸。 |
| 异常恢复 | 错误目标 UID 的派发被拒绝，接收器未产生触摸。关闭该输入会话后重新连接，实际收到 NOTE_2 的 DOWN 和 UP，保持 45 ms。首次快速回归在覆盖安装时被打断，系统日志记录接收窗口暂停和安装终止旧进程，之后完整重跑通过。 |
| 游戏实际演奏 | 正式应用首次安装前未发现正式包，安装后首页和只读存储检查均无已保存布局。用户进入口琴界面后，使用 12 个临时屏幕坐标进行验证。录屏逐键显示 1–7、高音 1 对应高亮，松开后高亮消失。游戏画面显示升调、降调、自然音依次生效，并在第 3 键高亮时同时显示升调、半音开启和 ♯ 标记。用户确认「8 音清楚，长音和切换后音高有变化」。 |
| 游戏内物理停止与恢复 | 360 音测试中用户实体按下音量减并确认「正常停止」。前 14 音已完成，第 15 音在按键报告 1 ms 后派发 UP，后续 346 音未完成，没有新的 DOWN。随后重新连接，以 2× 播放 8 音，派发 8/8、最后一音 UP。 |

## 应用接收窗口的时值与播放范围

数据来自目标 Xiaomi 上正式应用 Debug 接收窗口的 `MotionEvent.eventTime`。用不同的 `ScriptEvent.durationMs` 驱动现有播放规划器和调度器，比较收到的抬起减按下与按速度换算后的目标时值。测试用时值 1× 为 30–500 ms，2× 为 15–250 ms。

| 范围 | 速度 | 实收音符 | 最大时值误差 | 相对计划最大开始延迟 | 会话耗时 | 末音释放 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 前 8 音 | 1× | 8 | 1 ms | 9 ms | 4,018 ms | 已接收 UP |
| 前 16 音 | 1× | 16 | 4 ms | 11 ms | 8,342 ms | 已接收 UP |
| 整组 | 1× | 24 | 2 ms | 12 ms | 13,169 ms | 已接收 UP |
| 前 8 音 | 2× | 8 | 1 ms | 10 ms | 2,012 ms | 已接收 UP |
| 前 16 音 | 2× | 16 | 1 ms | 9 ms | 4,180 ms | 已接收 UP |
| 整组 | 2× | 24 | 1 ms | 10 ms | 6,601 ms | 已接收 UP |
| 长时整组 | 1× | 24 | 1 ms | 11 ms | 60,323 ms | 已接收 UP |

每轮核对了进度计数、控制动作顺序和退出时空闲状态。MIDI 解析、曲库事务和导入保护由完整 JVM 回归覆盖。

## 真实 MIDI 与游戏窗口

正式应用 Debug 诊断入口读取项目已有 MIDI 文件，经原有解析器、映射和播放调度器演奏。测试临时坐标保留在内存，MIDI 文件存入本轮专用测试缓存。下表的时值来自 Root 助手发往游戏 UID 的 DOWN／UP 时间。游戏录屏和用户反馈用于核对游戏响应，尚无整曲逐音的音频测量。

| 文件与范围 | 速度 | 完成／总数 | 派发时值最大误差 | 会话耗时 |
| --- | --- | ---: | ---: | ---: |
| `duration16-test.mid` 前 8 音 | 1× | 8/8 | 1 ms | 7,134 ms |
| `duration16-test.mid` 前 8 音 | 2× | 8/8 | 1 ms | 3,577 ms |
| `duration16-test.mid` 前 16 音 | 1× | 16/16 | 3 ms | 15,135 ms |
| `duration16-test.mid` 前 16 音 | 2× | 16/16 | 2 ms | 7,576 ms |
| `stage6-60s.mid` 整曲 | 1× | 120/120 | 2 ms | 59,761 ms |
| `stage6-60s.mid` 整曲 | 2× | 120/120 | 4 ms | 29,899 ms |
| `pitch-switch-test.mid` 音区及半音自动切换 | 1× | 8/8 | 1 ms | 7,169 ms |
| `stage6-180s.mid` 游戏内音量减停止 | 1× | 14/360，下一音按下期间停止 | 停止时提前抬起 | 7,332 ms |

所有完整演奏的最后一音均记录 UP，且无音键重叠派发。三分钟素材首传校验失败，应用识别为无效轨道并停止于首音前。重传并核对 SHA-256 后进行了实体键停止测试。完整三分钟演奏、游戏逐音音频精度、无障碍回退的真机端到端验证和基于已保存布局的常规入口仍未实测。

## 实现与构建

Root 在 Android 14 及以上通过独立输入进程派发目标 UID 的 MotionEvent。一个输入流最多持有一个音键，控制键使用独立触点。停止时先抬起音键，再取消尚未完成的控制触点。启动失败且尚未触摸时可以回退到已连接的无障碍后端。发送途中的异常先尝试清理和停播，释放无法确认时禁止续播。无障碍模式的停止仍需等待当前系统手势的回调。

修复了 `su` 进程退出后输出管道仍阻塞会延长调用期限的问题。新增 9 项 JVM 测试，覆盖输入时序、组合触点、续音安全、停止、失败与清理、Xiaomi 实体键驱动识别和命令期限。Debug 单元测试共 200 项，失败、错误和跳过均为 0。Debug、Release 和隔离测试 APK 构建通过，Debug lint 0 错误、34 警告。设备更新使用签名校验和 `adb install -r`，真机结束后只移除了本轮复制进去的四个临时 MIDI 夹具。本轮未卸载、清数据、保存校准或提交 Git。

### 文件清单

- 应用入口和配置 · `app/src/main/AndroidManifest.xml`、`DeltaHarmonicaApp.kt`、`res/xml/accessibility_service.xml`
- Root 和输入互斥 · `input/RootBackend.kt`、`RootCommandRunner.kt`、`RootGestureConnection.kt`、`RootTouchMain.kt`、`RootTouchTimeline.kt`、`RootVolumeKeyMonitor.kt`、`NoteAction.kt`
- 播放与停止 · `accessibility/TouchAccessibilityService.kt`、`overlay/CalibrationOverlayService.kt`、`playback/PlaybackPreparation.kt`、`PlaybackRunner.kt`、`ui/AppViewModel.kt`、`HomeScreen.kt`、`MidiScreen.kt`
- Debug 真机接收器 · `app/src/debug/AndroidManifest.xml`、`RootInputDiagnosticActivity.kt`
- JVM 回归 · `RootTouchTimelineTest.kt`、`RootVolumeKeyMonitorTest.kt`、`SuCommandRunnerTest.kt`

### 证据

- [正式应用进程拒绝授权日志](../app/build/f1-evidence/device-authorization.log)
- [设备型号、Root UID 和校准数据只读检查](../app/build/f1-evidence/device-identity.log)
- [8／16／整组时值和长时运行接收日志](../app/build/f1-evidence/device-suite.log)
- [实体音量减与抬起事件](../app/build/f1-evidence/device-stop.log)
- [停止按钮共用入口与重连复测](../app/build/f1-evidence/device-automatic-stop.log)
- [错误目标拒绝与恢复实收日志](../app/build/f1-evidence/device-reject.log)
- [覆盖安装中断和窗口暂停的系统日志](../app/build/f1-evidence/device-reinstall-stop.log)
- [游戏 8 键、音区、半音组合与释放的录屏帧](../app/build/f1-evidence/game-frames-final.png)
- [游戏 120 音 1× 派发](../app/build/f1-evidence/game-midi-stage6-60s-120-1.log)
- [游戏 120 音 2× 派发](../app/build/f1-evidence/game-midi-stage6-60s-120-2.log)
- [游戏实体音量减、当前音符抬起和终止](../app/build/f1-evidence/game-midi-extended.log)
- [游戏停止后的 8 音复测](../app/build/f1-evidence/game-recovery-after-stop.log)
- [三分钟素材首传异常日志](../app/build/f1-evidence/game-midi-extended-attempt1.log)
- [最后安装的 APK 签名与哈希](../app/build/f1-evidence/final-device-install.log)
- [构建和 JVM 回归](../app/build/f1-evidence/final-build.log)
- [Android 16 目标 UID 注入结果](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/input/InputManagerService.java)
- [Android 实体输入事件工具](https://android.googlesource.com/platform/system/core/+/android16-qpr2-release/toolbox/getevent.c)
