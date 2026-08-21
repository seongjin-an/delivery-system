# TODO — 무엇을 만들어 나갈지

배달 배차 시스템을 만들면서 카프카 · 레디스 · 래빗엠큐를 각자 제 자리에서 쓰고,
로키 · 프로메테우스 · 템포 · OTel 수집기로 그 시스템을 들여다보고,
마지막에 서비스별로 다른 수평확장 전략을 직접 골라보는 것이 목표다.

기능을 많이 만드는 게 목표가 아니다. **"이 기술이 왜 여기 있는지" 를 몸으로 아는 것**이 목표다.
그래서 각 단계마다 "이걸 왜 하나" 를 적어뒀다. 그게 사라지면 그냥 CRUD 만드는 것과 같아진다.

진행 원칙
- 한 단계가 끝날 때마다 `README.md` 의 해당 절을 갱신한다.
- 확장 실험은 반드시 **그래프를 캡처하고 판단 근거를 적는다** (`.reference/scaling-scenarios.md`).
- 막힌 지점과 그 이유는 코드 주석에 남긴다. 나중에 이게 제일 값진 기록이 된다.

---

## 0단계 — 뼈대 (완료)

- [x] 멀티모듈 구조 (`libs/`, `services/`, `infra/`, `scripts/`, `http/`, `.reference/`)
- [x] Gradle 8.13 Kotlin DSL · Java 21 · Spring Boot 3.5.0
- [x] 서비스 8개 모듈 + 각각 `/hello` 만
- [x] `libs/common` — 토픽/큐/레디스 키 이름 상수, 이벤트 레코드, JSON 유틸
- [x] 인프라 컴포즈 — MySQL, Redis, Kafka(KRaft), RabbitMQ, OTel Collector, Prometheus, Loki, Tempo, Grafana, exporter 2종
- [x] `infra/create-topics.sh` — 파티션 수를 못 박은 토픽 생성
- [x] `scripts/` — start · stop · status · logs · build · restart · **scale**
- [x] 관측 설정 파일 — 수집기 파이프라인, 프로메테우스 스크레이프 + 확장 판단 룰, 로키/템포 설정, 그라파나 데이터소스 프로비저닝

### 0단계에서 실제로 확인한 것

- [x] `./scripts/start.sh` 로 컨테이너 15개 + 서비스 8개 기동, 8개 포트 모두 `/hello` 응답
- [x] 프로메테우스 스크레이프 타깃 15개 전부 `up` (앱 actuator 8 + 인프라 익스포터 7)
- [x] 로키에 8개 서비스 로그 도착, 각 줄에 `trace_id` 붙어 있음
- [x] 템포에 트레이스 도착, 서비스 7개 인식
- [x] 수집기 tail sampling 동작 — `/hello` 40회 중 5건 저장(10% 확률 + 느린 것 전량)
- [x] `/actuator/prometheus` 스크레이프 스팬은 수집기 filter 로 버림 (5초마다 15개씩 쌓여 실제 트레이스를 덮었다)
- [x] `./scripts/scale.sh geo-indexer 3` → 8092/8192/8292 세 인스턴스 기동, `1` 로 되돌리면 추가분만 정리

기동 중 잡은 것 두 개
- `geo-indexer` 에 lettuce 풀만 켜고 `commons-pool2` 를 안 넣어서 기동 실패 → 의존성 추가
- macOS 기본 bash 3.2 + `set -u` 에서 빈 배열 전개(`"${arr[@]}"`)가 unbound variable 로 죽음
  → `${arr[@]+"${arr[@]}"}` 로 감쌈 (create-topics.sh, _common.sh, status.sh)

---

## 1단계 — 주문에서 배차까지 해피패스

세 기술이 한 요청 안에서 처음 만나는 단계. 여기까지 돌면 프로젝트의 척추가 생긴 것이다.

### order-api
- [ ] `POST /api/orders` — 주문 생성 (가게 좌표, 목적지 좌표, zoneId, 금액)
- [ ] `orders` 테이블 + `outbox` 테이블
- [ ] **아웃박스 패턴** — 주문 INSERT 와 이벤트 INSERT 를 한 트랜잭션에 넣는다
      *왜: "DB엔 주문이 있는데 배차가 안 걸렸다" 를 구조적으로 막는다. 카프카 발행 실패와 커밋 실패가 갈라지는 순간이 없어진다.*
