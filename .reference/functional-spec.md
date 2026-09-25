# 기능 정의서

무엇을 만들지 기능 단위로 정의한 문서다. 코드를 짜기 전에 여기서 입력과 출력, 규칙, 예외,
완료 조건을 확정하고 들어간다.

다른 문서와 역할이 이렇게 갈린다.

| 문서 | 답하는 질문 |
|---|---|
| 이 문서 | **무엇을** 만드나 (API, 규칙, 상태, 완료 조건) |
| [`flow-scenarios.md`](flow-scenarios.md) | 만든 것들이 **어떻게 이어져** 흐르나 |
| [`dispatch-internals.md`](dispatch-internals.md) | 배차 엔진이 레디스와 **명령 단위로** 뭘 하나 |
| [`scaling-scenarios.md`](scaling-scenarios.md) | 부하가 오면 **어떻게 늘리나** |
| [`../TODO.md`](../TODO.md) | **어떤 순서로** 만드나 |

저장소 선택은 레디스로 확정했다. MySQL 이나 몽고로 옮기는 안도 검토했는데, 정합성은 그쪽이
나은 대신 지연이 늘고 인프라가 복잡해진다. 이 프로젝트는 카프카와 레디스, 래빗엠큐를 익히는 게
목적이라 레디스로 만들고 겪어본 다음, 2단계에서 MySQL 로 바꿔 비교하는 순서로 간다.

---

# 1. 용어

| 용어 | 뜻 |
|---|---|
| 주문 (order) | 고객이 가게에 낸 배달 요청 |
| 라이더 (rider) | 배달원. 위치를 3초마다 보낸다 |
| 제안 (offer) | 특정 라이더 한 명에게 보내는 배차 요청. 유효시간 10초 |
| 배차 (dispatch) | 주문에 라이더를 붙이는 일 전체 |
| 후보 (candidate) | 배차 제안을 보낼 순서로 줄 세운 라이더 목록 |
| 시도 (attempt) | 몇 번째 후보에게 제안했는지 |
| 존 (zone) | 배차 단위 지역. 지금은 좌표를 격자로 잘라 만든 문자열 |
| 리스 (lease) | 인스턴스가 주문을 붙잡고 있다는 표시. 레디스 락으로 구현 |

---

# 2. 상태 정의

## 2.1 주문 상태 (`orders.status`)

| 상태 | 뜻 |
|---|---|
| `CREATED` | 주문이 만들어졌다. 아직 배차가 시작되지 않았다 |
| `DISPATCHING` | 배차가 진행 중이다. 제안이 나가 있다 |
| `ASSIGNED` | 라이더가 확정됐다 |
| `PICKED_UP` | 라이더가 가게에서 음식을 받았다 |
| `DELIVERED` | 배달이 끝났다 |
| `FAILED` | 후보를 다 썼는데 아무도 받지 않았다 |
| `CANCELLED` | 고객이 취소했다 |

```
CREATED ──▶ DISPATCHING ──▶ ASSIGNED ──▶ PICKED_UP ──▶ DELIVERED
   │             │
   │             └──▶ FAILED
   │
   └────────────────────────────────▶ CANCELLED   (DELIVERED 이전 어디서든)
```

## 2.2 제안 상태 (`dispatch:offer:{orderId}` 의 `state`)

| 상태 | 뜻 |
|---|---|
| `OFFERED` | 제안을 보냈고 응답을 기다린다 |
| `ACCEPTED` | 라이더가 수락했다 |
| `EXPIRED` | 10초가 지났다. 다음 후보로 넘어간다 |
| `REJECTED` | 라이더가 명시적으로 거절했다 |
| `FAILED` | 후보를 다 썼다 |
| `CANCELLED` | 주문이 취소됐다 |

## 2.3 라이더 상태 (`rider:state:{riderId}` 의 `status`)

| 상태 | 뜻 | 후보로 뽑히나 |
|---|---|---|
| `OFFLINE` | 좌표가 30초 넘게 안 왔다 | 아니오 |
| `IDLE` | 온라인이고 한가하다 | 예 |
| `OFFERED` | 제안을 받고 응답을 안 했다 | 아니오 |
| `DELIVERING` | 배달 중이다 | 아니오 |

```
OFFLINE ◀──▶ IDLE ──▶ OFFERED ──▶ DELIVERING ──▶ IDLE
                 ▲         │
                 └─────────┘   (거절하거나 10초가 지났을 때)
```

`DELIVERING` 인 라이더는 좌표가 끊겨도 `OFFLINE` 으로 바꾸지 않는다. 지하 주차장에 들어간
배달 중 라이더를 오프라인 처리하면 진행 중인 주문이 붕 뜬다.

---

# 3. 공통 규칙

## 3.1 식별자

`orderId`, `riderId`, `offerId` 는 모두 **TSID** 다. `libs/common` 의 `Ids.newId()` 로 만든다.
64비트 정수라 DB 에는 `BIGINT`, JSON 에는 숫자, 레디스 키와 카프카 키에는 십진수 문자열로 나간다.
DB 에 보이는 값과 로그에 찍히는 값과 레디스 키에 박힌 값이 전부 같은 숫자라야 장애 났을 때 눈으로 따라갈 수 있다.

앞쪽 42비트가 밀리초 타임스탬프라 숫자 크기가 곧 만들어진 순서다.
DB 의 `AUTO_INCREMENT` 를 안 쓰는 이유는 이 아이디가 DB 에 들어가기 전에 이미 필요해서다 —
카프카 파티션 키로, 레디스 키로, 래빗엠큐 메시지 안으로 그대로 흘러다닌다.

인스턴스를 여러 대 띄울 때는 각자 다른 노드 번호를 줘야 한다.
`-Dtsidcreator.node=N` 으로 주고, `scripts/_common.sh` 가 포트에서 뽑아 넣는다.

문서 안의 예시에서는 읽기 좋게 `order-77`, `R1` 처럼 줄여 쓴다.

## 3.2 시간

모든 시각은 UTC 밀리초다. 자바에서는 `Instant`, JSON 에서는 ISO-8601 문자열
(`2026-08-23T04:12:33.482Z`) 로 주고받는다. 레디스 해시에는 epoch 밀리초 정수로 넣는다.

## 3.3 좌표

WGS84 위경도, 소수점 여섯 자리까지. 한국 밖 좌표는 받지 않는다.

| | 최소 | 최대 |
|---|---|---|
| 위도 | 33.0 | 39.0 |
| 경도 | 124.0 | 132.0 |

범위를 벗어나면 `400 INVALID_COORDINATE` 로 거절한다.

## 3.4 존 계산

`zoneId` 는 클라이언트가 보내지 않고 서버가 좌표로 계산한다.
위경도를 0.01도(약 1.1km) 격자로 내려서 `Z{위도*100}_{경도*100}` 형태로 만든다.
예를 들어 `(37.5665, 126.9780)` 은 `Z3756_12697` 이 된다.

