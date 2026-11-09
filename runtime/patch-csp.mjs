#!/usr/bin/env node
// 放宽 Kimi Code 服务端的 CSP frame-ancestors，允许手机 App 的本地 WebView 内嵌工作台。
// 默认的 frame-ancestors 'self' 会让 App 里的 iframe 白屏（跨源被拦截）。
// 幂等且可升级：重复执行不会重复修改，也能从旧补丁升级到新白名单。
import fs from 'node:fs';

const TARGETS = [
  'apps/kimi-code/dist/main.mjs',
  'packages/kap-server/src/middleware/securityHeaders.ts',
];

// 按顺序匹配：旧值 → 新值。只匹配完整字面量，避免误伤语法高亮里的关键词列表。
const CURRENT = "frame-ancestors 'self' http://localhost http://localhost:* http://127.0.0.1:*\";";
const LEGACY = [
  "frame-ancestors 'self'\";",
  "frame-ancestors 'self' http://localhost:* http://127.0.0.1:*\";",
];

for (const file of TARGETS) {
  if (!fs.existsSync(file)) continue;
  let src = fs.readFileSync(file, 'utf8');

  if (src.includes(CURRENT)) {
    console.log(`[csp] 已是目标状态: ${file}`);
    continue;
  }

  const from = LEGACY.find((needle) => src.includes(needle));
  if (!from) {
    console.log(`[csp] 未找到可替换的片段，跳过: ${file}`);
    continue;
  }

  src = src.replace(from, CURRENT);
  fs.writeFileSync(file, src, 'utf8');
  console.log(`[csp] 已更新 frame-ancestors: ${file}`);
}
