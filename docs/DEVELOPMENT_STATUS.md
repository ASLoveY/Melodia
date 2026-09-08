# 第一批稳定性开发记录

## v1.6.0 音频效果与应用背景

- `CrossfadePlayer` 基于 Media3 1.6.0 的 SimpleBasePlayer，对外维持一个媒体会话，内部两个 ExoPlayer 分别负责当前歌曲和静音预备。默认自然切歌交叉淡化 3 秒，时长可调 1–12 秒；预备不足、未知时长、单曲循环和失效队列回退普通播放。
- 队列版本与歌曲标识共同校验准备和交接；暂停冻结两路，手动切歌、拖动、停止、模式变化取消预备。当前歌曲到结尾时才交接，下一首保留已经播放的位置。音频焦点、耳机拔出与输出路由统一作用于两路。
- PCM 音频处理采用 K 加权及绝对/相对门控测量整体响度，目标 -16 LUFS、增益 -24 至 +6 dB。首次播放至少积累 3 秒有效音频，每秒增益变化不超过 0.5 dB；4 倍重建峰值估算和保守余量保护 -1 dBTP 上限。关闭均衡后逐字节透传。
- 仅完整连续播放可保存响度档案；跳播、停止或中途切换均衡不会产生完整档案。在线档案按文件 MD5、码率和大小区分音源，本地按 URI、大小和修改时间区分；缺失可靠指纹时只在本次播放估算。
- 背景图通过系统选择器导入，修正 EXIF 方向并限制最长边 2048 像素，保存内部副本。透明度默认 70%，即时预览、结束拖动后持久化；更换失败保留旧图，恢复默认不修改源图。常规页面共用背景与可读性衬底，播放器、MV 和弹窗保留原有背景。
- 音频输出验证工具仅存在于 debug 源集，采集限定为本应用 UID；正式包不包含采集工具或录音权限。

## v1.5.0 搜索歌曲操作

- 搜索单曲支持长按及右侧更多按钮。菜单按队列、收藏/下载、查看/分享排列，适配深浅主题；下一首与队列末尾分别插入，保留当前播放进度。空队列只准备歌曲，点击播放后才开始播放。
- 添加到歌单展示当前账号的自建普通歌单，也可新建后添加。加载失败可重试、提交时防重复；新建成功但加歌失败时保留目标供重试。所有请求绑定打开选择页时的账号和会话版本。
- 多歌手歌曲提供歌手选择；缺少有效歌手或专辑 ID 时禁用对应导航。分享使用 Android 系统分享面板。
- 登录后请求服务端下载链接，拒绝试听、空链接或不匹配的歌曲。下载独立于播放缓存，不向 CDN 发送账号 Cookie，使用 HTTPS，并检查长度、服务端提供的 MD5 和音频时长。
- 下载是可取消的前台操作，失败可重试；校验完成后自动注册到本地音乐。Android 10 及以上保存到 `Music/Melodia`，Android 8/9 保存到应用音乐目录；不申请额外的全盘存储权限。重复下载复用已有文件；本地移除操作仍保留源文件。

验证范围见 [EMULATOR_VALIDATION.md](EMULATOR_VALIDATION.md)。

日期：2026-09-07。起点：`6287799`。分支：`codex/stability-phase1`。

本批落实开发计划中的登录返回、搜索状态与缓存管理修复。播放错误分类、云盘任务持久化、更新完整性校验仍按后续阶段推进。

**实现范围**

- 未登录创建歌单时接通授权流程，成功同步账号后直接打开创建表单；取消和重复、迟到回调不会重复触发创建。资料同步失败或取消时条件回滚 Cookie，旧请求不能覆盖新会话。
- 搜索关键词变更时失效所有分类缓存；取消请求恢复可重试状态；分页失败保留已加载结果，迟到结果不会覆盖当前查询。
- 保留播放器持有的音频缓存实例，通过缓存接口清理已提交内容和调整容量；后台执行清理，操作失败准确反馈，保留日志及 WebView 等活动目录。若清理与读取文件产生竞态，从已返回的字节偏移执行一次网络回退。
- 收起账号、消息、隐私、共建歌单、共享合辑及下载目录的占位入口；播放缓存入口名称与当前能力一致。
- 增加登录交接、搜索请求状态和音频缓存策略的回归测试。
- CI 增加 Android Lint 及报告上传；修复首次 lint 发现的长按配置读取和首页缩进错误，校准 README 对 R8 构建验证范围的说明。

