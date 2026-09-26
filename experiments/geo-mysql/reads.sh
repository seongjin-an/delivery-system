#!/usr/bin/env bash
# 후보 검색을 초당 50, 200, 500, 1000 건으로 30초씩 부른다. 판마다 k6 결과와 MySQL, 레디스 CPU 를 남긴다.
#   ./reads.sh <라벨>     (dispatch-engine 을 GEO_STORE=redis|mysql 로 띄워둔 상태에서)
set -u
cd "$(dirname "$0")"
label=$1
for rate in ${RATES:-50 200 500 1000}; do
  out=results/$label-$rate
  ( sleep 15; docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' delivery-mysql delivery-redis > "$out-cpu.txt" ) &
  k6 run -q -e RATE=$rate -e DURATION=30s candidates.js > "$out-k6.txt" 2>&1
  wait
  curl -s localhost:8093/actuator/prometheus | grep -E '^candidate_nearby_search_seconds\{' > "$out-server.txt"
  printf '%-5s ' $rate; grep -E 'http_req_duration|checks|dropped_iterations|http_reqs' "$out-k6.txt" | tr -s ' ' | tr '\n' ' '; echo; cat "$out-cpu.txt" | tr '\n' ' '; echo
done
