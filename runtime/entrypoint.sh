#!/bin/bash
# Kimi Code mobile runtime entrypoint.
#
# Started by the Android app through proot. Every knob is an environment
# variable so the app never has to edit this file:
#   KIMI_PORT      TCP port the web server listens on (default 58630)
#   KIMI_HOST      bind address; 0.0.0.0 when LAN access is enabled
#   KIMI_WORKDIR   workspace directory the agent opens
set -u

export HOME=/root
export KIMI_CODE_HOME=/root/.kimi-code
export TERM=xterm-256color
export LANG=C.UTF-8
export PATH=/root/.kimi-code/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

KIMI_DIST=/opt/kimi/dist/main.mjs
PORT="${KIMI_PORT:-58630}"
HOST="${KIMI_HOST:-127.0.0.1}"
WORKDIR="${KIMI_WORKDIR:-/root/workspace}"

mkdir -p "$KIMI_CODE_HOME/bin" "$KIMI_CODE_HOME/logs" "$WORKDIR"

cd "$WORKDIR" 2>/dev/null || cd /root

exec /opt/node/bin/node "$KIMI_DIST" web --host "$HOST" --port "$PORT" --no-open "$@"
