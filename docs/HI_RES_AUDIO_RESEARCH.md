# Android Hi-Res 输出调研与后续方向

调研日期：2026-09-08 至 2026-09-09。项目基线：v1.6.0、Media3 1.6.0、minSdk 26、targetSdk 36。本文是后续开发依据，v1.6.1 只修复背景显示，不新增 Hi-Res 输出能力。

## 结论

优先实现 **Android 14+ 的官方 USB DAC 输出路径**，先做输出状态诊断，再保持解码到 AudioTrack 的高精度 PCM，最后在设备明确支持时启用 bit-perfect。Android 16 可以沿用这套 API；系统版本较新本身不能证明手机、DAC 或当前路由具备无重采样输出能力。官方说明允许通过 USB 首选混音属性协商格式，而 bit-perfect 属于厂商可选实现。[Android 播放指南](https://developer.android.com/media/platform/improve-audio-playback)、[AOSP USB 混音属性](https://source.android.com/docs/core/audio/preferred-mixer-attr)

需要区分三件事：音源是高解析文件、应用输出高精度 PCM、DAC 最终收到逐位一致的数据。音源菜单中的“Hi-Res”、AudioTrack 的请求采样率或 DAC 单独显示“192 kHz”，都不足以独立证明最后一项。这是本项目后续验收和状态展示的边界。

## 近期 Android 版本

| 版本 | 已核实的能力或限制 | 项目应对 |
| --- | --- | --- |
| Android 14 / API 34 | 引入 AudioMixerAttributes，可查询 USB 混音格式、采样率、声道及 DEFAULT / BIT_PERFECT 行为；bit-perfect 不是所有设备的必选能力。 | 作为官方 USB Hi-Res 路线的最低版本，运行时查询，不能按版本直接亮起支持标记。 |
| Android 15 / API 35 | target 35+ 请求音频焦点时，应用需位于前台或运行前台服务。 | 覆盖锁屏恢复、媒体通知启动与 DAC 插拔后的焦点恢复。 |
| Android 16 / API 36 | AudioRouting 增加 getRoutedDevices()，可检查实际正在播放的路由列表。MediaQuality 新能力面向电视音画配置，不应当作手机通用 Hi-Res 开关。 | 优先验收版本；使用实际路由信息，USB 格式协商仍用 API 34 的接口。 |
| Android 17 / API 37 | 已正式发布；后台音频强化进一步约束播放、焦点和音量操作，WIU 条件需按 targetSdk 等规则处理。 | 纳入后续兼容矩阵，升级 target 37 前专门验证前台服务启动、通知恢复和后台输出，不能仅复用 Android 16 的通过结论。 |

版本依据：[API 34 混音属性](https://developer.android.com/reference/android/media/AudioMixerAttributes)、[Android 15 音频焦点](https://developer.android.com/about/versions/15/behavior-changes-15#media)、[AudioRouting API 36](https://developer.android.com/reference/android/media/AudioRouting#getRoutedDevices())、[Android 16 电视 MediaQuality](https://developer.android.com/about/versions/16/features#media-quality)、[Android 17 正式发布公告](https://android-developers.googleblog.com/2026/06/Android-17.html)、[Android 17 后台音频强化](https://developer.android.com/about/versions/17/changes/bg-audio)。

## 官方 USB 路线

1. 监听输出设备变化，识别 USB DAC；查询 `getSupportedMixerAttributes(device)`，从返回结果选择与音源匹配的编码、采样率和声道组合。
2. 使用 `USAGE_MEDIA`，声明普通权限 `MODIFY_AUDIO_SETTINGS`，调用 `setPreferredMixerAttributes()` 并检查返回值。AudioTrack 必须使用相同的属性和音频格式。
3. 监听首选混音属性及实际路由变化；退出该模式、DAC 拔出、播放失败和服务销毁时调用 `clearPreferredMixerAttributes()` 并释放监听。
4. 协商失败、未返回目标格式或路由改变时明确回退普通输出，不保留“Hi-Res 已启用”的过期状态。

这些接口目前限定 USB / USAGE_MEDIA；设置是以 UID 识别的偏好，并不意味着任何内部扬声器或蓝牙路由都可被强制切换。接口契约及权限见 [AudioManager](https://developer.android.com/reference/android/media/AudioManager#setPreferredMixerAttributes(android.media.AudioAttributes,%20android.media.AudioDeviceInfo,%20android.media.AudioMixerAttributes))。实际路由与首选路由需分开判断，且路由查询应在播放期间进行。[AudioRouting](https://developer.android.com/reference/android/media/AudioRouting)

不优先采用自写 USB 驱动或绕过系统的独占方案。官方指出直接访问 USB 音频外设可能影响其他应用及提示音；若以后确需此路线，应作为独立项目验证设备访问、系统声音和生命周期。[官方 USB 播放建议](https://developer.android.com/media/platform/improve-audio-playback)

AAudio / Oboe 可作为以后重做原生音频引擎的备选，但不是当前首选：AAudio 主要面向低延迟，不负责音频文件解码；独占请求也可能按设备能力回退，打开流后仍需读取实际配置。因此不能把“使用 AAudio”当作 bit-perfect 证明；现有 Media3 项目优先扩展 AudioSink，减少同时重写解码、缓存和媒体会话的范围。[AAudio 官方说明](https://developer.android.com/ndk/guides/audio/aaudio/aaudio)

## 当前代码的限制与改造方向

- `CrossfadePlayer` 创建音频输出时固定 `setEnableFloatOutput(false)`，并为两个播放器安装 `LoudnessAudioProcessor`；后者只接收 PCM 16-bit。这意味着当前不能把音源的“hires”请求等同于端到端高精度输出，单独关闭均衡也不会自动改成高精度解码路径。
- Media3 1.6.0 的浮点输出选项可用于高精度 PCM，但启用该路径时不能直接沿用其整数音频处理链。后续需验证解码器真实输出精度，不能先降到 16-bit，再升成 float 并称为 Hi-Res。[当前依赖版本的源码说明](https://raw.githubusercontent.com/androidx/media/1.6.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java)
- 建议保留“普通播放”模式，新增独立“高精度输出”模式。第一阶段停用交叉淡化、响度均衡、限幅和应用增益，避免这些处理改写 PCM；退出后恢复用户原有的效果偏好。需要 DSP 与 Hi-Res 共存时，再单独实现和验证高精度处理链。
- USB bit-perfect 必须严格匹配设备报告的格式。仅开启 float 输出不保证能匹配 DAC 的 24-bit packed 或 32-bit integer 格式；必要时提供独立 AudioSink / AudioTrack 适配。32-bit integer 转 float 也不能承诺逐位保留全部整数位。
- 不强制把 44.1/48 kHz 音源升采样到 192 kHz。优先原采样率；不支持时展示实际回退格式。bit-perfect 需关闭改变采样的效果、重叠混音、软件音量和变速，系统硬件支持仍须查询。[bit-perfect 行为定义](https://developer.android.com/reference/android/media/AudioMixerAttributes#MIXER_BEHAVIOR_BIT_PERFECT)
- 内置扬声器、模拟耳机口和蓝牙作为独立能力分支处理。蓝牙编解码器由设备及连接协商，LDAC 等编码能力不是 USB bit-perfect 的等价证据，不通过开发者选项或隐藏 API 强制设置。[AOSP 蓝牙服务](https://source.android.com/docs/core/connect/bluetooth/services)

## 后续开发顺序与验收门槛

1. **输出诊断**：展示音源参数、解码 PCM 参数、AudioTrack 配置、实际路由及混音协商状态；无法获得的硬件信息显示未知。补充设备/路由变化监听。
2. **高精度 PCM 路径**：在保留普通模式的前提下，验证 24-bit 96/192 kHz 的本地 FLAC/WAV；音源、解码器与输出端分别核对，排除中途 16-bit 截断。
3. **USB 首选混音与 bit-perfect**：实现 API 34+ 协商、模式互斥、资源清理和失败回退；是否升级 Media3 或增加自定义 AudioSink，以格式匹配原型结果决定，独立提交验证。
4. **实体设备验收**：至少覆盖 Android 14/15/16/17、不同厂商手机、两款可显示输入采样率的 USB DAC，以及不支持目标格式的设备。测试 44.1/48/96/192 kHz、24-bit 低位信号、暂停恢复、通知音/来电、插拔、切路由和后台播放。

DAC 显示用于核对采样率；bit-perfect 的强证明需要数字回录或可比对的端点数据，验证 PCM 低位和逐样本一致性。当前用于交叉淡化的 48 kHz Android Playback Capture 不能作为 USB 24-bit/192 kHz 或 bit-perfect 的验收证据。没有硬件测量时，只报告应用配置与协商结果，保留“硬件未验证”状态。

本轮未连接 USB DAC，也未在 Android 14/15/17 实体设备上验证；以上版本和 API 结论来自官方文档，输出模式的实现方案是基于项目现状提出的后续方向。
