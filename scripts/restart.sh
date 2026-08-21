#!/usr/bin/env bash
# 서비스 하나만 재빌드 + 재기동.
#   ./scripts/restart.sh dispatch-engine
#   ./scripts/restart.sh dispatch-engine --skip-build
set -euo pipefail
source "$(dirname "$0")/_common.sh"

[[ $# -lt 1 ]] && { echo "사용법: $0 <서비스> [--skip-build]"; echo "서비스: ${SERVICES[*]}"; exit 1; }

SVC=$1
PORT=$(port_of "$SVC") || { error "모르는 서비스: $SVC"; exit 1; }

if [[ -f "$PIDS/$SVC.pid" ]]; then
  pid=$(cat "$PIDS/$SVC.pid")
  if kill -0 "$pid" 2>/dev/null; then
    info "$SVC 종료 (PID $pid)..."
    kill "$pid"
    for ((i=0; i<10; i++)); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PIDS/$SVC.pid"
fi

if [[ "${2:-}" != "--skip-build" ]]; then
  info "$SVC 빌드..."
  ( cd "$ROOT" && ./gradlew ":$SVC:bootJar" -x test -q )
fi

mkdir -p "$LOGS" "$PIDS"
start_spring "$SVC" "$PORT"