지금은 배차 로직에 안 쓰고 지표 라벨과 로그에만 쓴다. 확장 시나리오 D 에서
락 샤딩과 파티션 키를 존 단위로 바꿀 때 쓰기 시작한다.

## 3.5 응답 형식

`libs/common` 의 `ApiResponse` 를 쓴다.

```json
{ "success": true,  "data": { "orderId": "..." }, "message": null }
{ "success": false, "data": null, "message": "이미 다른 라이더가 수락했습니다" }
```

## 3.6 에러 코드

| HTTP | 코드 | 언제 |
|---|---|---|
| 400 | `INVALID_COORDINATE` | 좌표가 한국 범위 밖이다 |
| 400 | `MISSING_IDEMPOTENCY_KEY` | 쓰기 요청에 멱등키가 없다 |
| 400 | `INVALID_REQUEST` | 필수 필드가 없거나 형식이 틀렸다 |
| 403 | `NOT_YOUR_OFFER` | 다른 라이더에게 간 제안이다 |
| 403 | `NOT_YOUR_ORDER` | 배차받지 않은 주문을 조작하려 한다 |
| 404 | `ORDER_NOT_FOUND` | 주문이 없다 |
| 409 | `ALREADY_TAKEN` | 이미 다른 라이더가 수락했다 |
| 409 | `INVALID_STATE` | 지금 상태에서 할 수 없는 요청이다 |
| 410 | `OFFER_EXPIRED` | 제안이 만료됐다 |
| 429 | `RATE_LIMITED` | 외부 발송 한도를 넘었다 |

## 3.7 멱등성

쓰기 API 는 모두 재시도해도 안전해야 한다. 방법은 두 가지로 나눈다.

- 주문 생성처럼 새 자원을 만드는 요청은 `Idempotency-Key` 헤더를 필수로 받고 레디스로 막는다.
- 상태를 바꾸는 요청은 조건부 갱신으로 막는다. 이미 그 상태면 성공으로 응답한다.

## 3.8 컨슈머 공통 규칙

| 항목 | 값 |
|---|---|
| ack | 수동 (`ack-mode: manual`) |
| `BusinessException` 발생 | 재시도하지 않고 바로 DLT 로 보낸다. 다시 해도 결과가 같으니까 |
| 그 외 예외 | 500ms 부터 지수 백오프로 3회 재시도, 그래도 실패하면 DLT |
| DLT 토픽 | 원본 토픽 이름 + `.DLT` |
| 관측 | 리스너마다 `observation-enabled: true` 로 트레이스를 이어붙인다 |

## 3.9 펜싱 규칙 (중요)

제안 메시지에는 `offerId` 가 들어 있다. offer-relay 는 메시지를 처리하기 전에
**메시지의 `offerId` 가 레디스에 적힌 현재 `offerId` 와 같은지 먼저 확인한다.**
다르면 오래된 메시지라서 조용히 버린다.

이 규칙이 없으면 이런 일이 난다. 라이더가 3초에 명시적으로 거절해서 곧바로 2순위에게
재제안했는데, 10초가 되면 1순위용 타이머 메시지가 만료돼서 도착한다. 그걸 그대로 처리하면
아직 유효한 2순위 제안을 끊고 3순위로 넘어가버린다.

---

# 4. order-api (:8090)

주문을 받고 배차 결과를 반영하고 상태를 알려준다. 이 프로젝트에서 유일하게 고객을 마주하는 서비스다.

## OR-01 주문 생성

| | |
|---|---|
| 엔드포인트 | `POST /api/orders` |
| 헤더 | `Idempotency-Key` (필수) |
| 출력 | `201`, `order.created` 토픽 발행 |
| 목표 지연 | p99 100ms |

요청

```json
{
  "storeId": "store-001",
  "storeLat": 37.498095,
  "storeLng": 127.027610,
  "destLat": 37.504198,
  "destLng": 127.048985,
  "priceKrw": 18000
}
```

응답

```json
{ "success": true, "data": { "orderId": 558668931353510983, "status": "CREATED", "zoneId": "Z3749_12702" } }
```

**규칙**

1. `orders` 행과 `outbox` 행을 **한 트랜잭션**에 넣는다. 커밋이 끝났으면 이벤트는 반드시 나간다.
2. 카프카 발행은 이 요청 안에서 하지 않는다. OR-06 폴러가 한다.
3. `zoneId` 는 가게 좌표로 계산한다 (3.4).
4. 멱등키는 `SET idem:order:{key} {orderId} NX EX 3600` 으로 잡는다.

**예외**

| 상황 | 처리 |
|---|---|
| 멱등키가 이미 있다 | `GET` 해서 기존 `orderId` 로 `200` 응답. 새 주문을 만들지 않는다 |
| 멱등키 헤더가 없다 | `400 MISSING_IDEMPOTENCY_KEY` |
| 좌표가 범위 밖이다 | `400 INVALID_COORDINATE` |
| 가게와 목적지가 20km 넘게 떨어졌다 | `400 INVALID_REQUEST` |

**완료 조건**

`curl` 로 주문을 넣으면 `201` 이 오고, Kafka UI 의 `order.created` 토픽에 메시지가 한 건 보인다.
같은 멱등키로 다시 부르면 같은 `orderId` 가 오고 토픽에는 메시지가 늘지 않는다.

## OR-02 주문 조회

| | |
|---|---|
| 엔드포인트 | `GET /api/orders/{orderId}` |

응답

```json
{
  "success": true,
  "data": {
    "orderId": 558668931353510983,
    "status": "ASSIGNED",
    "riderId": 558668931353510984,
    "attempt": 2,
    "timeline": [
      { "status": "CREATED",     "at": "2026-08-23T04:12:33.482Z" },
      { "status": "DISPATCHING", "at": "2026-08-23T04:12:33.712Z" },
      { "status": "ASSIGNED",    "at": "2026-08-23T04:12:41.109Z" }
    ]
  }
}
```

`attempt` 를 넣는 이유는 "몇 번째 라이더에서 잡혔는지" 를 고객 지원에서 바로 보게 하려는 것이다.
프론트가 없으니 이 응답이 사실상 화면 역할을 한다.

## OR-03 픽업 처리

| | |
|---|---|
| 엔드포인트 | `POST /api/orders/{orderId}/pickup` |
| 입력 | `{ "riderId": "..." }` |
| 출력 | `order.status` 발행 (`PICKED_UP`) |

**규칙**

`UPDATE orders SET status='PICKED_UP' WHERE order_id=? AND rider_id=? AND status='ASSIGNED'` 로
바꾸고 영향 행 수를 본다. 0이면 예외로 처리한다.

**예외**

| 상황 | 처리 |
|---|---|
| 상태가 `ASSIGNED` 가 아니다 | `409 INVALID_STATE` |
| `riderId` 가 배차된 라이더와 다르다 | `403 NOT_YOUR_ORDER` |
| 이미 `PICKED_UP` 이다 | `200` (멱등) |

