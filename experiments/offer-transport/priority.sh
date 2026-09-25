#!/usr/bin/env bash
# 우선순위 실험. 마케팅 1만 건을 넣은 직후 주문 하나를 넣고, 그 주문이 배차되기까지 몇 초 걸리나 본다.
#   ./priority.sh <라벨>
# 라이더 100명은 전원 즉시 수락한다. 푸시만 제때 닿으면 첫 제안에 배차된다.
set -u
cd "$(dirname "$0")"
label=$1
out=results/$label
metrics() { curl -s localhost:8095/actuator/prometheus | grep -E '^push_(offer_age_seconds_(count|sum|max)|expired_total|ratelimited_total|sent_total)'; }

# 앞 판 라이더가 레디스에 IDLE 로 남아 있으면 제안이 그쪽으로 간다. 새 시뮬레이터는 그 라이더를 몰라서
# 푸시가 나가도 아무도 안 받는다(실제로 한 판이 이것 때문에 다섯 번째 제안에서야 배차됐다).
# 오프라인 정리(30초)가 치울 때까지 기다린다. 10 은 예전 테스트로 DELIVERING 에 남은 라이더 몫이다.
until [[ $(docker exec delivery-redis redis-cli ZCARD riders:online) -le 10 ]]; do sleep 3; done
curl -s -X POST localhost:8097/sim/start -H 'Content-Type: application/json' \
  -d '{"riders":100,"ordersPerSec":0,"acceptRate":1,"rejectRate":0,"centerLat":37.498095,"centerLng":127.027610,"spreadKm":1.5,"durationSec":150}' > /dev/null
sleep 15
metrics > "$out-before.txt"
t0=$(python3 -c 'import time; print(time.time())')
curl -s -X POST localhost:8095/api/notify/marketing -H 'Content-Type: application/json' \
  -d '{"count":10000,"message":"점심 할인"}' > /dev/null
t1=$(python3 -c 'import time; print(time.time())')
order=$(curl -s -X POST localhost:8090/api/orders -H 'Content-Type: application/json' -H "Idempotency-Key: prio-$label-$(date +%s)" \
  -d '{"storeId":"store-001","storeLat":37.498095,"storeLng":127.027610,"destLat":37.504198,"destLng":127.048985,"priceKrw":18000}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["orderId"])')
echo "마케팅 1만 건 넣는 데 $(python3 -c "print(round($t1-$t0,2))")초, 주문 $order" | tee "$out-result.txt"
for i in $(seq 90); do
  s=$(curl -s localhost:8090/api/orders/$order | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; print(d["status"], d.get("attempt"))')
  case $s in
    ASSIGNED*|PICKED_UP*|DELIVERED*|FAILED*)
      echo "주문 뒤 $(python3 -c "import time; print(round(time.time()-$t1,1))")초에 $s" | tee -a "$out-result.txt"; break ;;
  esac
  sleep 1
done
metrics > "$out-after.txt"
paste -d' ' <(awk '{print $1}' "$out-after.txt") <(awk '{print $2}' "$out-before.txt") <(awk '{print $2}' "$out-after.txt") \
  | awk '{printf "%-70s %s\n", $1, ($1 ~ /_max/ ? $3 : $3-$2)}' | tee -a "$out-result.txt"
curl -s localhost:8097/sim/status | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; print("sim:", {k: d.get(k) for k in ("accepted","unknownRider","failures")})' | tee -a "$out-result.txt"
curl -s -X POST localhost:8097/sim/stop > /dev/null
