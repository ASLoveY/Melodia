# v1.7.0-beta.1：LDAC 实验与每日推荐验证

本文保留 beta.1 的实现与验证记录。beta.3 已增加原生直出路径并完成指定真机的 DIRECT / 96 kHz 验证，最新结论见 [原生直出修复记录](NATIVE_DIRECT_OUTPUT.md)。

状态：本地实现、自动化验证及真实账户空日推/歌单/在线播放验证完成，按 GitHub prerelease 发布。真实账号当前日推返回空列表，非空日推播放与实体 LDAC 链路尚未验证。稳定版 v1.6.1 不被替换为 beta。

## 本轮改动

- 背景全屏衬底降为 35%，保留强化文字配色和实体控件底色。复杂图片上的文字可读性可通过现有图片透明度继续调节；不再声称 35% 衬底对任意背景都能达到相同对比度。
- “设置 → 播放与缓存”新增 LDAC 高精度实验开关，默认关闭。播放器在 AudioTrack 的实际路由为单个蓝牙 A2DP 输出时使用独立的浮点音频路径，不经过应用的 16-bit 均衡处理链；16-bit 音源不会因此被标为 24-bit 音源。
- 高精度路径暂时停用交叉淡化、响度均衡和应用限幅，保留用户设置，退出后恢复普通路径。切换保留当前歌曲、位置及暂停状态；不支持的输出回退普通播放，同一首歌曲不反复重试失败路径。
- 分别展示解码 PCM、AudioTrack 配置、实际路由和系统编码器报告。公开 SDK 没有普通应用可用的 `getCodecStatus` / `setCodecConfigPreference` 调用，本版不通过反射强制设置 LDAC。
- Android 13+ 可尝试读取系统的编码配置变化广播；仅接收持有系统蓝牙特权的发送方，且报告的设备必须与实际 A2DP 路由匹配。未收到、权限不足、旧系统或厂商不提供事件时显示“未确认”。已连接但应用启动前未收到事件时，可重新连接耳机后再播放。
- 每日推荐请求改用 WEAPI v3 路径，兼容现代 `data.dailySongs` 和旧式 `recommend` 数据；仅在成功但缺失列表时回退一次 WEAPI v1 日推。两次请求绑定同一账号会话，保留歌曲时长。未登录显示登录入口，重新登录同一账号后也会重新加载；有效空列表显示“暂时没有每日推荐”和刷新入口。
- 原来的“操作失败，请重试”会丢弃业务代码；每日推荐现在显示具体服务代码和可用错误信息，便于区分接口异常和身份问题。

## 真实 LDAC 的边界与操作

手机系统和耳机决定 Bluetooth codec、采样率与链路码率。应用无法承诺或锁定 990 kbps，AudioTrack 为 96 kHz/float 也不能证明系统没有重采样、无线链路为 LDAC 或最终输出无损。实验模式只有在设备能力和系统设置配合时，才可能改善从应用到 LDAC 编码前的精度。

对于 WF-1000XM5，可按 Sony 官方说明在 Sound Connect 中选择优先音质，并在手机蓝牙设置检查 LDAC/高清音频；连接类型和最终参数以系统实际协商为准。[Sony 支持的编解码器说明](https://helpguide.sony.net/mdr/2963/v1/zh-cn/contents/TP1000781808.html)

接口依据：[BluetoothA2dp 公开 SDK](https://developer.android.com/reference/android/bluetooth/BluetoothA2dp)、[AOSP 系统接口和编码变化广播](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothA2dp.java)、[BluetoothCodecStatus](https://developer.android.com/reference/android/bluetooth/BluetoothCodecStatus)。编码广播属于尽力兼容路径，不保证所有厂商都会向普通应用发送。

## 已完成与待完成的验证

- 258 项 JVM 单元测试通过，Lint 0 错误、83 警告、18 提示；日志 `build/feature3-validation/daily-real-account-fix.log`。64 项 API 36 模拟器测试在真实登录前通过（`ldac-beta-full.log`）；日推回退补充改动通过新增 JVM 测试、设备测试源码编译和真实账户 UI 验证，未重新安装 instrumentation，以保留真实登录数据。
- 24-bit / 96 kHz WAV 实测进入 Float32 / 96 kHz AudioTrack；切换模式保留暂停与位置，失败后回退正常播放。该测试注入 A2DP 资格条件以在无耳机的模拟器运行，不能当作无线 LDAC 实机验证。
- 测试验证解码/输出参数不能冒充编码器确认、不同设备的旧报告不匹配、每日推荐现代/旧响应、空列表/缺失列表、未登录状态和会话绑定。
- 2026-09-09 用户在官方网页登录完成真实登录。正式签名 beta 采用覆盖安装，账号歌单/收藏专辑可读取；“我喜欢的音乐”歌曲可在线播放，MediaSession 播放位置从 6,666 ms 前进至 15,474 ms，暂停后为 18,552 ms，无播放错误和崩溃。
- 真实日推复现证据（仅字段形状，无用户数据）：v3 返回 `{"code":200,"data":null}`；带 `afresh=false` 的 v3 仍相同，WEAPI v1 和旧日推路由返回 `{"code":200,"recommend":[]}`，EAPI v3 仍为空。修复后空状态、刷新、返回重进通过。不能把该结果记为“真实账号已加载非空日推并播放”；具体服务端空数据原因及官方 App 对照结果尚未确认。
- 诊断期间仅记录响应字段和数组长度，临时诊断/探测代码已移除。未读取或发布密码、验证码和 Cookie；未执行云端歌单删除、收藏写入或资料修改。同账号重新登录刷新已补充处理代码，本轮没有再退出真实账号，尚未实际复测该场景。
- 在模拟器扬声器路由打开实验开关，界面保持“等待蓝牙 A2DP 播放路由”，不会误报 LDAC；普通歌曲显示 44.1 kHz / 16-bit PCM。冷启动后账号和实验开关状态保留，验证后恢复默认关闭。
- 待实际手机和耳机：核对系统 LDAC、源音质、实际路由、24-bit/96 kHz 音源、暂停/恢复、切回普通模式、断连及 AAC/LE Audio 回退。普通模拟器没有完成该链路的条件。

发布须使用 `--prerelease --latest=false`，保留稳定版 latest。beta 更新只进入用户主动开启的预发布渠道。