## OR-04 배달 완료

| | |
|---|---|
| 엔드포인트 | `POST /api/orders/{orderId}/complete` |
| 입력 | `{ "riderId": "..." }` |
| 출력 | `order.status` (`DELIVERED`), `delivery.completed` 발행 |

`delivery.completed` 페이로드

```json
{
  "orderId": "...", "riderId": "...", "zoneId": "Z3749_12702",
  "priceKrw": 18000, "distanceMeters": 2340,
  "assignedAt": "...", "completedAt": "...", "elapsedSeconds": 1287
}
```

**규칙**

1. 상태를 `DELIVERED` 로 바꾸고 `delivery.completed` 를 아웃박스에 넣는다. 한 트랜잭션이다.
2. `HSET rider:state:{riderId} status IDLE currentOrderId ""` 로 라이더를 풀어준다.
3. `lock:rider:{riderId}` 를 Lua 로 해제한다. 값이 이 `orderId` 인지 확인하고 지운다.
4. `dispatch:offer:{orderId}` 와 `dispatch:candidates:{orderId}` 를 지운다.

## OR-05 주문 취소

| | |
|---|---|
| 엔드포인트 | `POST /api/orders/{orderId}/cancel` |

**규칙**

1. `DELIVERED` 면 취소할 수 없다. `409 INVALID_STATE`.
2. 진행 중인 제안이 있으면 `dispatch:offer` 의 `state` 를 `CANCELLED` 로 바꾼다.
   그러면 만료 메시지가 와도 offer-relay 가 재제안하지 않는다.
3. 라이더가 이미 배차됐으면 `lock:rider` 를 풀고 `rider:state` 를 `IDLE` 로 돌린다.

## OR-06 아웃박스 폴러

| | |
|---|---|
| 트리거 | 200ms 주기 스케줄러 |
| 입력 | `outbox` 에서 `published_at IS NULL` 인 행 100개 |
| 출력 | 각 행의 `destination_topic` 으로 발행 |

**규칙**

1. `partition_key` 를 카프카 키로 쓴다. 주문 관련 이벤트는 `orderId` 다.
2. 발행이 성공한 행만 `published_at` 을 채운다.
3. 실패하면 다음 주기에 다시 시도한다. 재시도 횟수를 세서 10회를 넘으면 경고 로그를 남긴다.
4. 인스턴스가 여러 대여도 안전해야 한다. `SELECT ... FOR UPDATE SKIP LOCKED` 로 행을 집어간다.

**완료 조건**

order-api 를 두 대 띄우고 주문을 100건 넣었을 때, `order.created` 토픽에 정확히 100건이
들어가고 중복이 없다.

## OR-07 배차 결과 반영

| | |
|---|---|
| 트리거 | `dispatch.assigned`, `dispatch.failed` 소비 |
| 출력 | `orders.status` 갱신 |

**규칙**

1. `dispatch.assigned` 를 받으면 `status='ASSIGNED'`, `rider_id` 를 채운다.
2. `dispatch.failed` 를 받으면 `status='FAILED'` 로 바꾼다.
3. 조건부 갱신으로 멱등하게 만든다. 이미 그 상태거나 더 진행된 상태면 아무것도 하지 않는다.
   같은 이벤트가 두 번 와도 `PICKED_UP` 이 `ASSIGNED` 로 되돌아가면 안 된다.

---

# 5. location-ingest (:8091)

라이더 위치를 받아서 카프카에 넣는다. 상태를 아무것도 갖지 않는 게 이 서비스의 설계 목표다.
확장 시나리오 A 의 기준선이 되기 때문이다.

## LI-01 위치 수신

| | |
|---|---|
| 엔드포인트 | `POST /api/riders/{riderId}/location` |
| 출력 | `202`, `rider.location` 발행 (키 = `riderId`) |
| 목표 지연 | p99 20ms |

요청

```json
{ "lat": 37.498095, "lng": 127.027610, "sentAt": "2026-08-23T04:12:33.482Z" }
```

응답은 본문 없이 `202` 다. 좌표를 받았다는 것만 알려주고 처리 결과는 기다리지 않는다.

**규칙**

1. 직전 좌표에서 `min-move-meters`(15m) 미만 움직였으면 발행하지 않고 그냥 `202` 를 준다.
   신호 대기 중인 라이더가 3초마다 같은 좌표를 보내는데, 그걸 다 발행하면 전체 트래픽의
   절반이 의미 없는 좌표가 된다.
2. 직전 좌표는 **인스턴스 메모리에 캐시**한다. 레디스를 조회하면 왕복이 생겨서 무상태라는
   이점이 사라진다. 인스턴스를 4대로 늘리면 한 대가 그 라이더의 좌표를 4번에 1번만 보게 돼서
   필터가 느슨해지는데, 12초 동안 15m 를 안 움직인 경우만 걸러지므로 그 정도는 감수한다.
3. 프로듀서는 처리량 쪽으로 맞춘다. `acks=1`, `linger.ms=20`, `batch.size=64KB`,
   `compression.type=lz4`. 좌표 한 점을 잃어도 3초 뒤에 다음 점이 온다.
4. 캐시는 최대 라이더 수만큼만 들고 있는다. 30분 넘게 안 온 라이더는 버린다.

**예외**

| 상황 | 처리 |
|---|---|
| 좌표가 범위 밖이다 | `400 INVALID_COORDINATE`. 발행하지 않는다 |
| 카프카 발행이 실패했다 | `202` 를 그대로 준다. 지표만 올린다. 라이더 앱을 재시도시킬 이유가 없다 |
| `sentAt` 이 현재보다 미래다 | 서버 시각으로 덮어쓰고 경고 지표를 올린다 |

**완료 조건**

시뮬레이터로 라이더 100명을 3초 주기로 돌렸을 때 `rider.location` 토픽에 초당 30건 안팎이
들어오고, 라이더를 한 자리에 세워두면 발행이 멈춘다.

---

# 6. geo-indexer (:8092)

위치 스트림을 소비해서 레디스 GEO 인덱스를 최신으로 유지한다.
확장 시나리오 B 의 주인공이라 파티션 수와 `concurrency` 를 만지기 쉽게 만들어둔다.

## GI-01 위치 인덱싱

| | |
|---|---|
| 트리거 | `rider.location` 소비 (`group-id: geo-indexer`) |
| 출력 | `riders:online` GEO 갱신, `rider:state:{id}` 갱신, `riders:heartbeat` 갱신 |

**규칙**

1. 한 폴에서 가져온 레코드를 라이더별로 **마지막 것만 남겨** 정리한 뒤 파이프라인으로 한 번에 쓴다.
   같은 라이더의 좌표가 한 배치에 세 개 들어와 있으면 앞의 두 개는 쓸 필요가 없다.
