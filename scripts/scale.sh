#!/usr/bin/env bash
# 이 프로젝트의 핵심 손잡이. 서비스 인스턴스를 N개로 맞춘다.
#
#   ./scripts/scale.sh geo-indexer 4     geo-indexer 를 4개로
#   ./scripts/scale.sh geo-indexer 1     1개로 줄이기 (추가분만 종료)
#
# 포트는 기본포트 + 100*(n-1). 예) geo-indexer: 8092, 8192, 8292, 8392
# 추가 인스턴스는 라벨이 <서비스>-2, -3 ... 이라 로그/PID/그라파나에서 따로 보인다.
#
# 인스턴스를 늘렸는데 처리량이 안 늘어난다면 그 이유를 찾는 게 실습의 목적이다.
# .reference/scaling-scenarios.md 에 시나리오 네 개를 정리해뒀다.
set -euo pipefail
source "$(dirname "$0")/_common.sh"

[[ $# -lt 2 ]] && { echo "사용법: $0 <서비스> <개수>"; echo "서비스: ${SERVICES[*]}"; exit 1; }

SVC=$1; WANT=$2
BASE=$(port_of "$SVC") || { error "모르는 서비스: $SVC"; exit 1; }
[[ "$WANT" =~ ^[0-9]+$ ]] || { error "개수는 숫자로"; exit 1; }

mkdir -p "$LOGS" "$PIDS"

label_of() { [[ $1 -eq 1 ]] && echo "$SVC" || echo "$SVC-$1"; }
port_at()  { echo $(( BASE + 100 * ($1 - 1) )); }

# 늘리기
for ((n=1; n<=WANT; n++)); do
  label=$(label_of "$n")
  port=$(port_at "$n")
  if [[ -f "$PIDS/$label.pid" ]] && kill -0 "$(cat "$PIDS/$label.pid")" 2>/dev/null; then
    warn "$label 은 이미 떠 있다 (:$port)"
    continue
  fi
  start_spring "$SVC" "$port" "$label"
done

# 줄이기 — 뒤에서부터 정리
for ((n=WANT+1; n<=20; n++)); do
  label=$(label_of "$n")
  pid_file="$PIDS/$label.pid"
  [[ -f "$pid_file" ]] || continue
  pid=$(cat "$pid_file")
  if kill -0 "$pid" 2>/dev/null; then
    info "$label 종료 (PID $pid)..."
    kill "$pid"
    for ((i=0; i<10; i++)); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
    success "$label 종료"
  fi
  rm -f "$pid_file"
done

echo ""
success "$SVC 인스턴스 $WANT 개"
warn "프로메테우스가 새 인스턴스를 긁으려면 infra/prometheus/prometheus.yml 의 targets 에"
warn "$(port_at 2) 부터 포트를 추가하고 'docker compose -f infra/compose.yaml restart prometheus' 하면 된다."
