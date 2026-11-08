# Kimi Code on Android (移动端原生启动器)

本项目是专为 Android 移动设备打造的 **Kimi Code 原生启动器**，支持在手机端直接与本地或局域网内的 Kimi Code 编码智能体进行交互。

---

## 🌟 核心特性

- **📱 原生 APK 体验**：摆脱浏览器地址栏与控制条束缚，全屏沉浸式写代码与对话。
- **🛡️ 强大系统级权限**：
  - 前台常驻保活（Foreground Service），防止切应用时后台被系统清理；
  - 外部存储空间（`/sdcard`）读写能力，直接管理与编辑手机上的项目工程；
  - 本地回环与局域网网络权限全开，毫秒级连接 Agent。
- **⚡ 自动化 CI/CD**：内置 GitHub Actions 工作流，只要提交代码即可自动编译生成最新的 `.apk` 安装包。
- **🔌 智能多端口探测**：启动时自动检测 `58630` 与 `58627` 端口，在线即秒连，离线提供一键切换与配置面板。

---

## 🚀 手机端运行步骤

1. 在手机本地终端（如 Termux / PRoot）启动 Kimi Code 服务：
   ```bash
   /workspace/kimicode/start-web.sh
   ```
2. 打开手机上安装的 **Kimi Code** App，它将自动连接并展示工作台。

---

## 🛠️ GitHub Actions 自动编译说明

本项目配置了完整的 GitHub Actions 工作流：
- 每次向 `main` 分支推送代码，GitHub 会自动启动 Ubuntu 虚拟环境，完成 Gradle 编译；
- 构建完成后，会自动在 [Releases](https://github.com/giangnam728828-rgb/kimi-code-android/releases) 发布最新的 `Kimi-Code-Android.apk`，手机直接点击即可下载安装。