- [ ] 아웃박스 폴러 → `order.created` 발행 (key = orderId)
- [ ] `GET /api/orders/{id}` — 배차 상태 조회
- [ ] 레디스 멱등키 — `Idempotency-Key` 헤더로 같은 주문 두 번 생성 막기

### location-ingest
- [ ] `POST /api/riders/{id}/location` — 좌표 수신 → `rider.location` 발행 (key = riderId)
      *왜: 이 서비스는 상태가 하나도 없다. 그래서 시나리오 A 의 기준선이 된다.*
- [ ] 프로듀서 튜닝 — `linger.ms`, `batch.size`, `compression.type=lz4`, `acks=1`
      *왜: 위치는 한 점 잃어도 3초 뒤 다음 점이 온다. 신뢰성보다 처리량이 맞는 유일한 토픽.*
- [ ] 이동거리 필터 — 15m 미만이면 발행 생략 (정차 중인 라이더가 트래픽 다 먹는 것 방지)

### geo-indexer
- [ ] `rider.location` 컨슈머 → 레디스 `GEOADD riders:online`
- [ ] `rider:state:{id}` 해시 갱신 (status, lastSeenAt, currentOrderId)
- [ ] 오프라인 정리 — 일정 시간 좌표가 없는 라이더를 `GEO` 에서 제거
- [ ] 수동 ack + `auto-offset-reset: latest`
      *왜: 밀린 위치는 쓸모없다. 과거를 따라잡느니 현재부터 보는 게 맞다.*

### dispatch-engine
- [ ] `order.created` 컨슈머
- [ ] `GEOSEARCH` 로 반경 3km 후보 조회 → 점수 계산(거리 + 대기시간) → 상위 10명
- [ ] 후보 목록을 레디스 리스트 `dispatch:candidates:{orderId}` 에 저장
- [ ] `SET NX PX` 로 배차 락 — 같은 주문을 두 인스턴스가 동시에 배차하는 것 방지
- [ ] 1순위에게 배차 제안 → 래빗엠큐 `dispatch.x` 로 `offer.created` 발행
- [ ] `POST /api/offers/{offerId}/accept` — 라이더 수락 접수 → `dispatch.assigned` 발행

### offer-relay
- [ ] **래빗엠큐 토폴로지 선언** — `RabbitTopologyConfig`
      - `dispatch.x` (topic) → `dispatch.offer.timer` (TTL 10s, DLX, **컨슈머 없음**)
      - `dispatch.x` → `dispatch.offer.notify` (알림 워커가 소비)
      - `dispatch.dlx` → `dispatch.offer.expired` (여기를 offer-relay 가 소비)
      *왜: 이게 래빗엠큐를 쓰는 이유 전부다. "특정 한 명에게, 10초 안에, 안 받으면 다음 사람" 을 카프카로는 못 만든다.*
      *주의: 타이머 큐에 리스너를 붙이면 TTL 이 흐를 틈이 없어서 재제안이 영원히 안 돈다.*
- [ ] 만료 제안 수신 → 레디스에서 수락 여부 확인 → 이미 수락됐으면 버림
- [ ] 아니면 `LPOP` 으로 다음 후보 꺼내 재제안 (attempt + 1)
- [ ] `max-attempts` 소진 시 `dispatch.failed` 발행
- [ ] `lock:rider:{id}` — 한 라이더에게 두 주문이 동시에 제안되는 것 방지

### notification-worker
- [ ] `dispatch.offer.notify` 소비 → 가짜 푸시 발송 (지연 흉내)
- [ ] `notify.push` 우선순위 큐 (`x-max-priority=10`) — 배차 제안 9, 마케팅 1
- [ ] 실패 시 DLQ

### rider-simulator
- [ ] `POST /sim/start` — 라이더 N명이 3초마다 좌표 전송 (가상 스레드)
- [ ] 주문 생성기 — 초당 M건
- [ ] 라이더 응답기 — 확률 `accept-rate` 로 수락, `accept-delay-ms` 뒤에
      *왜: 프론트가 없으니 이게 유일한 손잡이다. 이 서비스의 품질이 실험의 품질을 결정한다.*

