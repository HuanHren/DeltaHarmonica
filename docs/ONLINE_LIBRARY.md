# 在线曲库

## 上游与访问范围

2026 年 11 月 19 日核对的公开源码为 [Dr-hydra/Delta-Force-Harmonica 87447ee](https://github.com/Dr-hydra/Delta-Force-Harmonica/tree/87447eec77bcc7318f0cf96f003d01ae21e1849c)，最新桌面发布为 [v0.2.5](https://github.com/Dr-hydra/Delta-Force-Harmonica/releases/tag/desktop-v0.2.5)。独立 GitHub Pages 构建的站点资源未配置曲库地址。公开 [Toy 构建配置](https://github.com/Dr-hydra/Delta-Force-Harmonica/blob/87447eec77bcc7318f0cf96f003d01ae21e1849c/.github/workflows/toy-package.yml) 明确声明了下面的匿名 CloudBase 对象目录。新版桌面 API 的健康检查返回 401，发布说明也提到安装凭据和会话迁移。

[`index.js`](https://656e-endfield-d3gdy9wg4afba9d16-1474357318.tcb.qcloud.la/harmonica/index.js) 和 16 个目录分片都返回 HTTP 200，共 663 个唯一 ID。从分片 `0` 到 `f` 的条数依次为 36、39、36、43、31、46、37、31、41、49、59、28、58、46、46、37。索引的更新时间为 2026 年 9 月 17 日 00:07:44（北京时间）。按需读取的详情同样返回 HTTP 200。该入口能访问的是 Toy 公开目录快照，与新版桌面曲库的同步关系未获上游确认。

公开源码仓库根目录没有声明代码许可。Android 实现依据可观察的读协议独立编写，黄金样本使用合成曲谱。曲谱保留原作者与上传者信息。应用只读取列出的公开目录及用户选择的详情，缓存于应用私有目录。

## 接口协议

| 对象 | 响应数据 |
| --- | --- |
| `index.js` | `v,n,t,s`，即版本、总数、更新时间和按十六进制字符分组的分片条数 |
| `catalog/{0..f}.js` | `r` 数组。行依次是 ID、标题、作者、上传者、头像、标签、难度、BPM、时长、音符数和更新时间 |
| `score/{id}.js` | `i,t,c,u,a,g,d,b,l,n,m` 为同类元数据，`p` 为无填充 Base64URL 谱面 |

`OnlineLibraryClient` 通过 HTTPS 读取 `__dfh("对象 key", JSON);`。JSONP 解析校验包装、key、响应大小与 UTF-8，并交给 JSON 解析器处理对象，响应正文始终作为数据。协议层独立校验 ID、字段、数量和时间。目录只拉取非空分片，四个请求并发。各阶段设有超时，失败最长重试三次请求，支持可接受的 `Retry-After`，超出等待上限时直接显示限流。404 会使失效详情离开在线缓存。最后一个请求订阅者离开后取消未完成的网络任务。

## DFHS1 与共用转换

`DfhsDecoder` 按上游 TypeScript、C# 两种读取实现核对二进制布局。Raw Deflate 解压后，按 `DFHS`、版本 1、PPQ、ZigZag 移调、速度表、拍号、小节和音符的顺序读取。速度、拍号和小节的起点以及音符起点使用 delta tick。速度以 BPM×100 保存，时值使用 tick，音高使用单字节 MIDI Pitch。解码结果保留原始 tick 和起止时间派生数据。压缩数据限制为 192 KiB，解压限制为 8 MiB，最多解码 20 万音符。转换单独限制输入和输出为 10 万。

上游网页发布时保存的是指法优化前的音高和独立移调值。网页与桌面用快照移调生成指法，标准 MIDI 导出的音符已含移调。Android 将快照移调和当前本地导入参数中的用户移调相加，交由共用 `ScriptConverter` 应用一次。新脚本的总移调可达 ±72，旧版本 App 的 JSON 读取器只支持 ±24。在线曲谱使用单轨和解码时间，复用 `Monophony`、`PitchMapper`、当前配置和键位布局，沿用 `ScriptCodec` 和播放器的映射校验。无法映射的音符逐个报告。无有效音符、数量超限或速度超出脚本字段容量时显示错误。

## 保存、缓存与播放

- `SongLibraryRepository` 通过既有导入日志提交 `original`（DFHS1 压缩字节）、`script.json` 和 `metadata.json`，读取校验两种 SHA-256 和脚本结构。本地条目使用独立 UUID，在线来源字段保存上游 ID、版本、曲谱统计和移调。旧 MIDI、JSON 条目可继续读取。
- 同一远端 ID 优先使用有效本地副本。相同内容的不同 ID 可关联同一副本。主动更新时，内容变化会保存新副本，旧副本及用户更改过的名称仍可在本地曲库使用。无效的本地文件会显示错误，并可通过在线下载取得新副本。
- HTTP 响应观察到 `Cache-Control: public, max-age=300, s-maxage=600`。应用端目录有效期至多 60 秒，详情至多 300 秒，同时遵守更短的响应有效期。完整目录快照和详情使用校验和及临时文件原子替换，支持手动刷新和过期回退。缓存目录与持久化歌曲分开。
- 详情按选择读取，可查看和调整单音化和附加移调。首次下载按所选参数保存。已下载曲目使用当前演奏参数调整或布局指纹变化时，从本地 DFHS1 在内存重新转换，保留已保存的文件和设置。进入播放器后仍需满足原有校准、校验及启动确认。切换曲目、并发导入和播放期间的保护采用现有工作区与播放器状态。
- 上游公开快照没有播放量或热度字段。界面提供按最新、标题和难度排序，支持标签和每批 30 条的增量列表。

## 验证方法

默认 JVM 测试使用本地 HTTP 服务器、自建数据和 TypeScript/C# 黄金样本。隔离设备测试通过可控的目录与详情验证 UI 状态。可选的真机连通性检查使用真实公开目录和单曲详情，以隔离宿主的录制派发器检查本地重载、调度开始和停止。

```powershell
.\gradlew.bat testDebugUnitTest testInstrumentedUnitTest testReleaseUnitTest lintDebug assembleDebug
.\gradlew.bat assembleInstrumented assembleInstrumentedAndroidTest
```

使用 `scripts/safe-device-runner.ps1` 时提供本次构建的隔离宿主和测试 APK 的实际 SHA-256。传入 `-Execute` 运行设备用例，追加 `-LiveOnlineLibrary` 才运行依赖上游的真机检查。真机验证结果见同分支的 [验收报告](online-library-acceptance.md)。
