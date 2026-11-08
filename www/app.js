(function () {
  const DEFAULT_URL = 'http://127.0.0.1:58630/';
  const STORAGE_KEY = 'kimi_android_server_url';

  const serverInput = document.getElementById('server-input');
  const btnConnect = document.getElementById('btn-connect');
  const statusPill = document.getElementById('status-pill');
  const statusText = document.getElementById('status-text');
  const offlinePanel = document.getElementById('offline-panel');
  const webviewContainer = document.getElementById('webview-container');
  const agentFrame = document.getElementById('agent-frame');
  const toggleMenu = document.getElementById('toggle-menu');
  const fabMenu = document.getElementById('fab-menu');
  const fabToggle = document.getElementById('fab-toggle');
  const fabOptions = document.getElementById('fab-options');
  const fabReload = document.getElementById('fab-reload');
  const fabSettings = document.getElementById('fab-settings');
  const fabFullscreen = document.getElementById('fab-fullscreen');
  const header = document.getElementById('header');
  const copyBtn = document.getElementById('btn-copy-cmd');

  let currentUrl = localStorage.getItem(STORAGE_KEY) || DEFAULT_URL;
  serverInput.value = currentUrl;

  function updateStatus(state, message) {
    statusPill.className = 'status-pill ' + state;
    statusText.textContent = message;
  }

  // 探测后端服务是否已准备就绪
  async function checkServer(url) {
    updateStatus('checking', '检测中...');
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 2000);
      
      // 尝试 ping /api/v1/meta 或直接抓取根目录
      const res = await fetch(url, { method: 'HEAD', mode: 'no-cors', signal: controller.signal });
      clearTimeout(timeoutId);

      updateStatus('online', '已连接');
      return true;
    } catch (e) {
      updateStatus('offline', '离线');
      return false;
    }
  }

  // 连接到指定服务
  async function connectToServer(url) {
    if (!url.endsWith('/')) url += '/';
    currentUrl = url;
    localStorage.setItem(STORAGE_KEY, url);
    serverInput.value = url;

    const isAlive = await checkServer(url);
    if (isAlive) {
      loadIframe(url);
    } else {
      showOffline();
    }
  }

  function loadIframe(url) {
    agentFrame.src = url;
    offlinePanel.classList.add('hidden');
    webviewContainer.classList.remove('hidden');
    fabMenu.classList.remove('hidden');
    header.style.display = 'none'; // 全屏模式隐藏顶栏
  }

  function showOffline() {
    agentFrame.src = 'about:blank';
    webviewContainer.classList.add('hidden');
    offlinePanel.classList.remove('hidden');
    fabMenu.classList.add('hidden');
    header.style.display = 'flex';
  }

  // 绑定事件
  btnConnect.addEventListener('click', () => {
    connectToServer(serverInput.value.trim());
  });

  document.querySelectorAll('.chip').forEach((chip) => {
    chip.addEventListener('click', () => {
      const port = chip.getAttribute('data-port');
      serverInput.value = `http://127.0.0.1:${port}/`;
      connectToServer(serverInput.value);
    });
  });

  toggleMenu.addEventListener('click', () => {
    offlinePanel.classList.toggle('hidden');
  });

  fabToggle.addEventListener('click', () => {
    fabOptions.classList.toggle('hidden');
  });

  fabReload.addEventListener('click', () => {
    fabOptions.classList.add('hidden');
    agentFrame.contentWindow?.location.reload();
  });

  fabSettings.addEventListener('click', () => {
    fabOptions.classList.add('hidden');
    showOffline();
  });

  fabFullscreen.addEventListener('click', () => {
    fabOptions.classList.add('hidden');
    if (header.style.display === 'none') {
      header.style.display = 'flex';
    } else {
      header.style.display = 'none';
    }
  });

  if (copyBtn) {
    copyBtn.addEventListener('click', () => {
      navigator.clipboard?.writeText('/workspace/kimicode/start-web.sh');
      copyBtn.textContent = '已复制!';
      setTimeout(() => (copyBtn.textContent = '复制'), 2000);
    });
  }

  // 启动时自动尝试探测连接 58630
  checkServer(currentUrl).then((online) => {
    if (online) {
      loadIframe(currentUrl);
    } else {
      // 如果 58630 没开，再顺带检测一下 58627
      checkServer('http://127.0.0.1:58627/').then((altOnline) => {
        if (altOnline) {
          connectToServer('http://127.0.0.1:58627/');
        }
      });
    }
  });
})();
