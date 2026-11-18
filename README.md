# DeltaHarmonica

Android 游戏口琴演奏助手。导入 MIDI 或 JSON 演奏脚本，本地保存曲目，并使用已校准的 12 个目标通过 Root 或 Android 无障碍演奏。应用界面使用 Jetpack Compose 和 Miuix，支持游戏内浮窗选曲、试听和整曲演奏。

## 使用

1. 在「设置」选择演奏后端。在 Root 管理器授权，或在系统设置开启 DeltaHarmonica 无障碍服务。允许悬浮窗后可打开游戏内播放器和校准工具。
2. 从首页打开「导入 MIDI / JSON」。MIDI 可选择旋律轨道、单音化策略和移调。JSON 演奏脚本可直接加入曲库。
3. 进入横屏口琴界面，通过「游戏内校准」把 8 个音键、3 个音区控制点及半音控制点对准游戏界面，保存后在演奏前核对。
4. 从曲库进入「演奏」，或打开游戏悬浮窗后直接选曲。选择 8 音、16 音或整曲，以及 1× 或 2×。确认游戏音区、半音和校准后开始演奏。
5. 演奏时可使用屏幕上的停止按钮。默认开启音量减停止，也可在设置中管理。

## 构建

使用 JDK 17 或 21、Android SDK Platform 35 和 Build Tools 35.0.0。在 `local.properties` 配置 SDK 路径，或设置 `ANDROID_HOME`。Gradle Wrapper 固定在 8.11.1，项目使用 AGP 8.9.2、Kotlin 2.1.20、Compose BOM 2025.04.01 和 Miuix Android 0.3.8。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\gradlew.bat assembleInstrumented assembleInstrumentedAndroidTest
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 测试与数据

正式应用包名为 `com.huanhren.deltaharmonica`。设备自动化使用独立的 `.instrumented` 宿主和 `.instrumented.test` 测试包，使用隔离目录及无特权输入环境。Gradle 设备任务受保护，指定真机的隔离测试由 `scripts/safe-device-runner.ps1` 执行。

校准坐标以当前方向完整默认显示屏为参考系，坐标范围为 `[0,1]`。草稿必须显式保存才会写入校准布局。演奏使用现有导入事务、曲库、规划器与执行器。应用内进度来自输入派发和回调。

见 [Miuix 翻新验收](docs/miuix-ui-overhaul-final-report.md)、[F1 输入验收](docs/f1-root-acceptance.md) 和 [F2–F4 验收](docs/f2-f4-final-acceptance.md)。
