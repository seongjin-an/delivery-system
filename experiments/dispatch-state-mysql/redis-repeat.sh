#!/usr/bin/env bash
# 레디스 모드로 다시 띄우고 dispatch-engine kill -9 판을 두 번 더 돈다
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
export DISPATCH_STATE_STORE=redis
( cd "$ROOT" && ./scripts/stop.sh > /dev/null 2>&1; SKIP_INFRA=true SKIP_BUILD=true ./scripts/start.sh 2>&1 | grep -E 'ERROR|완료' )
sleep 60
RATE=10 ./load.sh warmup > /dev/null 2>&1; rm -f results/warmup-*
./kill-repeat.sh redis
