(function () {
  const DEFAULT_PORT = '58630';
  const DEFAULT_URL = `http://127.0.0.1:${DEFAULT_PORT}/`;
  const STORAGE_URL_KEY = 'kimi_android_server_url';
  const STORAGE_THEME_KEY = 'kimi_android_theme';

  // DOM 元素
  const statusIndicator = document.getElementById('status-indicator');
  const mainStatusTitle = document.getElementById('main-status-title');
  const mainStatusDesc = document.getElementById('main-status-desc');
  const btnEnter = document.getElementById('btn-enter-workspace');
  const btnRestart = document.getElementById('btn-restart');
  const btnPower = document.getElementById('btn-power');
  const powerText = document.getElementById('power-text');
  const metricPort = document.getElementById('metric-port');
  const metricEnv = document.getElementById('metric-env');
  const terminalOutput = document.getElementById('terminal-output');
  const workspaceView = document.getElementById('workspace-view');
  const workspaceFrame = document.getElementById('workspace-frame');
  const btnBack = document.getElementById('btn-back-to-launcher');
  const btnViewAddresses = document.getElementById('btn-view-addresses');
  const addressModal = document.getElementById('address-modal');
  const btnCloseModal = document.getElementById('btn-close-modal');
  const inputCustomUrl = document.getElementById('input-custom-url');
  const btnApplyCustom = document.getElementById('btn-apply-custom');
  const btnCopyLog = document.getElementById('btn-copy-log');
  const btnDownloadLog = document.getElementById('btn-download-log');
  const btnThemeToggle = document.getElementById('btn-theme-toggle');

  let currentUrl = localStorage.getItem(STORAGE_URL_KEY) || DEFAULT_URL;
  let isServiceOnline = false;

  // 日志记录函数
  function appendLog(line) {
    const time = new Date().toLocaleTimeString();
    terminalOutput.querySelector('code').textContent += `\n[${time}] ${line}`;
    terminalOutput.scrollTop = terminalOutput.scrollHeight;
  }

  // 状态机更新
  function setReadyState(online, port = '58630') {
    isServiceOnline = online;
    metricPort.textContent = port;

    if (online) {
      statusIndicator.className = 'status-indicator online';
      mainStatusTitle.textContent = 'Kimi Code 已就绪，可进入';
      mainStatusDesc.textContent = 'proot · kimi-code 2.5.2';
      metricEnv.textContent = 'READY';
      metricEnv.className = 'metric-val text-ready';
      btnEnter.innerHTML = '<span class="play-icon">▶</span> 进入工作台';
      btnEnter.style.background = '#2563eb';
      powerText.textContent = '停止';
    } else {
      statusIndicator.className = 'status-indicator';
      mainStatusTitle.textContent = 'Kimi Code 服务未就绪';
      mainStatusDesc.textContent = '点击下方按钮可重试或手动启动';
      metricEnv.textContent = 'STANDBY';
      metricEnv.className = 'metric-val';
      btnEnter.innerHTML = '<span class="play-icon">🚀</span> 启动并进入';
      btnEnter.style.background = '#0284c7';
      powerText.textContent = '启动';
    }
  }

  // 探测后端服务状态
  async function probeServer(url) {
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 2500);
      const res = await fetch(url, { method: 'HEAD', mode: 'no-cors', signal: controller.signal });
      clearTimeout(timeout);
      return true;
    } catch {
      return false;
    }
  }

  async function checkAllPorts() {
    appendLog(`正在探测主端口 ${currentUrl}...`);
    let ok = await probeServer(currentUrl);

    if (ok) {
      const port = new URL(currentUrl).port || '58630';
      setReadyState(true, port);
      appendLog(`连接就绪: ${currentUrl}`);
      return;
    }

    // 备用端口探测
    appendLog('主端口未响应，正在探测备用端口 58627...');
    let altOk = await probeServer('http://127.0.0.1:58627/');
    if (altOk) {
      currentUrl = 'http://127.0.0.1:58627/';
      setReadyState(true, '58627');
      appendLog('在备用端口 58627 发现可用服务！');
      return;
    }

    setReadyState(false, new URL(currentUrl).port || '58630');
    appendLog('本地服务未启动，可直接点击【进入工作台】唤起。');
  }

  // 动作绑定
  btnEnter.addEventListener('click', () => {
    appendLog(`正在打开工作台: ${currentUrl}`);
    workspaceFrame.src = currentUrl;
    workspaceView.classList.remove('hidden');
  });

  btnBack.addEventListener('click', () => {
    workspaceView.classList.add('hidden');
  });

  btnRestart.addEventListener('click', async () => {
    appendLog('正在重新连接并刷新环境状态...');
    setReadyState(false);
    await checkAllPorts();
  });

  btnPower.addEventListener('click', () => {
    if (isServiceOnline) {
      setReadyState(false);
      appendLog('已切换至离线待机状态。');
    } else {
      checkAllPorts();
    }
  });

  // 快捷入口 & 弹窗
  btnViewAddresses.addEventListener('click', () => {
    addressModal.classList.remove('hidden');
  });

  btnCloseModal.addEventListener('click', () => {
    addressModal.classList.add('hidden');
  });

  document.querySelectorAll('.address-item').forEach((item) => {
    item.addEventListener('click', () => {
      const url = item.getAttribute('data-url');
      navigator.clipboard?.writeText(url);
      item.querySelector('.copy-tag').textContent = '已复制!';
      setTimeout(() => (item.querySelector('.copy-tag').textContent = '复制'), 1500);
    });
  });

  btnApplyCustom.addEventListener('click', () => {
    let url = inputCustomUrl.value.trim();
    if (!url.endsWith('/')) url += '/';
    currentUrl = url;
    localStorage.setItem(STORAGE_URL_KEY, url);
    addressModal.classList.add('hidden');
    checkAllPorts();
  });

  // 日志复制与下载
  btnCopyLog.addEventListener('click', () => {
    const text = terminalOutput.textContent;
    navigator.clipboard?.writeText(text);
    btnCopyLog.textContent = '已复制!';
    setTimeout(() => (btnCopyLog.textContent = '长按复制'), 1500);
  });

  btnDownloadLog.addEventListener('click', () => {
    const blob = new Blob([terminalOutput.textContent], { type: 'text/plain' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `kimi-code-launch-${Date.now()}.log`;
    a.click();
  });

  // 暗黑/浅色模式切换
  const savedTheme = localStorage.getItem(STORAGE_THEME_KEY) || 'theme-light';
  document.body.className = savedTheme;

  btnThemeToggle.addEventListener('click', () => {
    const isDark = document.body.classList.contains('theme-dark');
    const newTheme = isDark ? 'theme-light' : 'theme-dark';
    document.body.className = newTheme;
    localStorage.setItem(STORAGE_THEME_KEY, newTheme);
  });

  // 底部导航栏切换
  document.querySelectorAll('.nav-tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.nav-tab').forEach((t) => t.classList.remove('active'));
      tab.classList.add('active');
      const target = tab.getAttribute('data-tab');
      if (target === 'plugins') {
        appendLog('提示：插件中心支持扩展各种工具和技能库。');
      } else if (target === 'settings') {
        addressModal.classList.remove('hidden');
      } else if (target === 'terminal') {
        terminalOutput.scrollIntoView({ behavior: 'smooth' });
      }
    });
  });

  // 页面加载启动自检
  checkAllPorts();
})();
