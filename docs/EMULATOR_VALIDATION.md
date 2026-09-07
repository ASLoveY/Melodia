# v1.2.1 目录导入验收

日期：2026-09-07。Android 16 / API 36 模拟器，正式签名的 R8 安装包。

- 219 项 JVM 单元测试、26 项设备测试通过，0 失败；Lint 0 错误，77 条警告、18 条提示。
- 真实目录包含根目录及两层子目录：31 秒、45 秒、60 秒和恰好 30 秒的 WAV 均导入，共 4 首；5 秒、29.999 秒的 WAV 被忽略。
- 不同子目录内同名歌曲分别保留；文本文件跳过；损坏 MP3 计入失败。首次结果为新增 4、重复 0、失败 1、短音频忽略 2。
- 重复扫描同一目录，新增 0、重复 4；再通过单文件选择器选择已导入歌曲，新增 0、重复 1。
- 强制退出并重启后曲库仍为 4 首；仅通过目录授权导入的深层歌曲可在飞行模式、Wi-Fi 关闭时播放，MediaSession 显示 PLAYING 且位置达到 24,035 ms。
- 用包含 3,003 个文件的目录验证取消：扫描进度达到 1,598 个文件、待导入 3 首时取消，曲库仍为原来的 4 首，未写入部分结果。
- 本次正式包检查的 crash 日志为空。

目录导入只获取一次持久化树读取权限，递归访问后代文档；按 provider/documentId 识别同一歌曲，避免单文件、父目录、子目录的不同访问 URI 产生重复记录。未知时长或无法读取的音频计为失败，不加入目录导入结果。单独选择音频文件的导入方式保留，不应用目录扫描的时长过滤。

扫描结束后一次性写入曲库；保存阶段禁用取消。再次导入目录可补充新增歌曲，不在后台持续监控目录变化。源文件不会被修改或删除。

本轮构建日志：`build/feature3-validation/folder-import-checks.log`；真实文件、UI XML、媒体状态与取消证据：`build/folder-import-validation/`。

---

# v1.2.0 模拟器验收

日期：2026-09-07。环境为已安装的 Android Studio 2026.1.4、Android 16 / API 36 的 Pixel 7 AVD。

| 检查 | 结果 |
| --- | --- |
| JVM 单元测试 | 213 项通过，0 失败、0 错误 |
| 模拟器 instrumentation | 21 项通过，0 失败、0 跳过 |
| Android Lint | 0 错误，77 条警告、18 条提示 |
| 正式签名混淆包 | versionName `1.2.0`，versionCode `1020099`，沿用 v1.1.1 的发布证书 |
| 安装升级 | v1.1.1 正式包覆盖升级到 v1.2.0 成功，冷启动成功 |
| 本地文件导入 | 系统文件选择器选择两个真实 WAV，导入 2 首、失败 0 首，时长均为 2 分钟 |
| 重复导入 | 再次选择同一文件，新增 0 首、重复 1 首，曲库数量保持不变 |
| 离线播放 | 未登录、飞行模式开启且 Wi-Fi 关闭时，MediaSession 为 PLAYING，位置持续前进 |
| 重启续播 | 暂停在 45,730 ms，强制退出并重新打开后保持暂停；点击播放从 45,730 ms 继续，并前进到 64,240 ms |
| 曲库管理 | 标题搜索、名称排序、下一首均正常；移出后源文件仍为 3,840,044 字节，原队列仍可播放 |
| 崩溃检查 | 本次正式包检查的 crash 日志为空 |

新增设备测试覆盖歌单操作入口、资料编辑及会话持久化、本地音乐入口与列表交互、跨进程媒体元数据 URI 恢复，以及真实 MediaSession/MediaController 从 30 秒位置起播。后者使用生成的 WAV 和生产播放调用顺序，确认播放器实际进入播放状态。

