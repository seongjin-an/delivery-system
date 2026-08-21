#!/usr/bin/env bash
# 서비스 종료. 인프라까지 내리려면 STOP_INFRA=true.
# scale.sh 로 띄운 추가 인스턴스(geo-indexer-2 같은)도 같이 정리한다.
set -euo pipefail
source "$(dirname "$0")/_common.sh"

kill_pidfile() {
  local pid_file=$1
  local label; label=$(basename "$pid_file" .pid)
  local pid; pid=$(cat "$pid_file" 2>/dev/null || echo "")

  if [[ -z "$pid" ]]; then rm -f "$pid_file"; return 0; fi

  if kill -0 "$pid" 2>/dev/null; then
    info "$label 종료 (PID $pid)..."
    kill "$pid"
    for ((i=0; i<10; i++)); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    if kill -0 "$pid" 2>/dev/null; then
      warn "$label 이 안 죽는다 — 강제 종료"
      kill -9 "$pid" 2>/dev/null || true
    fi
    success "$label 종료"
  else
    warn "$label (PID $pid) 은 이미 죽어 있었다"
  fi
  rm -f "$pid_file"
}

shopt -s nullglob
for f in "$PIDS"/*.pid; do kill_pidfile "$f"; done
shopt -u nullglob

if [[ "${STOP_INFRA:-}" == "true" ]]; then
  info "인프라 종료..."
  docker compose -f "$COMPOSE_FILE" down
  success "인프라 종료"
else
  echo ""
  warn "컨테이너는 그대로 살아 있다. 같이 내리려면 STOP_INFRA=true ./scripts/stop.sh"
fi
echo ""
success "완료"
