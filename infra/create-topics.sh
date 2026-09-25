#!/usr/bin/env bash
# 카프카 토픽 명시 생성 (멱등). broker auto-create 를 끈 상태라 서비스 기동 전 필수.
#
# 파티션 수를 여기서 못 박는 이유: auto-create 는 파티션 1개로 만든다. 그러면 컨슈머를
# 몇 개 띄워도 하나만 일하게 되고, "인스턴스를 늘렸는데 처리량이 안 늘어난다"는
# 시나리오 B 실험 자체가 성립하지 않는다.
#
# scripts/start.sh 가 인프라 기동 직후 호출한다.
set -euo pipefail

CONTAINER="${KAFKA_CONTAINER:-delivery-kafka}"
BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9094}"

if docker exec "$CONTAINER" bash -lc 'command -v kafka-topics' >/dev/null 2>&1; then
  KT="kafka-topics"
else
  KT="/opt/kafka/bin/kafka-topics.sh"
fi

echo "[create-topics] 브로커 대기: $CONTAINER / $BOOTSTRAP ..."
until docker exec "$CONTAINER" $KT --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; do
  sleep 2
done

# macOS 기본 bash(3.2)에서는 set -u 아래에서 빈 배열을 "${arr[@]}" 로 펼치면
# unbound variable 로 죽는다. ${arr[@]+"${arr[@]}"} 로 감싸야 한다 — 실제로 여기서 터졌다.
create() {
  local name=$1 parts=$2 rf=$3
  shift 3
  local cfg=()
  for c in "$@"; do cfg+=(--config "$c"); done
  docker exec "$CONTAINER" $KT --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists --topic "$name" --partitions "$parts" --replication-factor "$rf" \
    ${cfg[@]+"${cfg[@]}"} >/dev/null
  echo "[create-topics] ok  $name (partitions=$parts, rf=$rf) ${*:+[$*]}"
}

# ── 위치 스트림 ─────────────────────────────────────────────────────────────
# 가장 양이 많고 가장 빨리 상한다. 30분만 들고 있으면 충분해서 retention 을 짧게 잡았다.
# lz4 압축은 컨슈머 랙이 쌓일 때 디스크가 먼저 터지는 걸 막아준다.
create rider.location 6 1 retention.ms=1800000 compression.type=lz4

# ── 주문 파이프라인 ─────────────────────────────────────────────────────────
# 정산이 과거분을 다시 읽어야 하니 넉넉히 보관한다(7일).
create order.created       6 1 retention.ms=604800000
create order.status        6 1 retention.ms=604800000
create dispatch.assigned   6 1 retention.ms=604800000
create dispatch.failed     3 1 retention.ms=604800000
create delivery.completed  6 1 retention.ms=604800000

# ── DLT (Spring Kafka 규약: 원본토픽 + ".DLT") ──────────────────────────────
# 재시도 소진분이 여기 쌓인다. 자동 생성이 꺼져 있으니 미리 만들어둬야 한다.
#
# 파티션이 원본(6개)보다 적은 3개인 건 일부러 그렇게 뒀다. 여기 쌓일 양이 적기도 하고,
# 공통 에러 핸들러가 DLT 목적지 파티션을 -1(카프카가 알아서 고름)로 넘기기 때문에
# 원본과 개수가 달라도 상관없다. 기본값처럼 원본 파티션 번호를 그대로 쓰면
# 4~6번 파티션에서 실패한 레코드가 DLT 에 없는 파티션을 찾다가 발행부터 실패한다.
create rider.location.DLT      3 1
create order.created.DLT       3 1
create delivery.completed.DLT  3 1
# order-api 가 OR-07 로 이 둘을 소비한다. 소비하는 토픽에는 DLT 가 있어야 한다.
create dispatch.assigned.DLT   3 1
create dispatch.failed.DLT     3 1
# OR-07 이 DISPATCHING 을 알려고 order.status 도 소비한다. 없으면 실패한 레코드를 DLT 로 보내다가 그것마저 실패한다
# (브로커가 토픽 자동 생성을 꺼놨다).
create order.status.DLT        3 1

# ── 2단계 실험: 배차 제안을 카프카로 (exp/offer-kafka 브랜치에만 있다) ──────────────
# 제안은 10분만 살아 있으면 된다(보드 TTL 과 같다).
create dispatch.offer            6 1 retention.ms=600000
create dispatch.offer.expired    6 1 retention.ms=600000
create dispatch.offer.expired.DLT 3 1
# 래빗엠큐 쪽 max-concurrency 16 에 맞췄다. 카프카는 파티션 하나를 스레드 하나만 읽는다.
create notify.push               16 1 retention.ms=3600000
create notify.push.offer         16 1 retention.ms=600000
create dispatch.offer.DLT        3 1
create notify.push.DLT           3 1
create notify.push.offer.DLT     3 1

echo "[create-topics] 완료. 현재 토픽:"
docker exec "$CONTAINER" $KT --bootstrap-server "$BOOTSTRAP" --list 2>/dev/null \
  | grep -Ev '^__' | sed 's/^/  /'
