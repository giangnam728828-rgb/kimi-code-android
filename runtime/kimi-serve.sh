#!/usr/bin/env bash
# ==============================================================================
# Kimi Code 本地服务守护脚本
#
#   kimi-serve.sh start      启动服务（已在运行则跳过）
#   kimi-serve.sh ensure     静默确保服务在运行（终端打开时自动调用，不阻塞）
#   kimi-serve.sh status     查看状态
#   kimi-serve.sh stop       停止服务
#   kimi-serve.sh restart    重启服务
#   kimi-serve.sh logs       实时查看日志
#   kimi-serve.sh supervise  前台守护循环（内部使用）
# ==============================================================================

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="$DIR/.run"
PORT="${PORT:-58630}"
HOST="${HOST:-0.0.0.0}"
PID_FILE="$RUN_DIR/kimi-web-$PORT.pid"
LOG_FILE="$RUN_DIR/kimi-web-$PORT.log"

mkdir -p "$RUN_DIR"

# --- 探测端口是否已就绪（不依赖 curl，纯 bash） -------------------------------
port_open() {
  (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && exec 3<&- && exec 3>&- && return 0
  return 1
}

# --- 读取记录的 pid，并确认进程还活着 ----------------------------------------
pid_alive() {
  [ -f "$PID_FILE" ] || return 1
  local p
  p="$(cat "$PID_FILE" 2>/dev/null)"
  [ -n "$p" ] || return 1
  kill -0 "$p" 2>/dev/null
}

is_up() {
  port_open
}

# --- 前台运行真正的服务进程 ---------------------------------------------------
run_foreground() {
  cd "$DIR" || exit 1
  if [ -f "$DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$DIR/.env"
    set +a
  fi
  # 允许手机 App 的 WebView 内嵌工作台（幂等补丁）
  node "$DIR/patch-csp.mjs" >/dev/null 2>&1 || true
  exec node "$DIR/apps/kimi-code/dist/main.mjs" web \
    --port "$PORT" \
    --host "$HOST" \
    --dangerous-bypass-auth \
    --no-open \
    --web-title "My Kimi Code"
}

# --- 后台启动（真正干活的那一层，供 supervise 调用） --------------------------
spawn_background() {
  cd "$DIR" || exit 1
  nohup setsid bash "$DIR/kimi-serve.sh" supervise \
    >>"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
}

# --- 守护循环：进程退出 3 秒后自动拉起 ---------------------------------------
supervise() {
  cd "$DIR" || exit 1
  while :; do
    echo "[$(date '+%F %T')] starting kimi web on $HOST:$PORT"
    bash "$DIR/kimi-serve.sh" run-foreground >>"$LOG_FILE" 2>&1
    echo "[$(date '+%F %T')] server exited (code $?), restarting in 3s"
    sleep 3
  done
}

# --- 启动并等待就绪 -----------------------------------------------------------
csp_ok() {
  local hdr
  hdr=$(curl -s -D - -o /dev/null --max-time 3 "http://127.0.0.1:$PORT/" 2>/dev/null | tr -d '\r' | grep -i '^content-security-policy:')
  case "$hdr" in
    *"http://localhost"*) return 0 ;;
    *) return 1 ;;
  esac
}

start() {
  if is_up; then
    echo "✅ Kimi Code 服务已在运行: http://127.0.0.1:$PORT/"
    if ! csp_ok; then
      echo "⚠️  当前实例是补丁前的旧进程，App 内嵌工作台会白屏。"
      echo "   请重启 PocketDev 容器后重新执行: bash $DIR/kimi-serve.sh start"
    fi
    return 0
  fi
  if pid_alive; then
    echo "⏳ 服务进程存在，等待端口就绪..."
  else
    echo "🚀 正在启动 Kimi Code 服务..."
    spawn_background
  fi
  local i=0
  while [ "$i" -lt 60 ]; do
    if is_up; then
      echo "✅ 已就绪: http://127.0.0.1:$PORT/"
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "❌ 启动超时，请查看日志: bash $DIR/kimi-serve.sh logs"
  return 1
}

# --- 静默确保（终端打开时调用，立刻返回，绝不阻塞） --------------------------
ensure() {
  is_up && return 0
  if ! pid_alive; then
    spawn_background >/dev/null 2>&1
  fi
  return 0
}

stop() {
  if pid_alive; then
    local p
    p="$(cat "$PID_FILE")"
    # 杀掉守护循环及其子进程
    pkill -TERM -P "$p" 2>/dev/null
    kill -TERM "$p" 2>/dev/null
    sleep 1
    kill -KILL "$p" 2>/dev/null
  fi
  rm -f "$PID_FILE"
  sleep 1
  if is_up; then
    echo "⚠️  端口 $PORT 仍被占用（进程来自另一个终端会话，本会话无法直接终止）。"
    echo "   请到启动服务的那个终端按 Ctrl+C，或重启 PocketDev 容器。"
    return 1
  fi
  echo "🛑 已停止"
}

status() {
  if is_up; then
    echo "✅ 运行中  http://127.0.0.1:$PORT/  (pid $(cat "$PID_FILE" 2>/dev/null || echo '?'))"
  else
    echo "⭕ 未运行"
  fi
}

case "${1:-ensure}" in
  start)          start ;;
  ensure)         ensure ;;
  stop)           stop ;;
  restart)        stop; sleep 1; start ;;
  status)         status ;;
  logs)           tail -f "$LOG_FILE" ;;
  supervise)      supervise ;;
  run-foreground) run_foreground ;;
  *)
    echo "用法: $0 {start|ensure|stop|restart|status|logs}"
    exit 1
    ;;
esac
