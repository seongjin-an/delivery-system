#!/usr/bin/env bash
# MySQL 판 전체: 기동 → 1분 쉼 → 워밍업(버림) → 초당 20건 → 50건 → kill dispatch → kill redis
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
export DISPATCH_STATE_STORE=mysql
( cd "$ROOT" && SKIP_INFRA=true ./scripts/start.sh 2>&1 | grep -E 'ERROR|완료' )
sleep 60
RATE=10 ./load.sh warmup > /dev/null 2>&1; rm -f results/warmup-*
RATE=20 ./load.sh mysql-20
./run-all.sh mysql
