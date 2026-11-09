# Kimi Code on Android (移动端启动器)

把属于你自己的 **Kimi Code** 编码智能体装进手机：桌面图标一点，直接进入工作台。

---

## 🌟 现在能做什么

- **📱 原生全屏体验**：独立 APK，无浏览器地址栏，支持手机触控与返回手势。
- **🔗 自动连接**：启动即探测 `58630` / `58627`，服务在线就绪后可直接进入。
- **♻️ 断线自愈**：服务未就绪时每 2.5 秒自动重试，服务一起来立刻连上，不用手动刷新。
- **⚡ 打开即进入**：设置里可开启「自动进入工作台」，跳过启动面板。
- **🛡️ 网络与存储权限**：本地回环、局域网、外部存储读写均已申请。
- **🤖 自动化构建**：推送到 `main` 即由 GitHub Actions 编译并发布最新 APK。

---

## 🚀 手机端使用步骤

1. **安装 APK**：在 [Releases](https://github.com/giangnam728828-rgb/kimi-code-android/releases) 下载 `Kimi-Code-Android.apk` 并安装。
2. **服务会自动启动**：PocketDev 终端打开时会自动执行启动钩子，把服务拉起来（无需敲命令）。
3. **打开 App**：状态变为「已就绪」后点击进入工作台即可。

如果服务确实没有起来，在终端执行一次：

```bash
bash /workspace/kimicode/kimi-serve.sh start
```

常用命令：

```bash
bash /workspace/kimicode/kimi-serve.sh status    # 查看状态
bash /workspace/kimicode/kimi-serve.sh restart   # 重启
bash /workspace/kimicode/kimi-serve.sh logs      # 实时日志
```

---

## 🔧 工作原理

```
手机桌面图标
     │
     ▼
Kimi Code APK (WebView 启动器)
     │  探测 http://127.0.0.1:58630/
     ▼
本地容器中的 kimi web 服务  ←── kimi-serve.sh 守护（自动拉起 / 崩溃重启）
     │
     ▼
Kimi Code Web 工作台（免 Token，直接用你自己的 API Key）
```

启动器本身不包含 Linux 运行时，它连接的是运行在本地容器里的 Kimi Code 服务。
服务由 `kimi-serve.sh` 守护：终端启动时自动拉起，进程崩溃 3 秒后自动重启。

---

## 🛠️ 本地开发

```bash
npm install
npx cap add android
npx cap sync android
node scripts/inject-permissions.mjs
cd android && ./gradlew assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`
