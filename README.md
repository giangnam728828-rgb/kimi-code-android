# Kimi Code on Android

把 **Kimi Code** 完整的编码智能体装进手机：不需要电脑、不需要终端里先开服务、不需要输入 Token。
桌面图标一点，App 自己把 Linux 环境跑起来，直接进工作台。

---

## 它到底是什么

这不是一个"网页壳"。APK 里带着 proot，首次启动会把一整套 Linux 运行环境下载并解压到 App 私有目录：

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
2. 打开 App，首次会下载约 **87 MB** 的运行环境（只下载一次），然后自动解压；
3. 右上角「设置」里填你自己的 API Key（Kimi / DeepSeek / OpenAI / Claude 任选）；
4. 回到主界面点「进入工作台」—— **不会再问你 Token**。

之后的每次打开：服务自动启动，工作台自动打开。

---

## 能力

- **前台服务保活**：切到别的应用、锁屏，运行时不会被系统回收；通知栏常驻状态。
- **崩溃自愈**：服务进程退出后自动重启，不需要手动干预。
- **免 Token 进入**：App 启动服务后读取 `server.token`，直接用 `#token=` 打开工作台。
- **外部存储**：申请了读写权限，`/sdcard` 直接挂进 Linux 环境，工作区可以放你的真实项目。
- **模型切换**：设置页写 `config.toml`，改完重启运行时生效。

---

## 目录结构

```
android/                         原生 Android 工程（Kotlin）
  app/src/main/assets/proot/     proot 二进制与依赖库（arm64）
  app/src/main/java/.../
    MainActivity.kt              启动界面 + 工作台 WebView
    SettingsActivity.kt          模型 / API Key 配置
    RuntimeService.kt            前台服务：安装、启动、保活、看护
    Installer.kt                 下载 rootfs、释放 proot
    TarExtractor.kt              流式 tar.gz 解压器（自带，不依赖外部命令）
    ProotRuntime.kt              组装并启动 proot 命令行
    ConfigWriter.kt              写 ~/.kimi-code/config.toml
.github/workflows/build-apk.yml  CI：测试 + 编译 + 发布
```

---

## 原理

```
手机桌面图标
     │
     ▼
RuntimeService（前台服务）
     │  首次：下载 rootfs.tar.gz → TarExtractor 解压到 filesDir/rootfs
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
- 首次安装需要联网下载运行环境；
- 运行时占用约 **250 MB** 磁盘（在 App 私有目录）；
- APK 目前用 debug 签名发布，不同签名版本之间不能覆盖安装，升级时请先卸载旧版。