**1단계 완료 조건:** 시뮬레이터를 켜면 주문이 생기고, 배차 제안이 가고, 수락하면 배차가 확정되고,
아무도 안 받으면 10초 뒤 다음 라이더에게 넘어간다. 로그로 그 흐름이 보인다.

---

## 2단계 — 거꾸로도 해보기 (기술 선택의 근거 만들기)

읽어서 아는 것과 겪어서 아는 것의 차이가 여기서 갈린다. 각 실험은 브랜치에서 하고 결과만 문서에 남긴다.

- [ ] **위치 스트림을 래빗엠큐로 바꿔본다**
      *예상: 큐가 메모리를 먹다가 flow control 이 걸리고, 컨슈머가 밀리면 브로커가 먼저 죽는다.*
      *카프카는 디스크에 순차 append 하고 컨슈머는 오프셋만 들고 있어서 밀려도 브로커는 멀쩡하다.*
- [ ] **배차 제안을 카프카로 바꿔본다**
      *예상: "이 라이더에게만, 10초 TTL" 을 표현할 방법이 없다. 스케줄러 테이블을 따로 만들게 되는데,*
      *그게 곧 래빗엠큐의 TTL+DLX 를 손으로 재구현하는 것이라는 걸 알게 된다.*
- [ ] **레디스 GEO 없이 MySQL 공간 인덱스로 후보를 찾아본다**
      *예상: 주문 하나당 쿼리 하나. 초당 200 주문이면 DB 가 먼저 눕는다.*
- [ ] 결과를 `.reference/tech-choice.md` 에 표로 정리 — "왜 이 셋을 같이 쓰는지" 에 대한 답

---

## 3단계 — 관측 붙이기

이 프로젝트에서 배울 게 가장 많은 단계. 특히 **비동기 경계를 넘는 트레이스**.

### 트레이싱 (템포)
- [ ] OTel 자바 에이전트로 전 서비스 계측 (`scripts/_common.sh` 에 준비돼 있음)
- [ ] 주문 하나가 order-api → 카프카 → dispatch-engine → 래빗엠큐 → notification 까지
      **하나의 트레이스**로 이어지는지 확인
- [ ] 카프카 레코드 헤더 / AMQP 프로퍼티 헤더의 `traceparent` 를 직접 찍어서 눈으로 확인
      *왜: HTTP 만 계측해본 사람이 여기서 한 번 크게 성장한다. 헤더가 안 붙으면 트레이스가 서비스마다 뚝뚝 끊긴다.*
- [ ] DLX 로 반송됐다 재제안된 메시지의 트레이스 이어붙이기 (링크 vs 부모-자식 중 무엇이 맞는지 판단)
- [ ] 에이전트를 끄고 마이크로미터 트레이싱으로 직접 계측해보기 — 에이전트가 뭘 해주고 있었는지 알게 된다

### 로그 (로키)
- [ ] 로그를 JSON 으로, `trace_id`/`span_id` 포함
- [ ] 수집기 → 로키 OTLP 경로 확인
- [ ] 그라파나에서 로그 → 트레이스 점프, 트레이스 → 로그 역점프 둘 다 동작

### 메트릭 (프로메테우스)
- [ ] 인프라 지표: 컨슈머 랙, 큐 깊이, unacked, 레디스 히트율/명령수
- [ ] **비즈니스 지표** (이게 핵심)
      - `dispatch_duration_seconds` 히스토그램 — 주문 접수부터 배차 확정까지
      - `dispatch_attempts` 분포 — 몇 번째 후보에서 잡혔나
      - `dispatch_failed_total` — 후보 소진
      - `offer_expired_total` — 제안 만료
      *왜: CPU 그래프는 장애를 알려주지 않는다. "배차가 느려졌다" 를 알려주는 건 이 지표들이다.*
- [ ] 익셈플러 — p99 그래프의 점을 클릭해 그 순간의 트레이스로 점프
- [ ] 템포 span-metrics + 서비스 그래프 확인 (코드 없이 나오는 RED 지표)

### 대시보드
- [ ] 유량 — 유입량 / 처리량 / 랙 / 큐 깊이
- [ ] 지연 — 배차 소요시간 p50/p95/p99, 단계별 분해
- [ ] 비즈니스 — 배차 성공률, 재제안 횟수, 존별 라이더 수
- [ ] `infra/grafana/provisioning/dashboards/json/` 에 JSON 저장 (재현 가능하게)

