#!/usr/bin/env node
/**
 * phone-mcp — 把手机操控暴露成 MCP 工具。
 *
 * kimi-cli 启动时会读 ~/.kimi-code/mcp.json，以 stdio 方式拉起本进程。
 * 模型看到的是 phone_screen / phone_tap / phone_type 这些原生工具，
 * 而不是「教它敲一条 shell 命令」。
 *
 * 数据面：POST http://127.0.0.1:<port>/phone/<tool>，带 X-Phone-Token。
 * 端口与 token 由 App 写在 ~/.kimi-code/phone.json。
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const CONFIG = path.join(os.homedir(), '.kimi-code', 'phone.json');

function endpoint() {
  try {
    return JSON.parse(fs.readFileSync(CONFIG, 'utf8'));
  } catch {
    return null;
  }
}

function call(tool, args) {
  return new Promise((resolve) => {
    const cfg = endpoint();
    if (!cfg || !cfg.url) {
      resolve({ ok: false, error: '手机桥未就绪：App 里「设置 → 能力 → 手机操控」打开一次即可。' });
      return;
    }
    const body = JSON.stringify(args || {});
    const url = new URL(cfg.url + '/phone/' + tool);
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(body),
          'X-Phone-Token': cfg.token || '',
        },
        timeout: 20000,
      },
      (res) => {
        let text = '';
        res.setEncoding('utf8');
        res.on('data', (c) => (text += c));
        res.on('end', () => {
          try {
            resolve(JSON.parse(text));
          } catch {
            resolve({ ok: false, error: '手机桥返回了无法解析的内容：' + text.slice(0, 200) });
          }
        });
      },
    );
    req.on('timeout', () => {
      req.destroy();
      resolve({ ok: false, error: '手机桥超时（20 秒）' });
    });
    req.on('error', (e) => resolve({ ok: false, error: '连不上手机桥：' + e.message }));
    req.write(body);
    req.end();
  });
}

const TOOLS = [
  {
    name: 'phone_screen',
    description:
      '读取当前手机屏幕上的可见文字与控件，返回应用名和一行行「文字 @(x,y)」，可点的会标[可点]，输入框标[输入框]。做任何操作前先看一次屏幕。',
    inputSchema: { type: 'object', properties: { limit: { type: 'integer', description: '最多返回多少个控件，默认 120' } } },
  },
  {
    name: 'phone_tap',
    description: '按屏幕上的文字或控件描述点击。优先用这个，比坐标稳。找不到会返回提示。',
    inputSchema: {
      type: 'object',
      properties: { text: { type: 'string', description: '要点的文字，例如「发送」「搜索」' } },
      required: ['text'],
    },
  },
  {
    name: 'phone_tap_xy',
    description: '按屏幕坐标点击，坐标来自 phone_screen 的 @(x,y)。',
    inputSchema: {
      type: 'object',
      properties: { x: { type: 'integer' }, y: { type: 'integer' } },
      required: ['x', 'y'],
    },
  },
  {
    name: 'phone_type',
    description: '往当前焦点输入框写入文字（会替换原内容）。先 tap 输入框再调用。',
    inputSchema: {
      type: 'object',
      properties: { text: { type: 'string' } },
      required: ['text'],
    },
  },
  {
    name: 'phone_scroll',
    description: '滚动当前页面：down / up。',
    inputSchema: {
      type: 'object',
      properties: { direction: { type: 'string', enum: ['down', 'up'] } },
      required: ['direction'],
    },
  },
  {
    name: 'phone_key',
    description: '按系统键：back 返回、home 回桌面、recents 多任务、notifications 通知栏。',
    inputSchema: {
      type: 'object',
      properties: { name: { type: 'string', enum: ['back', 'home', 'recents', 'notifications'] } },
      required: ['name'],
    },
  },
  {
    name: 'phone_open',
    description: '按应用名或包名打开一个 App，例如「微信」「com.tencent.mm」。',
    inputSchema: {
      type: 'object',
      properties: { target: { type: 'string' } },
      required: ['target'],
    },
  },
  {
    name: 'phone_apps',
    description: '列出手机里已安装、有桌面的 App，可按关键词过滤。',
    inputSchema: {
      type: 'object',
      properties: { query: { type: 'string' }, limit: { type: 'integer' } },
    },
  },
  {
    name: 'phone_clipboard',
    description: '读或写剪贴板。read=true 读，否则把 text 写进去。',
    inputSchema: {
      type: 'object',
      properties: { read: { type: 'boolean' }, text: { type: 'string' } },
    },
  },
  {
    name: 'phone_screenshot',
    description: '截当前屏幕，返回 base64 JPEG（需要 Android 11+，且模型支持看图才有意义）。',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'phone_swipe',
    description: '从 (x1,y1) 滑到 (x2,y2)，用于拖动、解锁、翻页。',
    inputSchema: {
      type: 'object',
      properties: {
        x1: { type: 'integer' }, y1: { type: 'integer' },
        x2: { type: 'integer' }, y2: { type: 'integer' },
        duration_ms: { type: 'integer', description: '默认 320' },
      },
      required: ['x1', 'y1', 'x2', 'y2'],
    },
  },
];

function reply(id, result) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
}

function fail(id, code, message) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n');
}

let buffer = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', async (chunk) => {
  buffer += chunk;
  let idx;
  while ((idx = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, idx).trim();
    buffer = buffer.slice(idx + 1);
    if (!line) continue;
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      continue;
    }
    await handle(msg);
  }
});

async function handle(msg) {
  const { id, method, params } = msg || {};
  if (method === 'initialize') {
    reply(id, {
      protocolVersion: '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'phone', version: '1.0.0' },
    });
    return;
  }
  if (method === 'notifications/initialized' || method === 'initialized') return;
  if (method === 'ping') {
    reply(id, {});
    return;
  }
  if (method === 'tools/list') {
    reply(id, { tools: TOOLS });
    return;
  }
  if (method === 'tools/call') {
    const name = params && params.name;
    const tool = TOOLS.find((t) => t.name === name);
    if (!tool) {
      fail(id, -32602, 'unknown tool: ' + name);
      return;
    }
    const args = (params && params.arguments) || {};
    const res = await call(name.replace(/^phone_/, ''), args);
    const text = res.ok ? String(res.result || '') : String(res.error || '执行失败');
    reply(id, { content: [{ type: 'text', text }], isError: !res.ok });
    return;
  }
  if (id !== undefined) fail(id, -32601, 'method not found: ' + method);
}
