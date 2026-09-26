#!/usr/bin/env bash
# 한 저장소로 판 세 개를 이어서 돈다: 초당 50건, dispatch-engine kill -9, 레디스 kill -9
#   DISPATCH_STATE_STORE=redis|mysql ./run-all.sh <접두어>
cd "$(dirname "$0")"
p=$1
RATE=50 ./load.sh $p-50
KILL=dispatch RATE=20 ./load.sh $p-kill-dispatch
KILL=redis RATE=20 ./load.sh $p-kill-redis
echo "ALL DONE $p"