### 수집기 파이프라인
- [ ] tail sampling 동작 확인 — 느린 배차만 전량 저장되는지
- [ ] 앱에서 백엔드 주소를 지워도 되는 이유(게이트웨이 모드) 정리

---

## 4단계 — 수평확장 실험

`.reference/scaling-scenarios.md` 의 시나리오 A/B/C/D 를 하나씩 재현한다.
**각각 그래프 캡처 + 판단 근거 기록이 산출물.**

- [ ] A. `location-ingest` — 늘리면 그냥 되는 케이스 (기준선)
- [ ] A-1. nginx 로드밸런서 추가 (`infra/nginx/`) — 인스턴스가 여러 개면 앞단이 필요하다
- [ ] B. `geo-indexer` — 파티션 천장. 인스턴스 3개부터 그래프가 평평해지는 것 확인
- [ ] B-1. 파티션 6 → 12 확장 vs 파티션 키 재설계(riderId → zoneId) 비교
- [ ] B-2. 리밸런싱 관찰 — 인스턴스 추가 순간의 랙 스파이크, cooperative sticky 로 개선
- [ ] C. `notification-worker` — 외부 병목. 늘리면 429 폭발
- [ ] C-1. 레디스 토큰버킷 전역 레이트리밋 + prefetch 조정으로 해결
- [ ] D. `dispatch-engine` — 락 경합. 늘렸는데 처리량이 떨어지는 것 확인
- [ ] D-1. zone 단위 락 샤딩으로 해결
- [ ] 네 시나리오 비교표 작성 — 같은 "랙" 신호에 네 개의 다른 처방이 나온다는 결론

---

## 5단계 — 자동화와 고장 견디기

- [ ] 도커라이즈 — 서비스별 Dockerfile (jib 또는 Boot 이미지 빌드)
- [ ] 컴포즈로 앱까지 올려서 `--scale` 로 늘리기
- [ ] KEDA(또는 스크립트)로 자동 스케일 — `infra/prometheus/rules/scaling.yml` 의 룰을 트리거로
      - 랙 기반 트리거에 **파티션 수를 maxReplicas 로 캡** 하는 이유를 문서에 남긴다
- [ ] 카오스 테스트
      - [ ] 카프카 브로커 재시작 → 리밸런싱과 재처리 관찰
      - [ ] 레디스 재시작 → 후보 목록이 날아갔을 때 배차가 어떻게 되는지 (재구성 vs 실패)
      - [ ] 래빗엠큐 재시작 → durable 큐가 정말 살아 있는지, 진행 중 제안은 어떻게 되는지
      - [ ] 컨슈머 강제 종료 → at-least-once 로 인한 중복 처리 확인, 멱등 처리 검증
- [ ] 스키마 확정 — `infra/mysql/init.sql` 로 옮기고 `ddl-auto: validate` 로 변경

---

## 6단계 — 정리

- [ ] `settlement-service` — `delivery.completed` 소비해 라이더별 일일 정산 집계
- [ ] **오프셋 리셋으로 과거분 재계산** 해보기
      `kafka-consumer-groups --reset-offsets --to-earliest --group settlement --execute`
      *왜: 카프카를 쓰는 가장 실감 나는 이유. 정산 로직 버그를 고치고 지난 이벤트를 다시 흘려보낸다.*
      *멱등하게 안 짜두면 정산이 두 배로 찍힌다 — 그 실패를 한 번 겪어보는 것도 포함.*
- [ ] README 에 아키텍처 다이어그램(mermaid) 완성
- [ ] `.reference/` 문서 정리 — 기술 선택 근거, 확장 시나리오 결과, 겪은 함정 목록
- [ ] 처음 보는 사람이 `./scripts/start.sh` 하나로 전부 띄울 수 있는지 확인

---

## 나중에 (하고 싶어지면)

- [ ] 카프카 스트림즈로 존별 실시간 수급 집계 (라이더 대비 주문 비율)
- [ ] 아웃박스 폴러를 Debezium CDC 로 교체
- [ ] Redisson 분산락 vs 직접 만든 `SET NX PX` 비교
- [ ] 쿠버네티스로 옮기고 HPA/KEDA 를 제대로
- [ ] 트랜잭셔널 프로듀서(exactly-once) 실험 — 얼마나 느려지는지 재본다