歌单删除、取消收藏及个人资料保存通过可控接口和状态测试；请求通过 Retrofit Tag 绑定账号及会话版本，并从同一份 DataStore 快照取得 Cookie。未对真实账号执行云端删除或资料修改；实体手机蓝牙、来电和厂商后台策略仍不属于本次模拟器验收范围。

本机最终模拟器使用 WHPX 和 host GPU。旧版 ADB 与新 SDK 存在冲突，因此验证使用新 SDK 的独立 ADB 服务端口 `15038`；没有替换系统中的旧 SDK。日志及测试音频保存在被忽略的 `build/feature3-validation/`，报告位于 `build/phase1-verified/app/reports/`。

复现项目检查：

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest lintDebug assembleRelease
```

正式包需配置 `RELEASE_*` 签名环境变量，并传入 `-PreleaseVersionName=1.2.0 -PreleaseVersionCode=1020099`。本机使用的临时隔离输出参数见下文。

---

# v1.1.1 模拟器验收

日期：2026-09-07。

**环境**

- Android Studio 2026.1.4，已安装在 Windows。
- Android Emulator 37.1.11，WHPX 硬件加速。
- AVD：`Melodia_API_36`，Pixel 7，Android 16 / API 36，x86_64 Google APIs。
- 完整 Temurin JDK 21、Android SDK Platform 36.1、Build-Tools 36.0.0。

**检查结果**

| 检查 | 结果 |
| --- | --- |
| JVM 单元测试 | 170 项通过，0 失败、0 错误 |
| 模拟器 instrumentation | 8 项通过，0 失败、0 跳过 |
| Android Lint | 0 错误，77 条警告、18 条提示 |
| 正式签名 release 构建 | 通过，versionName `1.1.1`，versionCode `1010199` |
| APK 签名校验 | `apksigner verify` 通过，签名者 `CN=Melodia Release, O=ASLoveY` |
| 正式包安装与启动 | 模拟器安装成功，冷启动返回 `Status: ok` |
| 正式包真实搜索与播放 | 搜索 `piano` 返回结果，MediaSession 显示 `PLAYING` 且播放位置前进；随后暂停 |
| 崩溃检查 | 本次检查时 crash 日志无崩溃记录 |

8 项 instrumentation 包含 3 项真实应用导航测试、4 项真实 Media3 缓存测试和 1 项包名检查：

- 主页面及底部导航显示。
- 未登录创建歌单 → 登录选择 → 返回原页，并检查未实现的创建入口已隐藏。
- 搜索输入与分类切换。
- 清理已缓存内容后继续缓存、读取。
- 缩小容量时按最近使用顺序驱逐。
- 选中缓存片段后文件消失时执行一次网络回退，字节内容完整。
- 活动写入期间清理缓存，写入完成后仍能读取。

缓存测试使用可控的本地字节数据源，不依赖外部账号或服务；正式包另行完成真实接口搜索和在线播放检查。真实账号的登录、云端写操作，以及实体手机的蓝牙、来电和厂商后台策略不在本次模拟器结果中作通过声明。

**复现命令**

在配置 JDK、SDK 并启动 AVD 后运行：

```powershell
.\gradlew.bat connectedDebugAndroidTest testDebugUnitTest lintDebug --no-daemon --max-workers=2
```

正式包通过 `RELEASE_*` 环境变量读取签名材料：

```powershell
.\gradlew.bat assembleRelease '-PreleaseVersionName=1.1.1' '-PreleaseVersionCode=1010199'
```

本机验证沿用隔离构建目录 `build/phase1-verified/`，日志保存在 `build/emulator-validation/`。首次遇到 Windows 生成目录只读属性引发的增量构建失败，清除生成目录的只读属性后构建成功；没有修改源文件目录的权限。

签名证书 SHA-256：`66d0087136c8a133a81555d402fb62b0934df60f15e038a664f1cabb10e92ae4`。

发布密钥存放在仓库外，密码在本机使用 Windows DPAPI 保护；Actions 使用仓库 Secrets。保留密钥及可恢复的密码备份，以便后续版本沿用同一签名。
