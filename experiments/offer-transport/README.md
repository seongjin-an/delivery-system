# 2단계 실험 — 배차 제안을 카프카로 바꿔본다

이 브랜치(`exp/offer-kafka`)는 main 에 머지하지 않는다. 해석은 main 의 `.reference/tech-choice.md` 3절에 있고,
여기는 다시 돌려볼 수 있게 코드와 원본 숫자만 둔다.

## 무엇을 바꿨나

`OFFER_TRANSPORT=kafka` 로 띄우면 제안이 래빗엠큐 대신 카프카로 흐른다. 기본은 `rabbit` 이라 안 주면 main 과 같다.

- `libs/common` — 제안을 내보내는 자리를 `OfferChannel` 로 뽑았다. `RabbitOfferChannel` 은 원래 코드 그대로고,
  `KafkaOfferChannel` 은 `dispatch.offer` 에 키 orderId 로 넣고 브로커 ack 를 최대 5초 기다린다.
  거절(DE-05)은 `expireNow` 로 `dispatch.offer.expired` 에 바로 넣는다.
- `offer-relay`
  - `KafkaOfferTimer` — 래빗엠큐 타이머 큐(TTL + DLX)를 손으로 만든 것. `dispatch.offer` 를 `offer-timer` 그룹으로 읽고,
    레코드마다 `offeredAt + 10초` 까지 자다가 `dispatch.offer.expired` 로 넘긴 뒤 ack 한다. TTL 이 전부 10초라
    파티션 안에서는 먼저 온 게 먼저 만료된다. 그래서 파티션마다 맨 앞 하나만 기다리면 된다.
  - `KafkaExpiredOfferListener` — `dispatch.offer.expired` 를 받아 기존 `OfferRelayService.relay` 로 넘긴다.
  - `offer_relay_delay` — offeredAt 부터 relay 에 닿기까지. 두 방식 모두 같은 자리에서 잰다.
- `notification-worker`
  - `KafkaOfferNotifyListener` — `dispatch.offer` 를 `notification-worker` 그룹으로 읽어 푸시 토픽에 넣는다.
  - `KafkaPushListener` — `PUSH_KAFKA_OFFER_TOPIC=notify.push`(기본)면 제안이 마케팅과 한 토픽에 섞이고,
    `notify.push.offer` 면 제안만 받는 토픽과 컨슈머가 따로 뜬다.
- `infra/create-topics.sh` — 실험 토픽 여덟 개(`dispatch.offer`, `notify.push` 등과 각 DLT).

## 돌리는 법

```bash
./scripts/start.sh                                   # 래빗엠큐(기본)
OFFER_TRANSPORT=kafka SKIP_INFRA=true ./scripts/start.sh
cd experiments/offer-transport
./timer.sh warmup && rm results/warmup-*             # JVM 을 데운다. 버린다
./timer.sh <라벨>                                    # 타이머 정확도
KILL_RELAY=true ./timer.sh <라벨>                    # 25초째 offer-relay kill -9, 20초 뒤 재기동
./priority.sh <라벨>                                 # 마케팅 1만 건 뒤에 주문 하나
```

`timer.sh` 는 라이더 1000명, 주문 초당 10건 60초, 전원 무응답이라 모든 제안이 타이머로만 끝난다.
래빗엠큐 만료 큐 컨슈머를 카프카 파티션 수와 맞추려면 `SPRING_RABBITMQ_LISTENER_SIMPLE_CONCURRENCY=6` 을 준다(main 기본은 1).

`priority.sh` 는 라이더 100명(전원 즉시 수락)을 가게 근처에 띄우고, 마케팅 1만 건을 넣은 직후 주문 하나를 넣는다.
앞 판 라이더가 레디스에 IDLE 로 남아 있으면 제안이 없는 라이더에게 가서 결과가 틀어진다. 그래서 시작 전에
`riders:online` 이 10명 이하가 될 때까지 기다린다. 판마다 밀린 마케팅은 비우고 시작했다(래빗 `purge_queue`,
카프카 `--reset-offsets --to-latest`).

## 원본 숫자 (`results/`)

| 판 | 파일 |
|---|---|
| 타이머, 래빗 컨슈머 1 / 6, 카프카 | `rabbit-c1-*`, `rabbit-c6-*`, `kafka-*` (`*-delay.txt` 가 분포) |
| 타이머 + kill -9 | `rabbit-kill-*`, `kafka-kill-*` |
| 우선순위 | `rabbit-p20-{1,2,3}-result.txt`, `rabbit-p250-result.txt`, `kafka-shared-{1,2,3}-result.txt`, `kafka-separate-{1,2,3}-result.txt` |

환경은 1실험과 같다(맥북 8코어 16GB, 래빗엠큐 4.3.6, cp-kafka 7.6.1 단일 브로커). 이번엔 OTel 에이전트를 붙인 채로
`scripts/start.sh` 로 전 서비스를 띄웠다.
