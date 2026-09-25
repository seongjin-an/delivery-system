#!/usr/bin/env bash
# 컨슈머가 5분 동안 멈췄다가 돌아오는 장면을 만든다. 부하는 내내 초당 RATE 건.
#   A 60초: 다 떠 있음 → B 300초 (A, B, C 환경변수로 바꿀 수 있다): geo-indexer 끔 → C 120초: geo-indexer 다시 켬
# k6 를 구간마다 따로 돌려서 구간별 응답 시간을 따로 받는다.
#   ./backlog.sh kafka|rabbit [라벨]
set -u
cd "$(dirname "$0")"
transport=$1; label=${2:-$1}
RATE=${RATE:-3000}
A=${A:-60s}; B=${B:-300s}; C=${C:-120s}
out=results/$label
./sample.sh > "$out-samples.csv" &
sampler=$!
phase() { k6 run -q -e RATE=$RATE -e DURATION=$2 --summary-export "$out-k6-$1.json" load.js > "$out-k6-$1.txt" 2>&1; }

echo "A start $(date +%T)"; phase A $A
./run.sh stop geo; echo "B start $(date +%T) (geo-indexer 끔)"; phase B $B
echo "C start $(date +%T) (geo-indexer 켬)"
./run.sh start "$transport" geo > "$out-geo-start.txt" 2>&1 &
phase C $C
echo "C end $(date +%T)"
sleep 30
kill $sampler
