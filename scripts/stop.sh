#!/usr/bin/env bash
# 서비스 종료. 인프라까지 내리려면 STOP_INFRA=true.
# scale.sh 로 띄운 추가 인스턴스(geo-indexer-2 같은)도 같이 정리한다.
#
#   ./scripts/stop.sh                       전부
#   ./scripts/stop.sh settlement-service    그 서비스만 (추가 인스턴스 포함). 인프라는 안 건드린다
#
# 예전엔 인자를 안 봐서, 기능 정의서 SE-03(정산 리플레이) 절차대로 settlement-service 만 멈추려고
# 이름을 줘도 서비스 8개가 다 내려갔다.
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
if [[ $# -gt 0 ]]; then
  for svc in "$@"; do
    port_of "$svc" >/dev/null || { error "모르는 서비스: $svc"; exit 1; }
    files=("$PIDS/$svc.pid" "$PIDS/$svc"-[0-9]*.pid)
    # [[ -f ]] && ... 로 쓰면 파일이 없을 때 반복문이 실패로 끝나서 set -e 에 걸려 조용히 죽는다
    for f in "${files[@]}"; do
      if [[ -f "$f" ]]; then kill_pidfile "$f"; fi
    done
  done
  shopt -u nullglob
  success "완료"
  exit 0
fi
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
