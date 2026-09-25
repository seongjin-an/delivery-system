#!/usr/bin/env bash
# 타이머 실험. 아무도 안 받게 해서 모든 제안이 10초 타이머로만 끝나게 한다.
#   OFFER_TRANSPORT=rabbit|kafka 로 전체를 띄워둔 상태에서
#   ./timer.sh <라벨>                 그냥 한 판
#   KILL_RELAY=true ./timer.sh <라벨> 25초째 offer-relay 를 kill -9, 20초 뒤 다시 띄움
#
# 한 판: 라이더 1000명, 주문 초당 10건 60초. 주문마다 제안 5번 × 10초라 마지막 주문은 110초쯤 끝난다.
# 지표는 offer-relay 를 재시작하지 않고 판 전후 구간 누적값의 차이로 본다(histogram.py).
set -u
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
label=$1
out=results/$label
q() { docker exec delivery-mysql mysql -udev_user -pdev_password delivery -N -e "$1" 2>/dev/null; }
scrape() { curl -s localhost:8094/actuator/prometheus | grep -E '^offer_(relay_delay_seconds_(bucket|count|sum)|timer_forwarded)'; }

scrape > "$out-before.txt"
before=$(q "select coalesce(max(order_id),0) from orders")
echo "start $(date +%T) transport=${OFFER_TRANSPORT:-rabbit} kill=${KILL_RELAY:-false} before=$before" | tee "$out-timeline.txt"

curl -s -X POST localhost:8097/sim/start -H 'Content-Type: application/json' \
  -d '{"riders":1000,"ordersPerSec":10,"acceptRate":0,"rejectRate":0,"durationSec":60}' > /dev/null

if [[ "${KILL_RELAY:-false}" == "true" ]]; then
  sleep 25
  kill -9 "$(cat "$ROOT/pids/offer-relay.pid")"; echo "kill -9 offer-relay $(date +%T)" | tee -a "$out-timeline.txt"
  : > "$out-before.txt"   # 죽였으니 누적값이 0 부터 다시 센다
  sleep 20
  "$ROOT/scripts/restart.sh" offer-relay --skip-build > /dev/null; echo "offer-relay up $(date +%T)" | tee -a "$out-timeline.txt"
  sleep 85
else
  sleep 130
fi

for i in 1 2 3 4; do
  left=$(q "select count(*) from orders where order_id > $before and status in ('CREATED','DISPATCHING')")
  [[ "$left" == "0" ]] && break
  echo "아직 배차 중 $left 건, 30초 더 $(date +%T)" | tee -a "$out-timeline.txt"; sleep 30
done
echo "end $(date +%T)" | tee -a "$out-timeline.txt"

scrape > "$out-after.txt"
q "select status, count(*), round(avg(attempt),2) from orders where order_id > $before group by status" > "$out-orders.txt"
curl -s localhost:8097/sim/status > "$out-sim.json"
cat "$out-orders.txt"
python3 histogram.py "$out-before.txt" "$out-after.txt" | tee "$out-delay.txt"
