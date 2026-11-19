# 在线曲库验收

验收日期为 2026 年 11 月 19 日。分支为 `feature/online-library`，基线为 `0912b9e48b2c293fc09a99e43034748524b3c829`。本地和远端的 UI 重构基线相同，开发使用独立工作树。

## 功能

| 项目 | 状态 | 验证范围 |
| --- | --- | --- |
| 真实曲库接口 | PASS | 公开 Toy CDN 匿名读取。索引、16 个目录分片和所选详情返回 200。 |
| JSONP 解析 | PASS | JVM 覆盖包裹、对象 key、字段校验和异常截断。 |
| 索引加载 | PASS | 读取真实索引并校验版本、总数、更新时间和分片计数。 |
| 目录加载 | PASS | 在并发上限内读取真实分片，合并得到 663 个唯一记录。 |
| 搜索 | PASS | 自动化覆盖曲名、作者、上传者、标签、排序和分页，真实链路找到《送别（李叔同）口琴二重奏》。 |
| 详情 | PASS | 真实曲目详情通过 ID、字段和载荷校验，隔离界面覆盖选择与返回。 |
| DFHS1 解码 | PASS | Kotlin 与 TypeScript、C# 的两组黄金样本比对。真实详情的 159 音符及另一首 791 音符分别完成解码。 |
| 共用演奏转换 | PASS | MIDI 与 DFHS1 输入的节奏及映射对比。验证大移调、无音符输出和独立转换容量。同一曲目可从离线原始数据按当前选项重新转换。 |
| 本地下载 | PASS | 落盘回读、SHA-256、事务取消、写入失败和损坏恢复。隔离设备验证真实曲谱落盘与加载。 |
| 重复下载保护 | PASS | 并发下载、远端 ID、同内容别名和新版副本，同时保留本地名称。 |
| Miuix 在线页 | PASS | Android 16 隔离设备验证入口、返回、搜索、详情、演奏选项、下载进度、失败重试、完成状态和缓存读路径。 |
| 离线重载与调度 | PASS | 隔离设备清理在线缓存后，以不可达的曲库地址重新打开已下载曲谱，校验播放规划、首音回调和停止。 |
| 异常恢复 | PASS | 自动化覆盖 404、429、5xx、断网模拟、超时、取消、损坏缓存、事务回滚、已发布记录恢复和本地元数据损坏。 |
| 游戏内真实发声与离线实奏 | NOT TESTED | 隔离测试使用记录派发结果的输入实现。需在正式应用与游戏中验证 Root 或无障碍触摸、可听音高和物理断网后的实奏。 |
| 核心流程整体验收 | PARTIAL | 公开曲库读取至调度器闭环已验证。正式安装上的游戏内实奏仍待验收。 |

## 测试结果

| 任务 | 总数 | 新增 | 通过 | 失败 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `testDebugUnitTest` | 278 | 39 | 278 | 0 | 0 |
| `testInstrumentedUnitTest` | 278 | 39 | 278 | 0 | 0 |
| `testReleaseUnitTest` | 278 | 39 | 278 | 0 | 0 |
| Android 16 小米隔离设备套件（含在线检查） | 33 | 5 | 33 | 0 | 0 |
| Gradle 设备任务保护离线检查 | 2 | 0 | 2 | 0 | 0 |

`lintDebug` 通过，报告包含 0 个错误、67 个警告和 7 个提示。在线曲库新增文件没有 lint 报告项。Debug APK 和隔离宿主、测试 APK 均构建通过。隔离运行核验了两个测试包的标识和摘要，正式安装在测试前后均存在。

本轮联调修正了关联工作树上的 Windows Java 命令长度问题，并在测试中区分首音开始与完成回调。真实曲谱《送别（李叔同）口琴二重奏》为 80 BPM、159 音符，生成 159 个有效演奏事件。另一首公开曲谱在宿主 JVM 生成 790 个事件，保留了一个未映射音符的警告。

## 关键文件

| 新增或修改 | 职责 |
| --- | --- |
| `online/OnlineLibraryClient.kt`、`OnlineLibraryProtocol.kt`、`JsonpParser.kt` | 原生 HTTPS、请求控制、JSONP 装载和协议校验 |
| `online/OnlineLibraryRepository.kt`、`OnlineLibraryCache.kt`、`OnlineLibraryModels.kt` | 索引、目录、详情、过期回退和本地搜索 |
| `online/DfhsDecoder.kt`、`OnlineSongConverter.kt` | 有界解码、时间换算和共享转换入口 |
| `midi/ScriptConverter.kt`、`midi/Conversion.kt` | MIDI 与 DFHS1 共用转换和脚本校验 |
| `data/SongLibraryRepository.kt` | 在线来源、事务写入、回读校验、版本和别名 |
| `ui/OnlineLibraryScreen.kt`、`LibraryScreen.kt`、`AppNavigation.kt` | Miuix 在线入口、目录、详情与演奏选项 |
| `ui/AppViewModel.kt`、`PlaybackScreenState.kt` | 互斥下载、曲目激活和离线重转换 |
| `app/src/test`、`app/src/androidTest`、`scripts/safe-device-runner.ps1` | 自动化和隔离真机验收 |

本轮新增和修改文件，删除文件数为 0。

## 仍待验证

- 新桌面 API 需要有效会话。当前可用的数据来自旧的公开 CDN 快照，是否持续同步由上游决定，榜单热度字段也只见于新版桌面说明。
- 旧 App 读取导出的 JSON 脚本时，支持的总移调范围为 ±24。此分支可保存在线曲谱最多 ±72 的总移调。
- 尚未确认生产环境的游戏音高、实体手势、物理关闭无线网络后的游戏内续播，以及大曲目在游戏中的完整调度和停止。隔离测试验证了受控的网络失败、离线重载、界面交互和首音后停止。
