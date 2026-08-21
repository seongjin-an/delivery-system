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
create rider.location.DLT      3 1
create order.created.DLT       3 1
create delivery.completed.DLT  3 1

echo "[create-topics] 완료. 현재 토픽:"
docker exec "$CONTAINER" $KT --bootstrap-server "$BOOTSTRAP" --list 2>/dev/null \
  | grep -Ev '^__' | sed 's/^/  /'
