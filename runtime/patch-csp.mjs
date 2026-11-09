#!/usr/bin/env node
// 放宽 Kimi Code 服务端的 CSP frame-ancestors，允许手机 App 的本地 WebView 内嵌工作台。
// 默认的 frame-ancestors 'self' 会让 App 里的 iframe 白屏（跨源被拦截）。
// 这是一个幂等的本地补丁：重复执行不会重复修改。
import fs from 'node:fs';

const TARGETS = [
  'apps/kimi-code/dist/main.mjs',
  'packages/kap-server/src/middleware/securityHeaders.ts',
];

const FROM = "frame-ancestors 'self'\";";
const TO = "frame-ancestors 'self' http://localhost:* http://127.0.0.1:*\";";

let patched = 0;
for (const file of TARGETS) {
  if (!fs.existsSync(file)) continue;
  const src = fs.readFileSync(file, 'utf8');
  if (src.includes(TO)) {
    console.log(`[csp] 已是放宽状态: ${file}`);
    continue;
  }
  if (!src.includes(FROM)) {
    console.log(`[csp] 未找到目标片段，跳过: ${file}`);
    continue;
  }
  fs.writeFileSync(file, src.replace(FROM, TO), 'utf8');
  console.log(`[csp] 已放宽 frame-ancestors: ${file}`);
  patched += 1;
}
process.exit(0);
