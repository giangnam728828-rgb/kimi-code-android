#!/usr/bin/env bash
# ==============================================================================
# 自定义 Kimi Code Web 启动脚本 (手机本地运行，免 Token 密码)
# ==============================================================================

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

# 加载 .env 环境变量（如果存在）
if [ -f "$DIR/.env" ]; then
  set -a
  source "$DIR/.env"
  set +a
fi

PORT="${PORT:-58630}"
HOST="${HOST:-0.0.0.0}"

echo "=================================================="
echo "  🚀 正在启动属于你自己的 Kimi Code Web 服务..."
echo "  🌐 端口: $PORT (免密码 / 免 Token)"
echo "  📱 手机浏览器访问: http://127.0.0.1:$PORT/"
echo "=================================================="

exec /workspace/kimicode/apps/kimi-code/dist/main.mjs web \
  --port "$PORT" \
  --host "$HOST" \
  --dangerous-bypass-auth \
  --no-open \
  --web-title "My Kimi Code" \
  "$@"