**本机构建环境**

首次验证发现本机没有完整 Android SDK，现有 IDE 附带的 Java 21 运行时缺少 Android 构建需要的 `jlink`。本次安装完整 Temurin JDK 21 和必要 SDK 组件，通过仓库外工具目录和被忽略的 `local.properties` 配置；没有把机器路径写入项目构建脚本。

首次 Kotlin debug 全量编译还出现 `GC overhead limit exceeded`，因此在 `gradle.properties` 为 Kotlin 编译守护进程设置 3 GB 堆空间。

在 PowerShell 中设置 `JAVA_HOME` 指向完整 JDK 21，并通过 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` 指向 SDK 后，执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleRelease --no-daemon --max-workers=2
```

本轮验收构建成功，结果如下：

| 检查 | 结果 |
| --- | --- |
| 单元测试 | 16 个测试类、170 项测试通过，0 失败、0 错误 |
| Android Lint | 0 错误、77 条警告、18 条提示；警告未设为忽略或加入基线 |
| Release 构建 | `assembleRelease` 成功，包含 R8 混淆 |
| APK 签名 | `apksigner verify` 通过，证书为 Android Debug，本包用于测试 |
| 补丁格式 | `git diff --check` 通过 |

首批本地日志：`build/baseline/phase1-acceptance.log`。后续已补齐模拟器和正式签名包验收，详见 [EMULATOR_VALIDATION.md](EMULATOR_VALIDATION.md)。

本机旧 debug 增量编译目录出现文件占用，因此本次验证通过临时 Gradle init script 将构建输出切换到 `build/phase1-verified/`，没有删除原有缓存。实际验证命令额外使用：

```text
-I build/baseline/isolated-output.gradle --project-cache-dir build/phase1-verified/project-cache
```

该临时脚本仅设置各项目的 `layout.buildDirectory`；正常的新检出目录和 CI 使用默认输出路径。

**本次产物**

- APK：`build/phase1-verified/app/outputs/apk/release/Melodia-v1.0.0-release.apk`。
- 单元测试报告：`build/phase1-verified/app/reports/tests/testDebugUnitTest/index.html`。
- Lint 报告：`build/phase1-verified/app/reports/lint-results-debug.html`。
- 混淆映射：`build/phase1-verified/app/outputs/mapping/release/mapping.txt`。

**设备验收进展**

已安装 Android Studio 并创建 Android 16 AVD：8 项设备测试通过，正式签名的混淆包已验证安装、启动、真实搜索和在线播放。真实账号的资料同步及跨页面一致性、实体手机的蓝牙和后台场景继续列为后续验证项。

后续功能按用户要求依次开发，详情见 [ROADMAP.md](ROADMAP.md)：音乐库删除歌单、个人资料查看与修改、本地音乐导入播放管理。

## v1.2.0 功能实现

- 音乐库支持删除自建歌单、取消收藏他人歌单，保护特殊歌单；二次确认、失败重试和会话校验防止重复或过期操作。
- 侧边栏打开个人资料页，查看头像和账号，编辑昵称、签名；保存时保留生日、性别及地区等未编辑字段，并校验当前账号及会话版本。
- 本地音乐通过系统文件选择器导入并保留读取权限，支持去重、搜索、排序、离线播放和移出曲库。移出只修改库记录，保留源文件及当前播放队列。
- 播放队列以 URI 区分本地文件，绕过网络音质、歌词、评论和移动网络限制。本地播放状态保留 URI 与暂停位置，冷启动保持暂停，显式播放时恢复位置。

实际验收结果与服务端操作的验证范围见 [EMULATOR_VALIDATION.md](EMULATOR_VALIDATION.md)。
