#!/usr/bin/env bash
# 실험용으로 location-ingest 와 geo-indexer 만 띄우고 내린다.
#   ./run.sh start kafka|rabbit [ingest|geo|both]
#   ./run.sh stop [ingest|geo|both]
#
# OTel 에이전트는 안 붙인다. 수집기를 안 띄운 채로 붙이면 내보내기 실패 로그가 쌓이고,
# 에이전트 자체 오버헤드도 재는 값에 섞인다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
LOGS="$ROOT/logs"; PIDS="$ROOT/pids"; mkdir -p "$LOGS" "$PIDS"

jar_of() { find "$ROOT/services/$1/build/libs" -name '*.jar' ! -name '*plain*' | head -1; }

start_one() {
  local svc=$1 port=$2 transport=$3
  INGEST_TRANSPORT=$transport SERVER_PORT=$port \
    LOCATION_QUEUE_TTL_MS=${LOCATION_QUEUE_TTL_MS:-0} LOCATION_QUEUE_MAX_LENGTH=${LOCATION_QUEUE_MAX_LENGTH:-0} \
    "$JAVA" -Xmx1g -jar "$(jar_of "$svc")" > "$LOGS/$svc.log" 2>&1 &
  echo $! > "$PIDS/$svc.pid"
  for i in $(seq 90); do curl -sf "localhost:$port/actuator/health" >/dev/null && { echo "$svc up ($transport)"; return; }; sleep 1; done
  echo "$svc 안 떴다 — $LOGS/$svc.log"; exit 1
}

stop_one() {
  local f="$PIDS/$1.pid"
  [[ -f $f ]] || return 0
  kill "$(cat "$f")" 2>/dev/null || true
  for i in $(seq 15); do kill -0 "$(cat "$f")" 2>/dev/null || break; sleep 1; done
  rm -f "$f"; echo "$1 down"
}

cmd=$1; shift
case $cmd in
  start)
    transport=$1; which=${2:-both}
    [[ $which != geo ]] && start_one location-ingest 8091 "$transport"
    [[ $which != ingest ]] && start_one geo-indexer 8092 "$transport"
    ;;
  stop)
    which=${1:-both}
    [[ $which != geo ]] && stop_one location-ingest
    [[ $which != ingest ]] && stop_one geo-indexer
    ;;
esac
