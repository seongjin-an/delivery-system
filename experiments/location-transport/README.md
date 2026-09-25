# 2단계 실험 — 위치 스트림을 래빗엠큐로 바꿔본다

이 브랜치(`exp/location-rabbitmq`)는 main 에 머지하지 않는다. 결론과 해석은 main 의
`.reference/tech-choice.md` 2절에 있고, 여기는 다시 돌려볼 수 있게 코드와 원본 숫자만 둔다.

## 무엇을 바꿨나

- `location-ingest` — `INGEST_TRANSPORT=rabbit` 이면 `rider.location` 큐(클래식, durable)에
  non-persistent 로 넣는다. publisher confirm 은 안 켰다(카프카 쪽 `acks=1` 비동기와 맞춤).
- `geo-indexer` — 같은 스위치로 카프카 리스너 대신 `@RabbitListener` 가 받는다.
  컨슈머 3개, prefetch 500, 배치 500건, 배치 모으는 시간 최대 50ms.
- 두 방식 모두 같은 입구(`RiderLocationListener.handle`)를 지나면서 지표 두 개를 올린다.
  - `geo_index_order_regression_total` — 같은 라이더의 더 새 좌표를 처리한 뒤에 옛 좌표가 온 횟수
  - `geo_index_staleness` — sentAt 부터 geo-indexer 에 닿기까지 걸린 시간
- 큐 인자는 `LOCATION_QUEUE_TTL_MS`, `LOCATION_QUEUE_MAX_LENGTH` 로 준다. 바꿀 땐 큐를 지우고 둘 다 다시 띄운다.

## 돌리는 법

```bash
docker compose -f infra/compose.yaml up -d redis kafka rabbitmq && bash infra/create-topics.sh
./gradlew :location-ingest:bootJar :geo-indexer:bootJar
cd experiments/location-transport
./run.sh start rabbit                         # 또는 kafka
k6 run -e RATE=3000 -e DURATION=60s load.js   # 정상 부하
./backlog.sh rabbit                           # 1분 정상 → 5분 geo-indexer 끔 → 2분 다시 켬
LOCATION_QUEUE_TTL_MS=5000 A=30s B=120s C=60s ./backlog.sh rabbit rabbit-ttl5s
./run.sh stop
```

알람 판은 부하를 건 채로 손으로 알람을 올렸다 내렸다.

```bash
docker exec delivery-rabbitmq rabbitmqctl set_vm_memory_high_watermark absolute 100MiB   # 켬
docker exec delivery-rabbitmq rabbitmqctl set_vm_memory_high_watermark 0.6               # 끔
```

## 환경

맥북(8코어, 16GB), 도커 데스크톱 VM 6코어 8GB. 래빗엠큐 4.3.6, cp-kafka 7.6.1(KRaft 단일 브로커).
`rider.location` 은 6파티션, 보존 30분, lz4. 부하는 k6 가 같은 노트북에서 초당 3000건, 라이더 3000명.
OTel 에이전트는 안 붙였다.

## 원본 숫자 (`results/`)

| 판 | 파일 |
|---|---|
| 래빗, 컨슈머 5분 정지 | `rabbit-samples.csv`, `rabbit-k6-{A,B,C}.txt` |
| 카프카, 컨슈머 5분 정지 | `kafka-samples.csv`, `kafka-k6-{A,B,C}.txt` |
| 래빗, TTL 5초 + 2분 정지 | `rabbit-ttl5s-*` |
| 래빗, 메모리 알람 강제 | `rabbit-alarm-samples.csv`, `rabbit-alarm-k6.txt`(초 단위 VU 수), `rabbit-alarm2-k6.txt`, `rabbit-alarm-jstack-excerpt.txt` |

`*-samples.csv` 는 `sample.sh` 가 5초(실제로는 명령 도는 시간까지 9초 안팎)마다 찍은 것이다.
