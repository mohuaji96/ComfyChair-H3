# ComfyChair H3

一个非官方的 ComfyUI 安卓客户端，专注在手机上跑 **MiniMax H3 参考生视频**，包括把长视频拆成多段、
用上一段的尾帧接着往下生成。

派生自 [legal-hkr/comfychair](https://github.com/legal-hkr/comfychair)（GPL-3.0）。上游是通用 ComfyUI
客户端，本项目在它的基础上加了 H3 工作区，**不在 F-Droid 发布**。

<p align="center">
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.5/01-h3-studio.png" width="45%">
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.5/02-refs-tailframe.png" width="45%"><br>
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.5/03-params.png" width="45%">
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.5/04-models-lora.png" width="45%">
</p>

- 包名 `com.comfychair.h3`，可与上游 `sh.hnet.comfychair` 同时安装
- 最低 Android 13（API 33）
- 需要你自己的 ComfyUI 服务器，手机能访问到（局域网或 VPN）

**下载**：[最新 Release](https://github.com/mohuaji96/ComfyChair-H3/releases/latest) · APK 与两份示例工作流

## 功能

**H3 视频工作区**
- 文生、图生、首尾帧、参考生视频；参数从工作流的节点结构解析，不写死在代码里
- 参考图（最多 9 张）、三路参考音频、画面比例、像素量、采样步数
- 时长限制在官方文档的 4–15 秒

**尾帧续接**
- 在手机上取视频最后一帧（`MediaMetadataRetriever`），也可以选一个视频文件由它来取
- 取到的帧作为最后一个 `<Picture N>` 挂上去，同时把声明写进提示词
- 声明归 App 所有：关掉开关只撤它自己写的那行，你手打的字一个不动
- 连拍模式自动循环，长片子变成几段短的

**图库**
- 两层缓存：`cacheDir` 是可丢弃副本（容量上限 + LRU + 收藏豁免），「保存到相册」才是永久的
- ComfyUI 重启后不会变空：服务器历史存在内存里，重启即失，App 用本地元数据把条目补回来
- 分类筛选（全部 / 图片 / 视频 / 收藏）、按日分组、缓存条目标识

## 更新记录

每个版本改了什么见 [CHANGELOG.md](CHANGELOG.md)。

## 工作流：内置两套，另附同款 JSON

App 内置两套手机端 H3 模板，装上就能跑：

| 内置模板 | 覆盖模式 |
|---|---|
| `ttv_h3_mobile_frames` | 文生 / 图生 / 首尾帧，同一个模板三种模式 |
| `itv_h3_mobile_r2v` | 参考生视频（多参考图 + 三路参考音频） |

**同样的两份 JSON 也放在仓库里**，可以先在电脑上打开，看自己缺哪个节点、缺哪个模型：

| 文件 | 说明 |
|---|---|
| [`workflows/ttv_h3_mobile_frames.json`](workflows/ttv_h3_mobile_frames.json) | 可直接拖进 ComfyUI 打开 |
| [`workflows/itv_h3_mobile_r2v.json`](workflows/itv_h3_mobile_r2v.json) | 可直接拖进 ComfyUI 打开 |

内置模板里的 UNET 和 LoRA 写的是开发机上的文件名，换台机器必然对不上。两条路：

- 直接用内置模板，进「模型与 LoRA」按自己服务器上的文件重选一次；
- 或把 `workflows/` 里的 JSON 拖进 ComfyUI 打开，缺节点会直接报错，缺模型在加载器里一眼能看到。

`workflows/` 里放的是 **ComfyUI 界面格式**（`nodes` / `links`），拖进网页就能打开；App 内置的是同一套逻辑的
**API 格式**，两种格式内容对应、节点一致，只是给不同地方用。

如果导入的是别人分享的工作流：App 内「工作流管理」→ 导入 JSON → 选中文件。无论内置还是导入，
**都要按自己服务器上的文件重选 UNET / CLIP / VAE / LoRA**，否则会报模型不存在。

### 依赖的自定义节点

这两个示例用到以下节点包，缺任何一个都会在导入或提交时报节点类型不存在：

| 节点包 | 示例里用到的节点 |
|---|---|
| MiniMax H3 节点 | `MiniMaxH3ImageToVideo`、`MiniMaxH3ReferenceToVideo`、`MiniMaxChunkFeedForward`、`MiniMaxLowVRAMAttention`、`MiniMaxH3MemoryEfficientSageAttentionPatch` |
| ComfyUI-KJNodes | `ModelPreviewOverrideKJ`、`PathchSageAttentionKJ` |
| rgthree | `Power Lora Loader`、`Display Any` |
| ComfyUI-Easy-Use | `easy seed`、`easy cleanGpuUsed`、`easy clearCacheAll` |
| VideoHelperSuite | `VHS_VideoCombine`、`VHS_LoadAudioUpload` |
| 其他 | `ComfyMathExpression`、`ResolutionSelector`、`CR Text`、`VRAM_Debug`、`ApplyMiniMaxH3FirstBlockCache` |

### 关于加速节点

示例工作流为了跑得动，默认开了一串加速手段，**不代表你的机器也需要**，删掉或关掉不影响正确性：

- **4 步加速 LoRA**（`...turbo_4step_v1.2_768p...`）：`LoraLoaderModelOnly` 强度 1.0，把采样步数压到 4。
  换成别的 LoRA 或不用 LoRA，步数要相应调回 20 左右
- **SageAttention 补丁**（`PathchSageAttentionKJ` + `MiniMaxH3MemoryEfficientSageAttentionPatch`）：省显存提速，
  需要装 SageAttention；没装就把这两个节点删掉
- **首块缓存**（`ApplyMiniMaxH3FirstBlockCache`）：只出现在首尾帧模板里，跨步复用注意力块
- **Easy-Use 清理节点**（`easy cleanGpuUsed`、`easy clearCacheAll`）：每次跑完释放显存
- **低显存注意力**（`MiniMaxLowVRAMAttention`）：显存够可以绕过

## 构建

需要 JDK 21 和 Android SDK 36。

```bash
./gradlew :app:testDebugUnitTest   # 101 个单测
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease     # 没有签名配置时产出 unsigned
```

发布签名密钥**不会进仓库**。要签 release，在仓库同级放一个 `signing/keystore.properties`，
或用环境变量 `COMFYCHAIR_KEYSTORE_PROPERTIES` 指向它：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

没有这个文件时 `assembleRelease` 产出未签名包——从源码构建的人本来就该用自己的 key。

> Windows 用户注意：Android Gradle Plugin 不接受含非 ASCII 字符的工程路径。如果你的检出目录带中文，
> 换到 `C:\build\ComfyChairH3` 这类路径下构建。

## 许可

GPL-3.0，继承自上游，见 [LICENSE](LICENSE)。本项目的修改同样以 GPL-3.0 发布，对应的完整源码就是
这个仓库。分发 APK 时请保持 tag 与源码一致。

## 隐私

没有遥测、没有账号、没有统计。详见 [PRIVACY.md](PRIVACY.md)。

## 致谢

- [ComfyUI](https://github.com/comfyanonymous/ComfyUI) — 这个 App 连接的服务器
- [legal-hkr/comfychair](https://github.com/legal-hkr/comfychair) — 本项目基于它修改

---

# English (summary)

An unofficial Android client for [ComfyUI](https://github.com/comfyanonymous/ComfyUI), built around
MiniMax H3 reference-to-video — including multi-segment clips that continue from the previous
segment's last frame. Derived from [ComfyChair](https://github.com/legal-hkr/comfychair), GPL-3.0.

- Package `com.comfychair.h3` — installs alongside upstream `sh.hnet.comfychair`
- Android 13 (API 33) or newer; you need your own ComfyUI server reachable from the phone

**Download**: [latest release](https://github.com/mohuaji96/ComfyChair-H3/releases/latest) — APK plus the two sample workflows.

**Features**: H3 workspace (T2V / I2V / FL2V / reference-to-video) with phone-side tail-frame capture
and burst capture; a two-tier media cache that survives a ComfyUI restart; gallery filters and day
grouping.

**Two mobile workflows are bundled** (`ttv_h3_mobile_frames`, `itv_h3_mobile_r2v`), and the same
JSON files live in [`workflows/`](workflows) in ComfyUI's UI format, so you can drag them into the
web UI and see which nodes or models your machine is missing. The bundled copies use the API format
the app submits; the two versions describe the same graph. Both carry the developer's UNET and LoRA filenames, so
reselect them once under "Model & LoRA" for your own server. Required node packs:
MiniMax H3 nodes, ComfyUI-KJNodes, rgthree, ComfyUI-Easy-Use, VideoHelperSuite, plus
`ComfyMathExpression`, `ResolutionSelector` and `CR Text`. The samples enable a 4-step turbo LoRA,
SageAttention patches, a first-block cache and Easy-Use cleanup nodes for speed — remove them if
your setup does not need them.

**Build**: JDK 21 and Android SDK 36; `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
Release signing material stays outside the repository — without it `assembleRelease` produces an
unsigned APK.

**Licence**: GPL-3.0, inherited from upstream. **Privacy**: no telemetry, no accounts; see
[PRIVACY.md](PRIVACY.md).
