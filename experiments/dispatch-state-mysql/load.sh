#!/usr/bin/env bash
# 한 판: 라이더 2000명(수락 60%, 거절 20%, 무응답 20%), 주문 초당 RATE 건을 60초.
#   DISPATCH_STATE_STORE=redis|mysql 로 전체를 띄워둔 상태에서
#   RATE=20 ./load.sh <라벨>
#   KILL=dispatch RATE=20 ./load.sh <라벨>   20초째 dispatch-engine kill -9, 10초 뒤 다시 띄움
#   KILL=redis    RATE=20 ./load.sh <라벨>   20초째 레디스 컨테이너 kill -9, 5초 뒤 다시 띄움
set -u
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
label=$1; RATE=${RATE:-20}; out=results/$label
q() { docker exec delivery-mysql mysql -udev_user -pdev_password delivery -N -e "$1" 2>/dev/null; }
scrape() { { curl -s localhost:8093/actuator/prometheus; curl -s localhost:8094/actuator/prometheus; } | grep -E '^(dispatch|relay|offer_accept|offer_reject)_duration_seconds_(bucket|count|sum)'; }

# 앞 판 라이더가 레디스에 IDLE 로 남아 있으면 없는 라이더에게 제안이 간다. 오프라인 정리가 치울 때까지 기다린다
# DELIVERING 으로 갇힌 라이더는 빼고 센다. 한가하지 않아서 후보로 안 뽑히니 결과를 흔들지 않는다.
# (워밍업 판에서 수락이 서버에선 됐는데 시뮬레이터가 3초 타임아웃으로 실패로 보고 픽업을 안 가서 87명이 갇혔다)
idle_online() {
  docker exec delivery-redis sh -c 'for id in $(redis-cli ZRANGE riders:online 0 -1); do redis-cli HGET rider:state:$id status; done' 2>/dev/null | grep -vc DELIVERING
}
until [[ $(idle_online) -le 10 ]]; do sleep 3; done
scrape > "$out-before.txt"
before=$(q "select coalesce(max(order_id),0) from orders")
echo "start $(date +%T) store=${DISPATCH_STATE_STORE:-redis} rate=$RATE kill=${KILL:-none}" | tee "$out-timeline.txt"
curl -s -X POST localhost:8097/sim/start -H 'Content-Type: application/json' \
  -d "{\"riders\":2000,\"ordersPerSec\":$RATE,\"acceptRate\":0.6,\"rejectRate\":0.2,\"durationSec\":60}" > /dev/null
( sleep 30; docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' delivery-mysql delivery-redis > "$out-cpu.txt" ) &

case ${KILL:-none} in
  dispatch)
    sleep 20; kill -9 "$(cat "$ROOT/pids/dispatch-engine.pid")"; echo "kill -9 dispatch-engine $(date +%T)" | tee -a "$out-timeline.txt"
    : > "$out-before.txt"
    sleep 10; "$ROOT/scripts/restart.sh" dispatch-engine --skip-build > /dev/null; echo "dispatch-engine up $(date +%T)" | tee -a "$out-timeline.txt"
    sleep 100 ;;
  redis)
    sleep 20; docker kill -s KILL delivery-redis > /dev/null; echo "kill -9 redis $(date +%T)" | tee -a "$out-timeline.txt"
    sleep 5; docker start delivery-redis > /dev/null; echo "redis up $(date +%T)" | tee -a "$out-timeline.txt"
    sleep 105 ;;
  *) sleep 130 ;;
esac
wait

# 주문이 다 끝날 때까지 기다린다. 배달 완료까지 가면 35초가 더 걸린다(픽업 5초 + 완료 30초)
for i in $(seq 8); do
  left=$(q "select count(*) from orders where order_id > $before and status in ('CREATED','DISPATCHING')")
  [[ "$left" == "0" ]] && break
  echo "아직 배차 전이거나 배차 중 $left 건, 30초 더 $(date +%T)" | tee -a "$out-timeline.txt"; sleep 30
done
echo "end $(date +%T)" | tee -a "$out-timeline.txt"
scrape > "$out-after.txt"
q "select status, count(*), round(avg(attempt),2) from orders where order_id > $before group by status" | tee "$out-orders.txt"
curl -s localhost:8097/sim/status > "$out-sim.json"
for m in dispatch_duration relay_duration offer_accept_duration offer_reject_duration; do
  python3 hist.py $m "$out-before.txt" "$out-after.txt"
done | tee "$out-latency.txt"
cat "$out-cpu.txt" | tr '\n' ' '; echo