2. `GEOADD riders:online {lng} {lat} {riderId}`
3. `HSET rider:state:{riderId} lat .. lng .. lastSeenAt ..` 로 좌표와 시각을 갱신한다.
4. **`status` 는 조건부로만 건드린다.** 지금 `OFFLINE` 이면 `IDLE` 로 올리고,
   `IDLE` 이면 그대로 두고, `OFFERED` 나 `DELIVERING` 이면 **절대 건드리지 않는다.**
   배달 중인 라이더의 상태를 `IDLE` 로 덮으면 새 주문 후보로 잡힌다.
5. `ZADD riders:heartbeat {lastSeenAt} {riderId}` 로 오프라인 정리용 인덱스를 같이 유지한다.
   GEO 자료구조만으로는 "좌표가 오래된 사람" 을 찾을 수 없기 때문이다.
6. 처리한 뒤 수동으로 ack 한다.
7. `auto-offset-reset` 은 `latest` 다. 다만 이건 커밋된 오프셋이 없는 첫 기동에만 먹는다.
8. **레코드 타임스탬프가 10초 넘게 지난 좌표는 버린다** (`delivery.listener.rider-location.max-age`).
   7번만 믿으면 재시작했을 때 커밋한 자리부터 밀린 좌표를 전부 다시 쓴다. 2단계 실험에서 5분 끄고 켜니
   310초 묵은 좌표가 레디스에 들어갔다. 좌표 안의 `sentAt`(폰 시계)이 아니라 레코드 타임스탬프
   (location-ingest 서버 시계)로 판단한다. 폰 시계가 늦은 라이더가 통째로 버려지면 안 되기 때문이다.

> `riders:heartbeat` 는 `libs/common` 의 `RedisKeys` 에 추가해야 하는 새 키다.

**완료 조건**

RedisInsight 에서 `riders:online` 의 멤버 수가 온라인 라이더 수와 맞고,
`GEOSEARCH` 를 직접 쳐보면 가까운 라이더가 거리순으로 나온다.

## GI-02 오프라인 정리

| | |
|---|---|
| 트리거 | 10초 주기 스케줄러 |
| 출력 | `riders:online` 에서 제거, `status = OFFLINE` |

**규칙**

1. `ZRANGEBYSCORE riders:heartbeat -inf {now - 30초}` 로 대상을 뽑는다.
2. 각 라이더의 `status` 를 확인한다.
   - `IDLE` 이나 `OFFLINE` 이면 `ZREM riders:online`, `status = OFFLINE` 으로 바꾼다.
   - `OFFERED` 면 GEO 에서만 뺀다. 진행 중인 제안은 10초 뒤 만료되면서 자연히 정리된다.
   - `DELIVERING` 이면 **아무것도 하지 않는다.** `riders:heartbeat` 에서도 빼지 않는다.
3. 인스턴스를 여러 대 띄워도 안전해야 한다. `SET lock:sweep:offline NX PX 8000` 으로
   한 번에 한 대만 돌게 만든다.

**완료 조건**

시뮬레이터에서 라이더 한 명을 멈추면 30초 안에 `riders:online` 에서 사라지고,
배달 중인 라이더를 멈추면 사라지지 않는다.

## GI-03 라이더 상태 조회

| | |
|---|---|
| 엔드포인트 | `GET /api/riders/{riderId}/state` |

디버깅용이다. `rider:state` 해시와 GEO 안에 있는지 여부를 그대로 보여준다.
배차가 왜 안 되는지 확인할 때 제일 먼저 보는 곳이 된다.

---

# 7. dispatch-engine (:8093)

카프카에서 주문을 받아 레디스로 후보를 찾고 래빗엠큐로 제안을 보낸다.
세 기술이 한 흐름 안에서 다 만나는 서비스다.

명령 단위 순서와 Lua 스크립트는 [`dispatch-internals.md`](dispatch-internals.md) 에 있다.
여기서는 규칙과 완료 조건만 정의한다.

## DE-01 배차 시작

| | |
|---|---|
| 트리거 | `order.created` 소비 (`group-id: dispatch-engine`) |
| 출력 | 래빗엠큐 `offer.created` 발행, `order.status` (`DISPATCHING`) |
| 목표 지연 | 주문 접수부터 첫 제안까지 p99 1초 |

**규칙**

1. `SET lock:dispatch:{orderId} {인스턴스ID}:{시각} NX PX 15000` 으로 리스를 잡는다.
   실패하면 다른 인스턴스가 처리 중이라는 뜻이니 ack 만 하고 넘어간다.
2. `dispatch:offer:{orderId}` 를 읽어서 이미 처리한 주문인지 판정한다. 규칙은 이렇다.

| 읽은 값 | 판단 | 처리 |
|---|---|---|
| 키가 없다 | 처음이다 | 진행한다 |
| `ACCEPTED` | 이미 배차됐다 | 넘긴다 |
| `CANCELLED` | 취소된 주문이다 | 넘긴다 |
| `EXPIRED` / `FAILED` / `REJECTED` | 끝났지만 다시 해도 된다 | 진행한다 |
| `OFFERED`, `offeredAt` 이 30초 안쪽 | 진행 중이다 | 넘긴다. offer-relay 가 이어받는다 |
| `OFFERED`, `offeredAt` 이 30초 초과 | 좀비다. 타이머가 없다 | 진행한다 |

   마지막 줄이 중요하다. 제안 유효시간이 10초인데 30초가 지나도 `OFFERED` 라면,
   타이머 메시지가 애초에 없었다는 뜻이다. 정상이라면 10초에 만료돼서 다른 상태로 바뀌어 있다.
   임계값 30초는 offer-relay 의 큐 랙을 보고 조정한다.
3. 후보를 찾는다 (DE-02).
4. 후보가 하나도 없으면 `dispatch.failed` 를 발행하고 끝낸다.
5. 후보 목록을 저장한다. `DEL` 먼저 하고 `RPUSH` 한다. 재배차 때 앞의 목록이 남아 있으면
   같은 라이더가 두 번 들어간다. `EXPIRE 600` 을 꼭 붙인다.
6. 1순위에게 제안을 보낸다 (DE-03).
7. Lua 로 리스를 해제한다. 반환값이 0이면 리스를 잃은 채로 일했다는 뜻이니
   경고 로그를 남기고 이후 작업을 중단한다.

**완료 조건**

주문을 하나 넣으면 래빗엠큐 관리 UI 의 `dispatch.offer.timer` 큐에 메시지가 한 건 쌓이고,
`dispatch.offer.notify` 큐에도 한 건이 들어와서 워커가 바로 꺼내간다.

## DE-02 후보 검색

| | |
|---|---|
| 입력 | 가게 좌표 |
| 출력 | 점수순 라이더 목록 최대 10명 |

**규칙**

