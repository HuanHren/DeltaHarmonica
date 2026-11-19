# DeltaHarmonica

Android 游戏口琴演奏助手。导入 MIDI 或 JSON 演奏脚本，本地保存曲目，并使用已校准的 12 个目标通过 Root 或 Android 无障碍演奏。应用界面使用 Jetpack Compose 和 Miuix，支持游戏内浮窗选曲、试听和整曲演奏。

## 使用

1. 在「设置 → 演奏与输入」选择演奏后端。Root 授权可在「关于与诊断 → 输入诊断」检查。无障碍和悬浮窗权限分别从「权限与系统」和「悬浮窗与游戏内控制」进入。
2. 从首页打开「导入 MIDI / JSON」。MIDI 可选择旋律轨道、单音化策略和移调。JSON 演奏脚本可直接加入曲库。
3. 从「设置 → 校准与布局」打开游戏内校准，再进入横屏口琴界面。点开悬浮胶囊，把 8 个音键、3 个音区控制点及半音控制点对准游戏界面，保存后确认坐标。
4. 从曲库进入「演奏」，或打开游戏悬浮窗后直接选曲。选择 8 音、16 音或整曲，以及 1× 或 2×。确认游戏音区、半音和校准后开始演奏。
5. 演奏时可使用屏幕上的停止按钮。「演奏与输入」中可以管理音量减停止和无障碍音量加暂停。

[设置与游戏内校准指南](docs/settings-and-calibration.md)

## 构建

使用 JDK 21、Android SDK Platform 37.2 和 Build Tools 37.0.0。在 `local.properties` 配置 SDK 路径，或设置 `ANDROID_HOME`。Gradle Wrapper 固定在 9.9.1，项目使用 AGP 9.5.1、Kotlin 2.4.21、Compose 1.12.2 和 Miuix 0.9.5-beta01。隔离测试脚本还会用 Build Tools 35.0.0 核验 APK。

当前 Gradle 配置在初始化时读取 Gradle 用户目录的 `gradle.properties`。签名配置项为 `DH_RELEASE_STORE_FILE`、`DH_RELEASE_STORE_PASSWORD`、`DH_RELEASE_KEY_ALIAS` 和 `DH_RELEASE_KEY_PASSWORD`。配置和值应保存在仓库外。正式应用的覆盖更新需要沿用原签名证书。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\gradlew.bat assembleInstrumented assembleInstrumentedAndroidTest
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 测试与数据

正式应用包名为 `com.huanhren.deltaharmonica`。设备自动化使用独立的 `.instrumented` 宿主和 `.instrumented.test` 测试包。两者均无桌面入口，使用隔离目录及无特权输入环境。Gradle 设备任务受保护，指定真机的隔离测试由 `scripts/safe-device-runner.ps1` 执行。

校准坐标以当前方向完整默认显示屏为参考系，坐标范围为 `[0,1]`。草稿必须显式保存才会写入校准布局。演奏使用现有导入事务、曲库、规划器与执行器。应用内进度来自输入派发和回调。

历史验收记录见 [Miuix 界面改造](docs/miuix-ui-overhaul-final-report.md)、[F1 输入验收](docs/f1-root-acceptance.md) 和 [F2–F4 验收](docs/f2-f4-final-acceptance.md)。
