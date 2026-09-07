# 第一批稳定性开发记录

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

后续按用户要求依次实现音乐库删除歌单、个人资料查看与修改、本地音乐导入播放管理，见 [ROADMAP.md](ROADMAP.md)。