1. `GEOSEARCH riders:online FROMLONLAT {lng} {lat} BYRADIUS 3000 m ASC COUNT 30 WITHDIST`
   `COUNT` 와 `ASC` 를 반드시 함께 준다. 안 주면 반경 안 500명을 다 계산해서 실어 보낸다.
2. 뽑힌 라이더들의 `rider:state` 를 **파이프라인 한 번**으로 읽는다. for 루프로 30번 왕복하면
   실제 환경에서 배차 하나에 30ms 를 상태 조회에만 쓴다.
3. `status == IDLE` 인 라이더만 남긴다.
4. 점수를 계산한다. **낮은 쪽이 우선**이다.

```
score = 거리km - 대기보너스
대기보너스 = min(대기시간_분, 10) × 0.1
```

   반경이 3km 니까 거리 항은 0에서 3 사이고, 대기 보너스는 최대 1.0 이다.
   10분 넘게 기다린 라이더는 1km 정도 더 멀어도 가까운 라이더를 이긴다.
   오래 기다린 사람을 챙기지 않으면 특정 라이더만 계속 콜을 먹는다.
5. 상위 10명을 반환한다.

**설정으로 뺄 값**

| 키 | 기본값 |
|---|---|
| `delivery.dispatch.search-radius-meters` | 3000 |
| `delivery.dispatch.geo-count` | 30 |
| `delivery.dispatch.max-candidates` | 10 |
| `delivery.dispatch.wait-bonus-cap-minutes` | 10 |

## DE-03 제안 발송

| | |
|---|---|
| 입력 | `orderId`, 후보 목록 |
| 출력 | 래빗엠큐 `offer.created` |

**규칙**

1. `LPOP dispatch:candidates:{orderId}` 로 다음 후보를 꺼낸다.
2. `SET lock:rider:{riderId} {orderId} NX PX 12000` 으로 라이더를 찜한다.
   실패하면 그 라이더를 건너뛰고 다음 후보로 간다. 후보가 떨어지면 `dispatch.failed` 다.
3. 새 `offerId` 를 발급한다. 재제안할 때마다 새로 만든다. 펜싱에 쓰기 때문이다 (3.9).
4. `HSET dispatch:offer:{orderId} offerId .. riderId .. state OFFERED attempt .. offeredAt ..`
   그리고 `EXPIRE 600`.
5. `HSET rider:state:{riderId} status OFFERED`
6. `dispatch.x` 익스체인지에 라우팅 키 `offer.created` 로 **한 번만** 발행한다.
   알림 큐와 타이머 큐 양쪽에 브로커가 복제해준다. 코드에서 두 번 발행하면 한쪽만 성공하는
   경우가 생기고, 그게 둘 다 사고다.
7. publisher confirm 을 확인한다. 실패했으면 `dispatch:offer` 를 지우고 `lock:rider` 를 풀고
   카프카 메시지를 ack 하지 않는다. 재소비돼서 처음부터 다시 한다.

## DE-04 제안 수락

| | |
|---|---|
| 엔드포인트 | `POST /api/offers/{offerId}/accept` |
| 입력 | `{ "riderId": "..." }` |
| 출력 | `dispatch.assigned`, `order.status` (`ASSIGNED`) |

**규칙**

Lua 스크립트 한 번으로 판정한다. 읽고 비교하고 쓰는 걸 따로 하면 만료 처리와 겹칠 때
나중 쓰기가 이겨서 응답과 상태가 어긋난다.

| Lua 반환 | HTTP | 라이더에게 보일 말 |
|---|---|---|
| `1` | 200 | 배차 완료 |
| `-1` | 409 `ALREADY_TAKEN` | 이미 다른 분이 받았어요 |
| `-2` | 410 `OFFER_EXPIRED` | 제안이 만료됐어요 |
| `0` | 403 `NOT_YOUR_OFFER` | 이 제안은 당신 것이 아니에요 |

성공하면 이어서 한다.

1. `HSET rider:state:{riderId} status DELIVERING currentOrderId {orderId}`
2. `lock:rider` 는 **풀지 않는다.** 배달 완료(OR-04)까지 유지한다.
   락 TTL 12초가 지나면 그때부터는 `status = DELIVERING` 이 라이더를 지킨다.
3. `dispatch.assigned` 와 `order.status` 를 발행한다.

**완료 조건**

시뮬레이터 라이더가 수락하면 `GET /api/orders/{id}` 가 `ASSIGNED` 와 `riderId` 를 보여준다.
같은 제안을 두 번 수락하면 두 번째는 `409` 가 온다.

## DE-05 제안 거절

| | |
|---|---|
| 엔드포인트 | `POST /api/offers/{offerId}/reject` |
| 출력 | `dispatch.dlx` 에 `offer.expired` 직접 발행 |

**규칙**

1. Lua CAS 로 `state` 를 `OFFERED` 에서 `REJECTED` 로 바꾼다. 반환값 처리는 DE-04 와 같다.
2. `lock:rider` 를 Lua 로 해제한다. 값이 이 `orderId` 인지 확인하고 지운다.
3. `HSET rider:state:{riderId} status IDLE`
4. 10초를 기다릴 이유가 없으니 `dispatch.dlx` 에 `offer.expired` 를 **직접 발행**해서
   offer-relay 가 곧바로 다음 후보로 넘기게 한다.
5. 나중에 원래 타이머 메시지가 만료돼서 도착하면, 그때는 `offerId` 가 이미 바뀌어 있어서
   펜싱 규칙(3.9)에 걸려 버려진다.

거절 횟수는 `HINCRBY rider:state:{riderId} rejectCount 1` 로 센다.
지금은 점수에 반영하지 않고 지표로만 본다.

## DE-06 배차 상태 조회

| | |
|---|---|
| 엔드포인트 | `GET /api/dispatch/{orderId}` |

`dispatch:offer` 해시와 `dispatch:candidates` 리스트, `lock:dispatch` 값을 그대로 덤프한다.
디버깅 전용이고 고객에게 노출하지 않는다.

---

# 8. offer-relay (:8094)

만료된 제안을 받아 다음 후보에게 넘긴다. 래빗엠큐를 쓰는 이유가 이 서비스에 다 담겨 있다.

## RE-01 토폴로지 선언

| | |
|---|---|
| 트리거 | 애플리케이션 기동 |

```
dispatch.x (topic)
  ├─(offer.created)─▶ dispatch.offer.notify   컨슈머 있음
  └─(offer.created)─▶ dispatch.offer.timer    x-message-ttl=10000
                                              x-dead-letter-exchange=dispatch.dlx
                                              x-dead-letter-routing-key=offer.expired
                                              컨슈머 없음
dispatch.dlx (topic)
  └─(offer.expired)─▶ dispatch.offer.expired  offer-relay 가 소비

notify.x (topic)
  └─(push)──────────▶ notify.push             x-max-priority=10
                                              x-dead-letter-exchange=notify.dlx
notify.dlx (topic)
  └─(push)──────────▶ notify.push.dlq
```

