# Root 连续演奏的真机发声验收

## 生成可复测曲谱

```powershell
.\scripts\generate-root-game-fixtures.ps1 -OutputDirectory .\app\build\root-game-acceptance\fixtures
```

同名 `.mid` 和 `.expected.csv` 分别用于应用正常导入和逐音比对。样本使用单轨 MIDI、4/4、120 BPM、固定时钟和不重叠的原音，预期表的音符序号从 1 开始。以原调导入，检查导入和计划中的音符数及警告。应用的默认口琴音高配置仍需要游戏录音验证。

| 样本 | 总音数 | 1× 原谱时长 | 验证内容 |
| --- | ---: | ---: | --- |
| A-natural-8 | 8 | 3.910 s | 自然音、长短音、普通节奏；在首轮确认收音和音量 |
| B-controls-16 | 16 | 6.470 s | 高、自然、低音区和频繁的半音开关，含双控同时需要改变的转换 |
| C-dense-32 | 32 | 4.135 s | 同键重触发、带半音换区和高频短音；2× 可能超过可执行时序预算，记录计划警告和时值变化 |
| B-full-360 | 360 | 179.780 s | 连续三分钟音区及半音变化，含长短音和停顿 |

## 手机操作与录音

1. 保留已保存的 12 点校准。在正式候选安装前，用已安装版本录基线。新版和旧版使用同一设备、曲谱、游戏场景、帧率、音量、布局、录音方式及速度。记录 APK SHA-256、时序配置、游戏帧率设置和导入后的报告。
2. 进入游戏口琴界面并把音区设为自然、半音关。关闭背景音乐、语音和环境声，保持口琴音量。开始手机系统录屏并选择系统声音，等录制浮层关闭后，先播放 A 样本。结束录屏后听原文件，确认包含清楚的游戏口琴声音。
3. 对四个样本分别录 1× 和 2× 的完整演奏。每段保留开头和结尾约 1 秒安静时间，每轮重新确认起始音区与半音。暂停、继续、停止、失焦和 Root 中断另开测试轮次，记录操作时刻，避免与连续演奏的节奏指标混合。录音干扰判断时，再按同条件重试。
4. 如果系统录屏没有游戏声音，将手机扬声器靠近电脑麦克风，测试音量、失真和环境噪音是否允许辨认快速连续音符。手机可由操作者直接控制，电脑只采音。

