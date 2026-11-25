# 工作流程

这份文档说明这个项目的目录分工、改动授权、发版步骤和本机环境约定。

## 一、两个目录

| 目录 | 角色 | 版本控制 |
| :--- | :--- | :--- |
| `G:\app\ComfyChair572` | 编译主工程。日常开发都在这里，`assembleRelease` 也从这里出 | 无 |
| `G:\app\ComfyChairH3-ws` | GitHub 发布源，对应 mohuaji96/ComfyChair-H3 | git |

改代码只改 `ComfyChair572`。仓库是镜像，发版前同步一次。

两边的分叉必须靠发版流程收口，否则会像 2026-11 那样拉开三个月、109 个文件。

## 二、授权档位

| 说这句话 | 允许做的事 | 不允许 |
| :--- | :--- | :--- |
| 讨论（默认） | 读代码、查数据、出方案 | 改文件、编译、碰 git |
| 「做」 | 改本地文件、编译、跑单元测试 | 提交、推送、打包 |
| 「开工」 | 上面全部，加升版本号、打包、投递共享盘 | 推送 git、建 Release |
| 「发布」 | 提交、推送、打 tag、建 Release、上传 APK，一次做完 | — |

「发布」是一整套动作，不再拆成「推」和「建 Release」两步。说一次，全做完。

## 三、发版步骤

```
1  比对 572 与仓库的 app/src
     有差异 → robocopy /MIR 同步 → 连同版本号一起提交
2  升版本号（versionCode 与 versionName 都避开 4）
3  写中英 changelog 到 metadata/{zh-CN,en-US}/changelogs/<vc>.txt
4  词库若改过，四份词库版本号一起升
5  编译，跑全部单元测试
6  assembleRelease，apksigner 验签，算 SHA-256
7  写 H3_<版本>_更新说明.md，回填大小与哈希
8  投递共享盘与 deliveries，两端核 SHA-256
9  解包核验：词库版本、changelog 首行、语言资源
10 说「发布」→ 提交推送 → 打 tag → 建 Release → 传 APK
```

第 1 步在每次发版时都要跑，不要跳过。

## 四、版本与文档规约

- **版本号避开数字 4**：`versionCode` 与 `versionName` 都不出现 4，遇到直接跳号，不解释
- **changelog 必须中英各一份**：各不超过 500 字符，首行 `v<版本名>` 两边逐字一致；缺英文会让 ChangelogTest 失败，缺中文则应用内更新说明会回退英文
- **词库版本跟 App 版本走**：改了词库数据就必须升词库版本号，否则升级安装仍读手机上的旧副本
- **README 与 CHANGELOG 同步**：版本号、功能描述变了要跟着改，否则发布页与实际不符
- **提交说明不写「上游」「原版」这类对比措辞**：功能就是功能

## 五、交付位置

```
共享盘   \\192.168.192.188\启迪 共享给我\启迪\个人分享的文件\许大帅哥分享\APP
本地     G:\app\ComfyChair572\deliveries\
```

每版两个文件：APK 与 `H3_<版本>_更新说明.md`。两端 SHA-256 必须一致。

## 六、本机环境

| 项 | 值 |
| :--- | :--- |
| JAVA_HOME | `G:\android-build\jdk21` |
| ANDROID_HOME | `G:\android-build\sdk` |
| GRADLE_USER_HOME | `G:\android-build\gradle-home` |
| 签名配置 | `COMFYCHAIR_KEYSTORE_PROPERTIES=G:\ComfyUI各类文件模型\app\signing\keystore.properties` |
| apksigner | `G:\android-build\sdk\build-tools\36.0.0\apksigner.bat` |
| 发布凭据 | `G:\ComfyUI各类文件模型\app\signing\github-release.token.bin`（DPAPI 加密，PAT 有效期至 2026-12-25） |

## 七、常见坑

| 现象 | 原因与处理 |
| :--- | :--- |
| `git push` 报 `schannel: failed to receive handshake` | 加 `-c http.version=HTTP/1.1`，失败就重试，通常第二次就过 |
| `api.github.com` 连不上 | 是本机 CRL 查询失败（`CRYPT_E_REVOCATION_OFFLINE`），不是网络不通；`curl` 加 `--ssl-no-revoke` |
| 上传 Release 附件同样失败 | 走 `uploads.github.com`，同样加 `--ssl-no-revoke` |
| 批量改文案后编译报错 | 用脚本按文件改、每批编译；不要用 PowerShell 整段替换，会静默失效 |
| Gradle 报路径非法 | 工程路径必须是纯 ASCII，含中文的目录会被直接拒绝 |
| 提交刷一屏 `LF will be replaced by CRLF` | 仓库没有 `.gitattributes`，不影响内容，补上会触发全仓换行规范化，另择时机处理 |
| 词库更新后手机上没变化 | 词库版本号没升，或改的是仓库副本而不是 572 |

## 八、语言资源

界面文案走 `strings.xml`，自研功能的条目统一放在 `strings_h3.xml`（`values/` 英文、`values-zh/` 中文）。

- 四种额外语言（德、西、法、波）的翻译保留在 `values-de` 等目录里当底稿，但 `localeFilters` 只打包 `en` 与 `zh`
- 要恢复某种语言：从 `localeFilters` 去掉它、在语言列表与 `locales_config.xml` 加回
- 新增界面文案时，英文放 `values/`，中文放 `values-zh/`，不要只写一边

## 九、待完成

- H3 提示词区与生成页的界面文案（含 ViewModel 中的状态消息，需改成资源 id + 参数）
- 提示词页、剧情页、优化供应商、模板库的界面文案
- Release 613 的 tag 指向同步前的提交，下一版重新打 tag 即可消除
