(function () {
  const PORTS = ['58630', '58627'];
  const DEFAULT_URL = `http://127.0.0.1:${PORTS[0]}/`;
  const STORAGE_URL_KEY = 'kimi_android_server_url';
  const STORAGE_THEME_KEY = 'kimi_android_theme';
  const STORAGE_AUTO_ENTER_KEY = 'kimi_android_auto_enter';
  const RETRY_MS = 2500;

  const $ = (id) => document.getElementById(id);

  const statusIndicator = $('status-indicator');
  const mainStatusTitle = $('main-status-title');
  const mainStatusDesc = $('main-status-desc');
  const btnEnter = $('btn-enter-workspace');
  const btnRestart = $('btn-restart');
  const btnPower = $('btn-power');
  const powerText = $('power-text');
  const metricPort = $('metric-port');
  const metricEnv = $('metric-env');
  const terminalOutput = $('terminal-output');
  const workspaceView = $('workspace-view');
  const workspaceFrame = $('workspace-frame');
  const btnBack = $('btn-back-to-launcher');
  const btnViewAddresses = $('btn-view-addresses');
  const addressModal = $('address-modal');
  const btnCloseModal = $('btn-close-modal');
  const inputCustomUrl = $('input-custom-url');
  const btnApplyCustom = $('btn-apply-custom');
  const btnCopyLog = $('btn-copy-log');
  const btnDownloadLog = $('btn-download-log');
  const btnThemeToggle = $('btn-theme-toggle');
  const offlineHelp = $('offline-help');
  const toggleAutoEnter = $('toggle-auto-enter');

  let currentUrl = localStorage.getItem(STORAGE_URL_KEY) || DEFAULT_URL;
  let isServiceOnline = false;
  let retryTimer = null;
  let probing = false;
  let enteredThisLaunch = false;

  const autoEnter = localStorage.getItem(STORAGE_AUTO_ENTER_KEY) !== '0';

  function appendLog(line) {
    const time = new Date().toLocaleTimeString();
    const code = terminalOutput.querySelector('code');
    code.textContent += `\n[${time}] ${line}`;
    terminalOutput.scrollTop = terminalOutput.scrollHeight;
  }

  function setReadyState(online, port) {
    isServiceOnline = online;
    if (port) metricPort.textContent = port;

    if (online) {
      statusIndicator.className = 'status-indicator online';
      mainStatusTitle.textContent = 'Kimi Code 已就绪，可进入';
      mainStatusDesc.textContent = '本地服务 · kimi-code 2.5.2';
      metricEnv.textContent = 'READY';
      metricEnv.className = 'metric-val text-ready';
      btnEnter.innerHTML = '<span class="play-icon">▶</span> 进入工作台';
      btnEnter.style.background = '#2563eb';
      powerText.textContent = '停止';
      offlineHelp.classList.add('hidden');
    } else {
      statusIndicator.className = 'status-indicator';
      mainStatusTitle.textContent = '正在等待本地服务…';
      mainStatusDesc.textContent = '服务启动后会自动连上，无需手动操作';
      metricEnv.textContent = 'STANDBY';
      metricEnv.className = 'metric-val';
      btnEnter.innerHTML = '<span class="play-icon">↻</span> 立即重试';
      btnEnter.style.background = '#0284c7';
      powerText.textContent = '重试';
      offlineHelp.classList.remove('hidden');
    }
  }

  // 端口可达性探测：no-cors 只关心「连接是否建立」
  async function probe(url) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 2000);
    try {
      await fetch(url, { method: 'HEAD', mode: 'no-cors', cache: 'no-store', signal: controller.signal });
      return true;
    } catch {
      return false;
    } finally {
      clearTimeout(timer);
    }
  }

  function scheduleRetry() {
    if (retryTimer) return;
    retryTimer = setTimeout(() => {
      retryTimer = null;
      if (!isServiceOnline) discover(true);
    }, RETRY_MS);
  }

  async function discover(silent) {
    if (probing) return;
    probing = true;
    try {
      if (!silent) appendLog('正在探测本地服务…');

      const candidates = [currentUrl];
      for (const p of PORTS) {
        const u = `http://127.0.0.1:${p}/`;
        if (!candidates.includes(u)) candidates.push(u);
      }

      for (const url of candidates) {
        if (await probe(url)) {
          const port = (() => {
            try {
              return new URL(url).port || '58630';
            } catch {
              return '58630';
            }
          })();
          const changed = url !== currentUrl;
          currentUrl = url;
          localStorage.setItem(STORAGE_URL_KEY, url);
          setReadyState(true, port);
          if (changed || !silent) appendLog(`已连接: ${url}`);
          if (autoEnter && !enteredThisLaunch) {
            enteredThisLaunch = true;
            appendLog('自动进入工作台…');
            openWorkspace();
          }
          return true;
        }
      }

      setReadyState(false);
      appendLog('未发现本地服务，2.5 秒后自动重试。');
      scheduleRetry();
      return false;
    } finally {
      probing = false;
    }
  }

  function openWorkspace() {
    workspaceFrame.src = currentUrl;
    workspaceView.classList.remove('hidden');
    try {
      history.pushState({ ws: 1 }, '');
    } catch {}
  }

  function closeWorkspace() {
    workspaceView.classList.add('hidden');
    workspaceFrame.src = 'about:blank';
  }

  btnEnter.addEventListener('click', () => {
    if (isServiceOnline) {
      appendLog(`打开工作台: ${currentUrl}`);
      openWorkspace();
    } else {
      appendLog('手动重试…');
      discover(false);
    }
  });

  btnBack.addEventListener('click', () => {
    if (workspaceView.classList.contains('hidden')) return;
    closeWorkspace();
    try {
      history.back();
    } catch {}
  });

  window.addEventListener('popstate', () => {
    if (!workspaceView.classList.contains('hidden')) closeWorkspace();
  });

  btnRestart.addEventListener('click', () => {
    appendLog('重新探测服务…');
    setReadyState(false);
    discover(false);
  });

  btnPower.addEventListener('click', () => {
    if (isServiceOnline) {
      setReadyState(false);
      appendLog('已进入待机，后台仍会继续探测。');
      scheduleRetry();
    } else {
      discover(false);
    }
  });

  btnViewAddresses.addEventListener('click', () => addressModal.classList.remove('hidden'));
  btnCloseModal.addEventListener('click', () => addressModal.classList.add('hidden'));

  document.querySelectorAll('.address-item').forEach((item) => {
    item.addEventListener('click', () => {
      const url = item.getAttribute('data-url');
      navigator.clipboard?.writeText(url);
      const tag = item.querySelector('.copy-tag');
      tag.textContent = '已复制!';
      setTimeout(() => (tag.textContent = '复制'), 1500);
    });
  });

  btnApplyCustom.addEventListener('click', () => {
    let url = inputCustomUrl.value.trim();
    if (!url) return;
    if (!/^https?:\/\//.test(url)) url = `http://${url}`;
    if (!url.endsWith('/')) url += '/';
    currentUrl = url;
    localStorage.setItem(STORAGE_URL_KEY, url);
    addressModal.classList.add('hidden');
    appendLog(`已切换地址: ${url}`);
    discover(false);
  });

  if (toggleAutoEnter) {
    toggleAutoEnter.checked = autoEnter;
    toggleAutoEnter.addEventListener('change', () => {
      localStorage.setItem(STORAGE_AUTO_ENTER_KEY, toggleAutoEnter.checked ? '1' : '0');
    });
  }

  btnCopyLog.addEventListener('click', () => {
    navigator.clipboard?.writeText(terminalOutput.textContent);
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

  const savedTheme = localStorage.getItem(STORAGE_THEME_KEY) || 'theme-light';
  document.body.className = savedTheme;

  btnThemeToggle.addEventListener('click', () => {
    const isDark = document.body.classList.contains('theme-dark');
    const newTheme = isDark ? 'theme-light' : 'theme-dark';
    document.body.className = newTheme;
    localStorage.setItem(STORAGE_THEME_KEY, newTheme);
  });

  document.querySelectorAll('.nav-tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.nav-tab').forEach((t) => t.classList.remove('active'));
      tab.classList.add('active');
      const target = tab.getAttribute('data-tab');
      if (target === 'settings') {
        addressModal.classList.remove('hidden');
      } else if (target === 'terminal') {
        terminalOutput.scrollIntoView({ behavior: 'smooth' });
      } else if (target === 'plugins') {
        appendLog('插件与技能库跟随你的本地 Kimi Code 服务，无需在此单独安装。');
      }
    });
  });

  // 从工作台返回或 App 回到前台时，重新确认服务状态
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && workspaceView.classList.contains('hidden')) discover(true);
  });

  inputCustomUrl.value = currentUrl;
  setReadyState(false);
  discover(false);
})();
