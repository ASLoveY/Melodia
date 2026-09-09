# v1.7.0-beta.3：修复 LDAC 播放经过 48 kHz 混音的问题

在 OnePlus 13s（CPH2723，Android 16 / API 36）与 WF-1000XM5 上，beta.2 的应用 AudioTrack 和蓝牙配置均显示 96 kHz，但本应用的活动音轨实际进入了 48 kHz AudioFlinger MIXER。beta.3 改用系统原生音频引擎，在同一设备、耳机和在线曲目上验证进入 DIRECT / 96 kHz 输出，绕过了此前观察到的 48 kHz 框架混音。

## 实现

- 普通模式仍使用原有双 ExoPlayer 交叉淡化和 PCM 响度处理。实验模式确认当前为单个 A2DP 路由后，切换至 `NativePrecisionPlayer`，通过 `SimpleBasePlayer` 接回现有播放门面；队列和通知继续由同一个 PlayerManager / MediaSession 管理。
- 原生引擎使用公开的 `MediaPlayer` / `MediaDataSource` API。`CachedMediaDataSource` 将随机读取接到已有 Media3 DataSource.Factory，保留流缓存、缓存键、网络配置和本地 content/file URI 支持；不让原生引擎另外直接请求网络链接。
- 原生操作和源文件准备在独立工作线程执行。歌曲版本和拖动版本丢弃过期回调，连续拖动只启动最后一次定位，重复 prepare 不重建已准备的音轨。暂停、音量、单曲循环、自然结束、音频焦点和耳机拔出继续受播放服务控制。
- 切换保留歌曲及位置；原生解码失败时回退到普通 ExoPlayer 路径，同一首失败歌曲不会持续重试原生模式。交叉淡化和响度均衡的用户设置保留，退出实验模式后恢复。
- 为原生后台播放使用 WAKE_LOCK，交由 MediaPlayer 在播放、暂停和释放时管理。没有修改系统音频策略、蓝牙码率或源文件，也不需要 root。

## 真机证据（2026-09-09）

| 环节 | beta.2 | beta.3 |
| --- | --- | --- |
| 本应用所在系统线程 | MIXER | DIRECT |
| 线程采样率 | 48,000 Hz | 96,000 Hz |
| HAL / processing 格式 | PCM 24-bit packed | PCM float |
| 路由 | Bluetooth A2DP / WF-1000XM5 | Bluetooth A2DP / WF-1000XM5 |
| 蓝牙配置 | LDAC / 96 kHz | LDAC / 96 kHz |

取证通过匹配本应用 UID、当前 active 音轨、音频会话及所在输出线程；没有将其他应用、能力列表或历史连接记录当作当前路径。一次在线播放样本的线程为 `AudioOut_E0D`，类型 `DIRECT`，采样率 96000 Hz，HAL / processing 均为 PCM_FLOAT，输出设备为 A2DP。最后一版代码另完成 3 次连续真机采样，均为单个 DIRECT / 96000 Hz / PCM_FLOAT / A2DP 活动输出。

真实在线曲目的播放、暂停和进度保持通过；最终构建熄屏后从 218,908 ms 继续播放，暂停于 229,074 ms，3 秒后位置不变。原生路径还通过独立的 24-bit / 96 kHz 本地 FLAC 与回调数据源探测，二者均进入 DIRECT / 96 kHz。主应用本地曲库的整套操作没有在真机重复执行。

## 回归验证

- 258 项 JVM 单元测试通过；Lint 0 错误，83 警告、18 提示；正式签名构建通过。
- 设备回归覆盖 68 个用例：首次全量 67 项通过，唯一失败是设置页仍断言旧文案；更新该断言并补测后通过。收尾改动后另复测原生播放器和切换/回退相关的 6 项用例，全部通过。
- 新增检查覆盖随机读取/缓存键/EOF/关闭后的读取、取消准备中的旧歌曲、连续拖动、暂停保持、自然结束、单曲循环；更新原生引擎与普通引擎切换及错误回退测试。
- 设备测试使用独立 AVD `Melodia_Native_Test`；真实手机采用同签名覆盖安装，保留账号、背景与音乐库数据。诊断原始记录保留在被忽略的本机 build 目录，不随仓库或 APK 发布。

## 适用范围

DIRECT / 96 kHz 是上述机型、系统和音源的实测结果。原生引擎是否进入直出仍取决于系统策略与音源格式，其他机型、格式和采样率需要单独验证。标准 SDK 不向应用提供原生引擎实际 AudioTrack/HAL 格式，因此设置页显示原生会话及系统提供的音源信息；缺失信息不会用推测的输出格式填充。应用重启后如未收到蓝牙编码事件，仍可能显示“未确认”。

绕过此次 48 kHz 框架混音不等于 LDAC 无损、bit-perfect 或已验证耳机内部全部处理。未确认固定 990 kbps，未完成声学采集、长时间稳定性、多厂商与断连恢复的完整实机验收。

接口与判断依据：[Android AudioTrack 直出定义](https://developer.android.com/reference/android/media/AudioTrack)、[AOSP 音频策略](https://source.android.com/docs/core/audio/implement-policy)、[AOSP 采样率转换](https://source.android.com/docs/core/audio/src)。