**규칙**

1. 큐와 익스체인지는 모두 durable 로 선언한다.
2. **`dispatch.offer.timer` 에 리스너를 붙이지 않는다.** 붙이면 메시지를 즉시 꺼내가서
   TTL 이 흐를 시간이 없다. 그러면 재제안이 한 번도 돌지 않는다.
3. dispatch-engine 이 이 익스체인지에 발행하므로 offer-relay 를 먼저 띄운다.
   `scripts/_common.sh` 의 기동 순서가 이미 그렇게 잡혀 있다.

## RE-02 만료 제안 처리

| | |
|---|---|
| 트리거 | `dispatch.offer.expired` 소비 |
| 출력 | `offer.created` 재발행 또는 `dispatch.failed` |

**규칙** 순서대로 확인한다.

1. 메시지의 `offerId` 가 `dispatch:offer` 의 현재 `offerId` 와 다르면 오래된 메시지다.
   ack 만 하고 버린다 (3.9).
2. `state` 가 `ACCEPTED` 면 이미 배차됐다. ack 만 하고 버린다.
3. `state` 가 `CANCELLED` 면 주문이 취소됐다. 키들을 정리하고 끝낸다.
4. `attempt` 가 `max-attempts`(5) 이상이면 `state = FAILED` 로 바꾸고
   `dispatch.failed` 를 발행한다.
5. 직전 라이더의 `lock:rider` 를 Lua 로 해제하고 `status` 를 `IDLE` 로 되돌린다.
   이걸 빼먹으면 그 라이더가 12초 동안 다른 주문의 후보가 되지 못한다.
6. `LPOP dispatch:candidates:{orderId}` 로 다음 후보를 꺼낸다. 비어 있으면 4번과 같이 처리한다.
7. 새 `offerId` 를 발급하고 `attempt` 를 1 올리고 `state = OFFERED` 로 갱신한다.
8. `offer.created` 를 재발행한다.

**설정으로 뺄 값**

| 키 | 기본값 |
|---|---|
| `delivery.relay.max-attempts` | 5 |
| `delivery.relay.stale-offer-seconds` | 30 |
| `spring.rabbitmq.listener.simple.prefetch` | 20 |

**완료 조건**

시뮬레이터의 `acceptRate` 를 0으로 두면 `attempt` 가 1에서 5까지 10초 간격으로 올라가고,
약 50초 뒤에 `dispatch.failed` 가 나가고 주문이 `FAILED` 가 된다.
`GET /api/orders/{id}` 의 `attempt` 가 5로 보인다.

## RE-03 좀비 스위퍼

| | |
|---|---|
| 트리거 | 30초 주기 스케줄러 |
| 우선순위 | 2단계로 미룬다 |

`dispatch:offer` 중에서 `state` 가 `OFFERED` 인데 `offeredAt` 이 30초를 넘은 것을 찾아
강제로 만료 처리한다. 타이머 메시지가 유실된 주문을 구제하는 안전망이다.

레디스에는 상태로 검색할 인덱스가 없으니 `ZADD offers:pending {offeredAt} {orderId}` 를
같이 유지해야 한다. MySQL 이라면 인덱스 하나로 끝나는 일이라, 2단계 저장소 비교 실험에서
이 차이를 재는 게 좋다.

---

# 9. notification-worker (:8095)

제안 알림을 실제로 발송한다. 외부 API 가 병목이라서 확장 시나리오 C 의 주인공이다.

## NW-01 제안 알림 접수

| | |
|---|---|
| 트리거 | `dispatch.offer.notify` 소비 |
| 출력 | `notify.push` 큐에 priority 9 로 투입 |

**규칙**

1. 발송 대상을 만든다. `riderId`, 가게 이름, 예상 수익, 남은 시간 10초.
2. `notify.x` 에 라우팅 키 `push` 로, `priority = 9` 로 넣는다.
3. 왜 두 단계로 나누나. 발송 지점을 한 곳에 모아서 레이트리밋을 걸기 위해서다.
   그리고 우선순위 큐가 실제로 작동하는지 보려면 우선순위가 다른 메시지가 같은 큐에
   들어와야 한다.

## NW-02 푸시 발송

| | |
|---|---|
| 트리거 | `notify.push` 소비 |
| 출력 | 웹훅 POST (가짜 푸시) |

**규칙**

1. 레디스 토큰 버킷으로 전역 초당 한도를 확인한다.
   **인스턴스마다 한도를 나눠 갖지 않는다.** 인스턴스가 죽거나 늘어날 때마다 한도가 어긋난다.
2. 토큰이 없으면 100ms 백오프로 3회까지 기다린다. 그래도 없으면
   `requeue=false` 로 nack 해서 DLQ 로 보내고 `push_ratelimited_total` 을 올린다.
3. 실제 발송은 `delivery.push.webhook-url` 로 POST 한다. 기본값은 rider-simulator 주소다.
   이렇게 하면 프론트 없이도 "제안 발송에서 라이더 수락까지" 루프가 닫힌다.
4. `fake-latency-ms` 만큼 지연을 넣는다. 이 값을 올리면 시나리오 C 가 재현된다.
5. 5% 확률로 실패시킨다. 재시도와 DLQ 경로를 실제로 타보기 위한 것이다.

**설정으로 뺄 값**

| 키 | 기본값 | 무엇을 실험하나 |
|---|---|---|
| `delivery.push.global-rate-per-sec` | 200 | 전역 레이트리밋 |
| `delivery.push.fake-latency-ms` | 120 | 외부 API 지연 |
| `delivery.push.fail-rate` | 0.05 | 재시도와 DLQ |
| `spring.rabbitmq.listener.simple.prefetch` | 250 | prefetch 가 큰 게 왜 나쁜지 |

**완료 조건**

`fake-latency-ms` 를 400 으로 올리면 `notify.push` 큐가 쌓이는데 워커 CPU 는 한가하다.
워커를 8대로 늘리면 큐는 줄지만 `push_ratelimited_total` 이 오른다.
`prefetch` 를 20으로 내리면 워커 사이에 물량이 고르게 퍼진다.

## NW-03 마케팅 푸시 투입

| | |
|---|---|
| 엔드포인트 | `POST /api/notify/marketing` |
| 입력 | `{ "count": 10000, "message": "..." }` |

`priority = 1` 로 대량 투입한다. 우선순위 큐 검증용이다.
마케팅 푸시 1만 건을 넣은 직후에 배차 제안을 보내면, 제안이 1만 건을 앞질러 먼저 나가야 한다.

---

# 10. settlement-service (:8096)

배달 완료 이벤트를 모아 라이더별 정산을 집계한다.
오프셋을 리셋해서 과거분을 다시 계산하는 게 이 서비스의 존재 이유다.

## SE-01 배달 완료 집계