提取系统录屏的第一条音频轨或使用 Windows DirectShow 收音，均输出 PCM WAV 和 SHA-256。预先创建录制结果目录，提供已在 PATH 的 FFmpeg 或传 `-Ffmpeg` 指定可执行文件的绝对路径。确认录屏的第一条音轨包含游戏口琴。 [FFmpeg 下载](https://ffmpeg.org/download.html) · [DirectShow 设备选项](https://ffmpeg.org/ffmpeg-devices.html#dshow)

```powershell
.\scripts\capture-root-game-audio.ps1 -Recording .\B-1x.mp4 -OutputWav .\B-1x.wav

ffmpeg -list_devices true -f dshow -i dummy
.\scripts\capture-root-game-audio.ps1 -Microphone '麦克风的完整设备名' -Seconds 20 -OutputWav .\B-1x-mic.wav
```

## 验证游戏控制语义

在停止自动演奏后手动测试。每个步骤单独记录控制按下、释放前的吹奏、释放后的吹奏及音高。每次改变一个条件，重复测试；对不同组合保留录音和对应画面。

| 操作 | 证据 |
| --- | --- |
| 依次从自然切到高、低，用同一键比较基准音、控制按下时的音和控制释放后的音 | 音区是否在 DOWN 或 UP 生效 |
| 关闭半音，按住半音控制点，用第二手指在释放前后各吹一次；再从开启重复 | 半音生效边沿，多次触发或长按重复切换 |
| 分别先切音区、先切半音，再试两个控制点近同时触碰；每个分支都从同一初始状态开始 | 顺序对最终音高的影响 |
| 持续吹奏一个长音，在同一个音键保持时改变音区或半音 | 前一个音是否变音或中断 |
| 同键重复触摸，逐步缩短中间释放间隔 | 最短可辨识的离键及重新起音间隔 |
| 音键分别在控制按下、抬起后不同时距触发，每组记录试次、命中数和实际音高 | 可用的控制键按住时长与稳定时间；需要可计时的输入日志才能据此设定毫秒参数 |

音区和半音切换成功率应按独立试次计数，并逐项记录分母、失败和不确定。若仅能从下一音判断且指法存在跨音区等价形式，保留该状态为待验证。

## 从录音测量逐音结果

在 Audacity 打开 WAV，用波形和频谱图标注可辨识口琴音的开始和结束。始终使用同一套起音与尾音标注标准。同音重触发需要看两段起音包络，合并的同音记录一段并标记粘连。以分段的基频判定音高，避免把泛音记成音高。半音可用 `round(69 + 12 * log2(基频 Hz / 440))` 辅助核对，辨认困难时标为未知。一个错误音高仍按听到的实际音记录。 [Audacity 标注导出格式](https://manual.audacityteam.org/man/importing_and_exporting_labels.html)

- 与预期表配对的发声标签使用 `序号 MIDI音高`，如 `3 63`。
- 听到声音但音高不明时使用 `3 ?`。缺音不创建该序号的标签。
- 额外发声使用 `extra 63`，重复发声使用 `repeat 63`。
- 使用标准格式导出区间标签，两个时间列单位为秒。保留 WAV、标签、源谱 SHA-256、测速结果和对应会话日志。只有已检查整段录音，才用已配对及未配对的区间报告完整的发声和漏音计数。

```powershell
.\scripts\measure-root-game-audio.ps1 `
    -ExpectedCsv .\app\build\root-game-acceptance\fixtures\B-controls-16.expected.csv `
    -AudacityLabels .\B-1x.labels.txt -Speed 1 |
    Set-Content -Encoding UTF8 .\B-1x.audio.json
```

默认用首个已配对的发声对齐音乐时钟，计算相对起音偏差及累计漂移。P95/P99 使用绝对误差的最近秩。只有录音和音乐时钟存在同一测量基准时，才提供 `-TimeZeroMs` 并报告绝对起音。时值偏差和时间轴拉伸应与调度数据对照。总音数、音高正确数、漏音、多发和重发均依据标签。波形中无法区分的事件应另记复核原因。

## 采集 Root 调度与恢复证据

如候选版本提供逐音 `DeltaRootTrace`，在该轮测试前显式启用 DEBUG 标签，并在测试后恢复设备原值。详细日志单独采集，对旧版与新版做性能对比时使用同等日志设置。取设备序列号并用 SDK 的 ADB 精确选择设备。保留会话 id、来源音符索引、原谱相对时刻、helper 的计划时刻及 DOWN/UP、音高和触点状态。

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$serial = '<已核验的设备序列号>'
& $adb -s $serial shell getprop log.tag.DeltaRootTrace
& $adb -s $serial shell setprop log.tag.DeltaRootTrace DEBUG
& $adb -s $serial logcat -v monotonic -s 'DeltaRootTrace:D' 'DeltaRoot:I' 'DeltaPlayback:I' |
    Tee-Object -FilePath .\root-dispatch.log
```

采完用 Ctrl+C 结束电脑端日志，再恢复原日志级别。新 trace 的 `index` 从 0 开始，预期表 `noteIndex` 从 1 开始，整曲从头播放时对应 `index + 1`。对同一会话按 `down - planned` 计算派发延迟，按 `up - down` 计算触控按住时长。结合计划统计及原谱时间区分调度延迟和预先编排的节奏伸缩。注入失败、已停止未播放、释放待确认和目标游戏实际发音分别记录。手机失焦、锁屏、画面重建、播放中断和快速操作要保留终止结果及下一次是否可安全演奏的证据。测试后释放依赖可信的触控确认，不以日志仍在输出作为判断依据。

## 先运行隔离真机测试

使用仓库工具链 JDK 21、Gradle 9.9.1、AGP 9.5.1、Kotlin 2.4.21、SDK 37.2、Build Tools 37.0.0。安全脚本另依赖 SDK Build Tools 35.0.0 的包和签名工具，验证唯一的 Xiaomi API 36 前台用户、固定的 ADB 服务端/客户端、隔离包名及 APK 哈希。项目的 Gradle 真机任务由 `settings.gradle.kts` 拦截。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
if ($LASTEXITCODE -ne 0) { throw 'JVM tests, debug build or lint failed' }
.\gradlew.bat assembleInstrumented assembleInstrumentedAndroidTest
if ($LASTEXITCODE -ne 0) { throw 'Isolated APK build failed' }
$hostHash = (Get-FileHash .\app\build\outputs\apk\instrumented\app-instrumented.apk).Hash
$testHash = (Get-FileHash .\app\build\outputs\apk\androidTest\instrumented\app-instrumented-androidTest.apk).Hash
.\scripts\safe-device-runner.ps1 -HostSha256 $hostHash -TestSha256 $testHash -Execute
```

如 ADB shell 已获得 Root 授权，可在运行时追加 `-RootContinuous`。这个选项增加两项触点用例，检查隔离窗口的 8/16 音、重触发、控制开关顺序、暂停继续和停止释放。辅助进程使用隔离宿主的 APK 和 UID，隔离应用仍保持禁用 Root 授权入口。

保存脚本输出的 `device-commands.jsonl` 证据路径。记录隔离窗口的事件和真实游戏发声时分别保存对应证据。
