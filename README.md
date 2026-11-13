# Kimi Code on Android

把 **Kimi Code** 完整的编码智能体装进手机：不需要电脑、不需要终端里先开服务、不需要输入 Token。
桌面图标一点，App 自己把 Linux 环境跑起来，直接进工作台。

---

## 它到底是什么

这不是一个"网页壳"。APK 里同时带着 proot 和整套 Linux 运行环境，首次启动解压到 App 私有目录后即可离线使用：

| 组件 | 说明 |
| --- | --- |
| **Ubuntu arm64 根文件系统** | 精简过的 Ubuntu 24.04 base |
| **Node.js 24** | Kimi Code 服务端的运行时 |
| **Kimi Code 服务端 + Web 工作台** | 本仓库 `dist` 构建产物 |
| **proot** | 免 Root 运行 Linux 的核心（Termux 编译版） |
| **ripgrep** | 内置，供智能体搜索代码用 |

装好之后，所有东西都在手机本地跑，和 DSHA 的思路一致：**App 就是运行环境**。

---

## 使用方法

1. 在 [Releases](https://github.com/giangnam728828-rgb/kimi-code-android/releases) 下载 `Kimi-Code-Android.apk` 并安装；
2. 打开 App，首次会解压内置的运行环境（一次性，约 1 分钟），之后完全离线；
3. 点顶栏右上角「**模型供应商**」（或设置页主按钮）进入模型管理：
   从目录挑选供应商，或新增 OpenAI-compatible / Anthropic / Gemini；点进去在
   「设定」页填名称、Base URL、API Key、Endpoint 模式，可测试连线、设置自定义
   请求头、开关启用；切到「模型」页可**从远端 `/models` 自动拉取**、新增自订模型、
   搜索，并且**每个模型都能单独设置**（展示名、上下文、思考强度、支援思考）；
   单选即可设为该分组默认模型，切换**无需重启**。
4. 回到主界面点「进入工作台」—— **不会再问你 Token**。

工作台「更多模型」菜单里，**每个分组各占一块、彼此隔开**；同一分组下的多个模型
可以直接切换，不用再回设置页保存，也不会出现 `is not configured in config.toml`。

之后的每次打开：服务自动启动，工作台自动打开。

---

## 能力

- **前台服务保活**：切到别的应用、锁屏，运行时不会被系统回收；通知栏常驻状态。
- **崩溃自愈**：服务进程退出后自动重启，不需要手动干预。
- **免 Token 进入**：App 启动服务后读取 `server.token`，直接用 `#token=` 打开工作台。
- **外部存储**：申请了读写权限，`/sdcard` 直接挂进 Linux 环境，工作区可以放你的真实项目。
- **Eta 风格两级管理页**：一级「模型供应商」列表（搜索、从目录添加、新增三类、
  已设定列表带单选），二级「设定 / 模型」双 Tab；界面按浅灰底 + 白圆角卡片 +
  分段 Tab 重做，配置流程一步到位，不再有多选弹窗 + 手动输入 + 高级选项的堆叠。
- **多模型并存**：可同时配置多个供应商（各自独立的 API Key），工作台里随时切换；
  切换默认模型只改 `config.toml` 顶层一行，**不重建模型段、不重启运行环境**。
- **模型分组隔离**：每个分组在 `config.toml` 里独占一个 `[providers.<alias>]` 段，
  模型 id 一律以本组 alias 为前缀；工作台「更多模型」按分组分块显示并留出间隔。
- **模型段只增不删（2.16.0 根治）**：重建配置时把历史 `[models.*]` 段原样并回
  managed 块，网页下拉能选到的每个 key 都有对应段，彻底消除
  `Model "..." is not configured in config.toml`；若历史安装已被弄坏，升级后
  强制重写一次自愈，缺段时还会按需补齐。
- **逐模型设置**：每个模型可单独设置展示名、上下文长度、思考强度、是否支援思考，
  参数写进各自的 `[models."<alias>/<id>"]` 段；支持远端 `/models` 一键拉取与
  自订模型，模型清单可搜索。
- **数据备份**：数据页支持备份 / 恢复会话、配置和工作区，可导出到别的手机迁移。
- **五个标签页**：首页（状态 / 地址 / 模型 / 工作区 / 日志）、插件、设置、数据、终端。
- **滑动返回**：工作台里从屏幕左边缘往右滑回到启动页，没有悬浮球遮挡界面。

---

## 目录结构

```
android/                         原生 Android 工程（Kotlin）
  app/src/main/assets/proot/     proot 二进制与依赖库（arm64）
  app/src/main/java/.../
    MainActivity.kt              五个标签页 + 全屏工作台 WebView + 终端
    RuntimeService.kt            前台服务：安装、启动、保活、看护
    Installer.kt                 解压内置 rootfs、释放 proot
    TarExtractor.kt              流式 tar 解压器（gzip 自动嗅探，不依赖外部命令）
    ProotRuntime.kt              组装并启动 proot 命令行
    ConfigWriter.kt              写 ~/.kimi-code/config.toml（多分组并存、模型段只增不删）
    ConfigMerger.kt              TOML 定点合并，保留服务端写入的登录态
    ModelConfig.kt               [models.*] 段稳定化：只增不删 / 自愈补段 / 只改一行切换默认
    ModelGroup.kt                分组隔离：唯一 alias / 模型 key 归属 / 分区生成
    ProviderStore.kt             供应商 + 逐模型设置持久化，兼容旧 Prefs 自动迁移
    ProviderActivity.kt          Eta 风格两级页：供应商列表 → 设定 / 模型 双 Tab
    EtaUi.kt                     浅色卡片 / 分段 Tab / 单选圈等平台 View 组件
    ModelCatalog.kt              从 /v1/models 拉取目录并按模型商归类
    ModelPickerDialog.kt         单选模型（旧入口保留，非主路径）
    ModelMultiPickerDialog.kt    多选模型（旧入口保留，非主路径）
    QuickSettingsDialog.kt       分组内多模型管理（旧入口保留，非主路径）
runtime/entrypoint.sh            容器启动脚本（CI 会把它打进 rootfs）
runtime/build-rootfs.sh          重建 rootfs 归档（需 aarch64 主机）
.github/workflows/build-apk.yml  CI：测试 + 编译 + 发布
```

---

## 原理

```
手机桌面图标
     │
     ▼
RuntimeService（前台服务）
     │  首次：解压 APK 内置的 rootfs 到 filesDir/rootfs
     │  之后：直接复用
     ▼
proot -0 -r <filesDir>/rootfs … /opt/kimi/entrypoint.sh
     │
     ▼
node dist/main.mjs web --host 127.0.0.1 --port 58630
     │  写入 rootfs/root/.kimi-code/server.token
     ▼
MainActivity 读取 token → WebView 打开 http://127.0.0.1:58630/#token=…
```

---

## 本地构建

```bash
cd android
gradle --offline :app:assembleRelease      # 本机离线仓库
# 或
./gradlew :app:assembleRelease             # 联网环境
```

产物：`android/app/build/outputs/apk/release/app-release.apk`

单元测试：

```bash
cd android
gradle :app:testReleaseUnitTest
# 用真实 rootfs 归档验证解压器（可选）
gradle :app:testReleaseUnitTest -Dkimi.rootfs=/path/to/rootfs-arm64.tar.gz
```

---

## 已知限制

- **只支持 arm64**，Android 10 及以上；
- 运行时占用约 **250 MB** 磁盘（在 App 私有目录）；
- APK 约 **240 MB** —— 整套运行环境内置的代价，换来首次启动不依赖网络；
- 所有版本使用同一把固定密钥签名，更新直接覆盖安装、数据保留；
  只有从更早的 debug 签名版本升级过来时才需要先卸载一次。

---

## 2.16.1 更新（界面替换与交互修正）

- **设置页直接替换**：点底部「设置」或顶栏「模型供应商」都进同一个供应商管理页；
  旧版繁琐的模型编辑器 / 多选弹窗 / 高级选项不再出现。局域网访问、工作台常亮、
  边缘滑动返回等开关已移到供应商列表底部的「App 偏好」。
- **不自动进工作台**：服务 / 解压就绪后停在启动页，用户自己点「进入工作台」才进。
- **返回键**：工作台里「更多模型」等浮层按返回会先关闭浮层，不会直接跳出工作台；
  有真实网页历史时仍回到上一页。
- **登录态保活**：WebView cookie 在切后台时强制落盘；`config.toml` 重写会保住
  `[auth]` 等登录凭据段，避免覆盖安装后账号掉线。
- **拉取模型自己挑**：点「从远端自动拉取」只列出该分组**还没加入**的模型，默认
  一个都不勾，底部「选择要新增的模型」在未勾选时置灰不可点；「全选」必须由用户
  主动点。不再一键全加进来再一个个删。
- **每个模型点整行就能设置**：去掉了小齿轮图标，点模型那一行进入设置（展示名 /
  上下文 / 思考强度 / 支援思考 / 设为默认模型 / 移除）；右侧圆圈仍是设为默认。
- 逐模型编辑现在会真正写回 `config.toml`；versionCode 30；全量 102 个单测通过。

---

## 2.16.2 更新（草稿制、统一弹层、全 App 动效、改名生效）

- **新增供应商不再"点一下就存"**：点「新增 OpenAI / Anthropic / Gemini / 目录项」
  只创建一个**内存草稿**，不会出现在「已设定的供应商」里；只有点「保存并新增」且
  名称与 API Key 都填了才落盘，直接返回等于什么都没做。
- **全 App 只有一套弹层**：供应商页的目录 / Endpoint / 请求头 / 删除确认 / 编辑模型 /
  新增模型 / 自定义模型，以及主界面的工作目录 / 备份 / 恢复，全部换成统一的
  `EtaSheet`（底部滑入、白色圆角、顶部拖拽横条、半透明遮罩、返回键关闭），
  不再出现系统原生居中方块对话框。
- **整个 App 都加了动效**：弹层滑入滑出、页面与 Tab 切换淡入上移、Activity 转场、
  列表项错峰入场、按钮按压缩放回弹。全部走同一套 `EtaMotion` 参数，并读取系统
  「动画时长缩放」，系统关闭动画时自动跳过。
- **改名字真的会生效**：工作台的「更多模型」分组标题取 `[providers.<alias>]` 的段名，
  后端没有独立的显示名，所以改名会**同步迁移 alias**：旧 alias 记入退役表，其历史
  `[models.*]` 段在重建配置时清掉（避免悬空段），保存后刷新工作台，分组标题立刻变。
  别名现在保留中文，`深度求索` 不会再被清成 `custom`。
- **模型展示名**写进 `[models.*]` 的 `display_name`，下拉里的模型名也会跟着变。
- 旧的设置页列表逻辑已停用，不再往进不去的页面里重建 View。
- versionCode 31；全量 111 个单测通过（新增 `ModelRenameTest`）。
