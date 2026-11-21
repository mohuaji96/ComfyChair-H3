# ComfyChair H3

一个非官方的 ComfyUI 安卓客户端，专注在手机上跑 **MiniMax H3 参考生视频**，包括把长视频拆成多段、
用上一段的尾帧接着往下生成；另外带一个**词库组合**和一个**剧情组合**，用来在手机上直接攒提示词。

派生自 [legal-hkr/comfychair](https://github.com/legal-hkr/comfychair)（GPL-3.0）。上游是通用 ComfyUI
客户端，本项目在它的基础上加了 H3 工作区，**不在 F-Droid 发布**。

<p align="center">
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.5/01-h3-studio.png" width="45%">
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.5/02-refs-tailframe.png" width="45%"><br>
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.5/03-params.png" width="45%">
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.5/04-models-lora.png" width="45%"><br>
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.8382.571-vc321/composer-tag.jpg" width="31%" alt="词库组合">
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.8382.571-vc321/composer-story.jpg" width="31%" alt="剧情组合 · 一男两女">
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.8382.571-vc321/composer-story-sfw.jpg" width="31%" alt="剧情组合 · 单人 SFW">
</p>

- 包名 `com.comfychair.h3`，可与上游 `sh.hnet.comfychair` 同时安装
- 最低 Android 13（API 33）
- 需要你自己的 ComfyUI 服务器，手机能访问到（局域网或 VPN）

**下载**：[最新 Release](https://github.com/mohuaji96/ComfyChair-H3/releases/latest) · APK 与四份示例工作流

## 功能

**H3 视频工作区**
- 文生、图生、首尾帧、参考生视频；参数从工作流的节点结构解析，不写死在代码里
- 参考图（最多 9 张）、三路参考音频、画面比例、像素量、采样步数
- 时长限制在官方文档的 4–15 秒

**词库组合**
- 40 个分类、2643 条词，分「单人 / 多人」两档：人物、身体、穿着、场景、光线、镜头各自成组
- 点词即选，点分类名只重抽这一组；可分项锁、可忽略、可整段重抽
- 规则互斥替你收拾冲突：一件式整身包住时上衣和下衣让位，外层衣物挡住时内衣不出现，
  取景裁掉的部分整类跳过并在面板上标「本次已跳过」
- 自带上色的款式（白衬衫、小白鞋）不再被颜色格叠成「黑色的白衬衫」，自带图案的同样不吃颜色
- 「去重」压掉同义重复；「SFW」切到清水词库
- 底部「优化」把这一盘词直接交给提示词优化，接着就能去生成，不用复制粘贴

**剧情组合**
- 四档人数：一男一女、一男两女、两女、单人；切档时分类跟着变
- 一男两女档有独立的「女二」字段组：种族、地域、形象、发型发色、肤色、身材、胸部、配饰、眼镜、穿着、袜、鞋、状态、过程中
- 37 个词池、1396 条词；分项可锁可忽略，可整段重抽
- 成句顺序是「开始 → 过程中 → 姿势与动作链 → 结束」：过程中交代的是开始之后那一身，所以排在开始后面
- 双女档两个人的动作轮流主位，各自写明是女一还是女二；开场就全裸的人不写「衣物已完全除尽」

**尾帧续接**
- 在手机上取视频最后一帧（`MediaMetadataRetriever`），也可以选一个视频文件由它来取
- 取到的帧作为最后一个 `<Picture N>` 挂上去，同时把声明写进提示词
- 声明归 App 所有：关掉开关只撤它自己写的那行，你手打的字一个不动
- 连拍模式自动循环，长片子变成几段短的

**更新提醒**
- 「关于」页可查有没有新版本：一天最多问一次 GitHub，失败静默，能手动重查、也能整体关掉
- 只负责告诉你去哪下：点开跳到 Release 页面，App 自己不下包、不碰安装权限
- 判新旧按版本号数字，公开版与个人版两条编号谱系不会互相误判

**图库**
- 两层缓存：`cacheDir` 是可丢弃副本（容量上限 + LRU + 收藏豁免），「保存到相册」才是永久的
- ComfyUI 重启后不会变空：服务器历史存在内存里，重启即失，App 用本地元数据把条目补回来
- 分类筛选（全部 / 图片 / 视频 / 收藏）、按日分组、缓存条目标识

**成人向内容与 SFW**
- 词库和剧情库各有 NSFW 与 SFW 两套，互斥切换：切档后抽取范围、面板分类、生成页可见项一起变
- 新装默认 SFW，老用户沿用原来的 NSFW；导入词库时先看它声明的档位，档位不对直接拦下
- 两档共用同一套组合逻辑，界面完全一样

## 更新记录

每个版本改了什么见 [CHANGELOG.md](CHANGELOG.md)。

## 工作流：内置四套，另附同款 JSON

App 内置四套手机端 H3 模板，装上就能跑。前两套是单采，一次采样出片；后两套是**二采**，先按低分辨率跑一遍，再放大做第二遍细修，同样步数下细节更稳，代价是多花一次采样。

| 内置模板 | 覆盖模式 | 采样 |
|---|---|---|
| `ttv_h3_mobile_frames` | 文生 / 图生 / 首尾帧，同一个模板三种模式 | 单采 |
| `itv_h3_mobile_r2v` | 参考生视频（多参考图 + 三路参考音频） | 单采 |
| `itv_h3_mobile_guide_turbo` | 文生 / 图生 / 首尾帧 | 二采 + Turbo |
| `itv_h3_mobile_r2v_turbo` | 参考生视频 | 二采 + Turbo |

**同样的四份 JSON 也放在仓库里**，可以先在电脑上打开，看自己缺哪个节点、缺哪个模型：

| 文件 | 说明 |
|---|---|
| [`workflows/ttv_h3_mobile_frames.json`](workflows/ttv_h3_mobile_frames.json) | 可直接拖进 ComfyUI 打开 |
| [`workflows/itv_h3_mobile_r2v.json`](workflows/itv_h3_mobile_r2v.json) | 可直接拖进 ComfyUI 打开 |
| [`workflows/itv_h3_mobile_guide_turbo.json`](workflows/itv_h3_mobile_guide_turbo.json) | 可直接拖进 ComfyUI 打开，含子图 |
| [`workflows/itv_h3_mobile_r2v_turbo.json`](workflows/itv_h3_mobile_r2v_turbo.json) | 可直接拖进 ComfyUI 打开，含子图 |

二采两套的骨架取自 **LokkenJP** 的 TenEros 二采示例（[SampleWorkFlowTwoPassOptimized](https://huggingface.co/LokkenJP/TenEros_Max_optimized_learned/tree/main/SampleWorkFlowTwoPassOptimized) 里的 `H3_Turbo_2Pass_ErosMax_b7_simple_v7` 与 `advanced_refmods_v9`）：两段 `BasicScheduler` + `SamplerCustomAdvanced`，第一遍出低分辨率，`MiniMaxH3LatentUpscaleWithModel` 放大后再跑第二遍，`MiniMaxH3SigmaShift` 对齐噪声表，收尾用 `LokkenVideoPostProcessing`。模板按手机端重画过，两段采样收在子图里，节点和连线跟示例并不逐一对得上，用之前仍要按自己服务器重选模型。

内置模板里的 UNET 和 LoRA 写的是开发机上的文件名，换台机器必然对不上。两条路：

- 直接用内置模板，进「模型与 LoRA」按自己服务器上的文件重选一次；
- 或把 `workflows/` 里的 JSON 拖进 ComfyUI 打开，缺节点会直接报错，缺模型在加载器里一眼能看到。

四份都是 **ComfyUI 界面格式**（`nodes` / `links`），拖进网页就能打开；App 内置的是同一张图的
**API 格式**，提交任务时用。二采两份把两段采样收进了子图，需要带子图支持的新版 ComfyUI——
在 0.46.1 上验证过四份都能零报错打开。

如果导入的是别人分享的工作流：App 内「工作流管理」→ 导入 JSON → 选中文件。无论内置还是导入，
**都要按自己服务器上的文件重选 UNET / CLIP / VAE / LoRA**，否则会报模型不存在。

### 依赖的自定义节点

这几套模板用到以下节点包，缺任何一个都会在导入或提交时报节点类型不存在：

| 节点包 | 示例里用到的节点 |
|---|---|
| MiniMax H3 节点 | `MiniMaxH3ImageToVideo`、`MiniMaxH3ReferenceToVideo`、`MiniMaxChunkFeedForward`、`MiniMaxLowVRAMAttention`、`MiniMaxH3MemoryEfficientSageAttentionPatch` |
| ComfyUI-KJNodes | `ModelPreviewOverrideKJ`、`PathchSageAttentionKJ` |
| rgthree | `Power Lora Loader`、`Display Any` |
| ComfyUI-Easy-Use | `easy seed`、`easy cleanGpuUsed`、`easy clearCacheAll` |
| VideoHelperSuite | `VHS_VideoCombine`、`VHS_LoadAudioUpload` |
| 其他 | `ComfyMathExpression`、`ResolutionSelector`、`CR Text`、`VRAM_Debug`、`ApplyMiniMaxH3FirstBlockCache` |
| 二采两套额外用到 | `MiniMaxH3LatentUpscaleWithModel`、`LatentUpscaleModelLoader`、`MiniMaxH3SigmaShift`、`LokkenVideoPostProcessing`、`RTXVideoSuperResolution` |

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
- [LokkenJP / TenEros](https://huggingface.co/LokkenJP/TenEros_Max_optimized_learned) — 二采（两段采样）工作流的原作者，`LokkenVideoPostProcessing` 等节点也出自他

---

# English (summary)

An unofficial Android client for [ComfyUI](https://github.com/comfyanonymous/ComfyUI), built around
MiniMax H3 reference-to-video — including multi-segment clips that continue from the previous
segment's last frame. Derived from [ComfyChair](https://github.com/legal-hkr/comfychair), GPL-3.0.

- Package `com.comfychair.h3` — installs alongside upstream `sh.hnet.comfychair`
- Android 13 (API 33) or newer; you need your own ComfyUI server reachable from the phone

**Download**: [latest release](https://github.com/mohuaji96/ComfyChair-H3/releases/latest) — APK plus the bundled sample workflows.

**Features**: H3 workspace (T2V / I2V / FL2V / reference-to-video) with phone-side tail-frame capture
and burst capture; a tag composer (40 categories, 2643 words, rule-driven mutexes, SFW switch) and a
story composer (four cast shapes, 37 pools, a separate field group for the second woman); a two-tier media cache that survives a ComfyUI restart; gallery filters and day
grouping; an update check that asks GitHub at most once a day (switchable off, browser hand-off only).
Both word libraries ship side by side (NSFW and SFW), switched per profile.

**Four mobile workflows are bundled**: `ttv_h3_mobile_frames` and `itv_h3_mobile_r2v` sample in one
pass; `itv_h3_mobile_guide_turbo` and `itv_h3_mobile_r2v_turbo` are two-pass — a low-resolution pass,
then a latent-upscaled refinement pass. The two-pass skeletons follow **LokkenJP**'s TenEros
two-pass samples ([Hugging Face](https://huggingface.co/LokkenJP/TenEros_Max_optimized_learned/tree/main/SampleWorkFlowTwoPassOptimized)):
two `BasicScheduler` + `SamplerCustomAdvanced` stages, `MiniMaxH3LatentUpscaleWithModel` between
them, `MiniMaxH3SigmaShift` for the noise table, `LokkenVideoPostProcessing` to finish.
The first two JSON files live in [`workflows/`](workflows) in ComfyUI's UI format, so you can drag
them into the web UI and see which nodes or models your machine is missing; the two-pass files use
the app's package format and go in through "Workflow manager → Import". Both formats describe the
same graph. Both carry the developer's UNET and LoRA filenames, so
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