| | |
|---|---|
| 트리거 | `delivery.completed` 소비 (`group-id: settlement`) |
| 출력 | `settlement_detail`, `settlement_daily` 갱신 |

테이블 두 개로 나눈다.

```sql
settlement_detail  (order_id PK, rider_id, settle_date, price_krw, distance_meters, fee_krw, ...)
settlement_daily   (rider_id, settle_date) PK, order_count, distance_sum, fee_sum
```

**규칙**

1. `INSERT IGNORE INTO settlement_detail ...` 를 먼저 한다.
2. 영향 행 수가 0이면 이미 집계한 주문이다. `settlement_daily` 를 건드리지 않고 ack 한다.
3. 1이면 처음 보는 주문이다. `settlement_daily` 를 UPSERT 로 더한다.
4. 두 문장을 **한 트랜잭션**에 넣는다.

이 구조가 리플레이 멱등성의 핵심이다. `settlement_daily` 만 두고 `fee_sum = fee_sum + ?` 로
더하면, 오프셋을 리셋해서 7일치를 다시 흘렸을 때 정산이 정확히 두 배가 된다.

**완료 조건**

배달 100건을 처리한 뒤 `settlement_daily` 를 기록해두고, 오프셋을 `--to-earliest` 로
리셋해서 전부 다시 흘린다. 집계 결과가 한 원도 달라지지 않아야 한다.

## SE-02 정산 조회

| | |
|---|---|
| 엔드포인트 | `GET /api/settlements?riderId=&from=&to=` |

일자별 건수와 거리 합, 수수료 합을 돌려준다.

## SE-03 리플레이 운영 절차

문서로 남기는 항목이다. 코드가 아니다.

```bash
# 1. 컨슈머를 멈춘다 (그룹이 비어야 리셋이 된다)
./scripts/stop.sh settlement-service

# 2. 오프셋을 처음으로 되돌린다
docker exec delivery-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9094 \
  --group settlement --reset-offsets --to-earliest \
  --topic delivery.completed --execute

# 3. 다시 띄운다
./scripts/restart.sh settlement-service
```

수수료 계산 로직을 바꾼 경우에는 2번 앞에
`DELETE FROM settlement_detail; DELETE FROM settlement_daily;` 를 넣어야 새 로직으로
다시 계산된다. `INSERT IGNORE` 가 기존 행을 건너뛰기 때문이다.
이 순서를 문서에 안 적어두면 나중에 "리셋했는데 왜 금액이 안 바뀌지" 로 한참 헤맨다.

---

# 11. rider-simulator (:8097)

프론트가 없으니 이게 유일한 손잡이다. 이 서비스의 품질이 실험의 품질을 결정한다.

## SM-01 시나리오 시작

| | |
|---|---|
| 엔드포인트 | `POST /sim/start` |

요청

```json
{
  "riders": 1000,
  "ordersPerSec": 20,
  "locationIntervalMs": 3000,
  "acceptRate": 0.6,
  "rejectRate": 0.2,
  "acceptDelayMs": 2000,
  "centerLat": 37.5665, "centerLng": 126.9780, "spreadKm": 8,
  "durationSec": 600
}
```

`acceptRate` 와 `rejectRate` 를 더한 나머지가 무응답 비율이다.
위 예시는 60% 수락, 20% 즉시 거절, 20% 무응답이다. 세 경로를 다 타봐야 한다.

**규칙**

1. 라이더 한 명당 가상 스레드 하나를 쓴다. Java 21 이라 1천 명이면 그냥 돌아간다.
2. 라이더는 중심에서 `spreadKm` 안에 흩뿌리고, 매 주기마다 임의 방향으로 조금 움직인다.
   시속 20km 정도로 걸어가게 만든다. 안 움직이면 `min-move-meters` 필터에 다 걸려서
   위치 트래픽이 아예 안 생긴다.
3. 주문은 `ordersPerSec` 만큼 만든다. 가게 좌표도 같은 범위에서 뽑는다.
4. 멱등키는 요청마다 새로 만든다. 재시도 실험을 할 때만 같은 키를 재사용한다.

## SM-02 시나리오 정지

`POST /sim/stop` 으로 모든 루프를 끝낸다. 진행 중이던 주문은 그대로 둔다.

## SM-03 상태 조회

| | |
|---|---|
| 엔드포인트 | `GET /sim/status` |

```json
{
  "running": true, "elapsedSec": 143,
  "riders": 1000, "onlineRiders": 987,
  "locationsSentPerSec": 291,
  "ordersCreated": 2860, "offersReceived": 3412,
  "accepted": 2044, "rejected": 683, "ignored": 685
}
```

`offersReceived` 가 `ordersCreated` 보다 훨씬 크면 재제안이 많다는 뜻이다.
그 비율을 보는 게 배차 품질의 첫 신호다.

## SM-04 제안 수신 웹훅

| | |
|---|---|
| 엔드포인트 | `POST /sim/push` |
| 호출하는 쪽 | notification-worker (NW-02) |

```json
{ "riderId": "...", "offerId": "...", "orderId": "...", "storeName": "...", "expiresInSec": 10 }
```

**규칙**

1. 확률에 따라 셋 중 하나를 한다.
   - `acceptRate` 로 `acceptDelayMs` 뒤에 `POST /api/offers/{offerId}/accept`
   - `rejectRate` 로 즉시 `POST /api/offers/{offerId}/reject`
   - 나머지는 아무것도 하지 않는다. 타이머가 만료되게 둔다
2. 수락한 뒤에는 픽업과 배달 완료도 이어서 호출한다.
   픽업까지 5초, 배달 완료까지 30초로 짧게 잡는다. 실제로 30분을 기다릴 수 없으니
   시간을 압축한다. 이 값도 설정으로 뺀다.

**완료 조건**

`POST /sim/start` 한 번으로 주문이 생기고 제안이 가고 수락이 되고 배달까지 완료돼서
`settlement_daily` 에 행이 쌓인다. 전체 루프가 사람 손 없이 돈다.

## SM-05 부하 프로파일

| | |
|---|---|
| 엔드포인트 | `POST /sim/profile/{name}` |

미리 정의한 곡선을 재생한다. 확장 실험에서 같은 조건을 반복하려면 필요하다.

| 이름 | 무엇 |
|---|---|
| `steady` | 고정 부하. 기준선 측정용 |
| `lunch-peak` | 5분에 걸쳐 주문을 20에서 200까지 올리고 10분 유지 |
| `rider-drain` | 라이더 수를 점점 줄여서 배차 실패가 나기 시작하는 지점을 찾는다 |
| `burst` | 30초마다 10배 스파이크. 리밸런싱과 랙 스파이크 관찰용 |

---

# 12. 비기능 요구사항

## 12.1 목표 수치

| 항목 | 목표 | 재는 곳 |
|---|---|---|
| 위치 수신 지연 | p99 20ms | location-ingest HTTP 히스토그램 |
| 주문 접수 지연 | p99 100ms | order-api HTTP 히스토그램 |
| 주문에서 첫 제안까지 | p99 1초 | `dispatch_first_offer_seconds` |
| 배차 확정까지 | p99 15초 | `dispatch_duration_seconds` |
| 위치 처리량 | 초당 3000건 | `rider.location` 유입량 |
| 주문 처리량 | 초당 200건 | `order.created` 유입량 |
| 배차 성공률 | 95% 이상 | `1 - failed/created` |

목표를 못 맞추는 것 자체가 실험 재료다. 어디가 먼저 무너지는지 보는 게 목적이라
처음부터 이 수치를 다 맞추려고 하지 않는다.

## 12.2 비즈니스 지표

CPU 그래프는 장애를 알려주지 않는다. "배차가 느려졌다" 를 알려주는 건 이 지표들이다.

| 지표 | 종류 | 라벨 |
|---|---|---|
| `dispatch_first_offer_seconds` | 히스토그램 | zone |
| `dispatch_duration_seconds` | 히스토그램 | zone |
| `dispatch_attempts` | 히스토그램 | zone |
| `dispatch_failed_total` | 카운터 | zone, reason |
| `offer_expired_total` | 카운터 | zone |
| `offer_rejected_total` | 카운터 | zone |
| `rider_online_count` | 게이지 | zone |
| `push_ratelimited_total` | 카운터 | — |
| `dispatch_lock_contention_total` | 카운터 | — |

## 12.3 관측 요구사항

1. 주문 하나가 order-api 에서 시작해 notification-worker 까지 **하나의 트레이스**로 이어진다.
   카프카 레코드 헤더와 AMQP 프로퍼티 헤더로 `traceparent` 가 넘어가야 한다.
2. 모든 로그 줄에 `trace_id` 가 들어간다.
3. 그라파나에서 메트릭과 트레이스, 로그를 서로 왕복할 수 있다.
4. 인스턴스를 여러 대 띄우면 트레이스에서 어느 인스턴스가 처리했는지 구분된다.
   `scripts/_common.sh` 가 `service.instance.id` 를 이미 넣고 있다.

## 12.4 장애 견디기

| 상황 | 기대 동작 |
|---|---|
| dispatch-engine 한 대를 강제 종료 | 리밸런싱 뒤 다른 인스턴스가 이어받는다. 좀비 주문 0건 |
| 카프카 브로커 재시작 | 컨슈머가 재연결해서 처리를 이어간다. 유실 0건 |
| 레디스 재시작 | GEO 는 30초 안에 스스로 복구된다. 진행 중 배차는 실패로 정리된다 |
| 래빗엠큐 재시작 | durable 큐가 살아남아 진행 중 제안이 유지된다 |
| 같은 이벤트 중복 소비 | 배차가 두 번 일어나지 않는다 |

좀비 주문 건수를 세는 게 제일 중요한 지표다.
`state` 가 `OFFERED` 인데 `offeredAt` 이 60초를 넘은 주문을 세면 된다.

---

# 13. 범위 밖

만들지 않는다. 이걸 명시해두지 않으면 계속 늘어난다.

| 안 만드는 것 | 대신 어떻게 |
|---|---|
| 인증과 인가 | `riderId` 를 그냥 받는다. 게이트웨이도 두지 않는다 |
| 결제 | `priceKrw` 를 숫자로만 다룬다 |
| 실제 푸시나 SMS | 시뮬레이터 웹훅을 호출하고 지연을 흉내낸다 |
| 프론트엔드 | `http/*.http` 와 시뮬레이터, 그라파나가 화면 역할을 한다 |
| 가게 관리, 메뉴, 리뷰 | `storeId` 와 좌표만 받는다 |
| 정산 지급 실행 | 집계까지만 한다 |
| 다중 존 운영 정책 | `zoneId` 는 지표 라벨로만 쓴다. 확장 실험 D 에서 쓰기 시작한다 |
| 라이더 평점, 등급 | 점수 계산에 넣지 않는다. 거절 횟수만 센다 |

---

# 14. 단계별 완료 기준

각 단계가 끝났다고 말할 수 있는 기준이다. 데모로 확인한다.

## D1 — 배차 해피패스 (1단계)

```bash
./scripts/start.sh
curl -X POST localhost:8097/sim/start -d '{"riders":3,"ordersPerSec":1,"acceptRate":1.0}'
curl localhost:8090/api/orders/{orderId}
```

주문이 `ASSIGNED` 가 되고 `riderId` 가 찍힌다. 로그로 흐름이 눈에 보인다.

## D2 — 재제안과 배차 실패 (1단계)

`acceptRate` 를 0으로 두면 `attempt` 가 1에서 5까지 10초 간격으로 올라가고,
약 50초 뒤에 `FAILED` 가 된다.

## D3 — 트레이스 한 줄로 이어짐 (3단계)

그라파나 템포에서 주문 하나를 검색하면 order-api 에서 notification-worker 까지
스팬이 하나의 트레이스로 이어져 보인다. 카프카와 래빗엠큐 구간이 끊기지 않는다.

## D4 — 확장 시나리오 네 개 (4단계)

시나리오 A 부터 D 까지 각각 재현하고 그래프를 캡처한다.
"인스턴스를 늘렸는데 처리량이 안 늘어난다" 가 그래프에서 평평한 선으로 보인다.

## D5 — 좀비 0건 (5단계)

부하가 걸린 상태에서 `kill -9` 로 dispatch-engine 을 한 대 죽인다.
1분 뒤에 좀비 주문을 세면 0건이다.

## D6 — 리플레이 후 금액 불변 (6단계)

`settlement_daily` 를 기록하고 오프셋을 리셋해서 전부 다시 흘린다.
집계 결과가 한 원도 달라지지 않는다.

---

# 15. 아직 안 정한 것

만들면서 정할 것들이다. 지금 정하면 근거 없이 정하게 되니 미뤄둔다.

1. **후보를 다 썼을 때 반경을 넓혀 재시도할까.** `dispatch_attempts` 분포를 보고 정한다.
2. **점수 공식의 대기 보너스 계수 0.1 이 맞나.** 특정 라이더만 콜을 먹는지 보고 조정한다.
3. **좀비 판정 임계값 30초가 맞나.** offer-relay 의 큐 랙을 보고 정한다.
   짧으면 정상 제안을 좀비로 오판하고, 길면 방치가 길어진다.
4. **제안 유효시간 10초가 맞나.** 짧으면 라이더가 알림을 볼 시간이 없고 길면 고객이 기다린다.
5. **주문 파티션 키를 `orderId` 로 둘까 `zoneId` 로 바꿀까.** 확장 시나리오 D 와 직결된다.
6. **`dispatch:offer` 를 DB 에도 쓸까.** 2단계 저장소 비교 실험에서 답이 나온다.
