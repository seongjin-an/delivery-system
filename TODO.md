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

> **코드를 짜기 전에 읽을 것**
> - [`.reference/functional-spec.md`](.reference/functional-spec.md) — 아래 항목의 `OR-01` 같은 번호가
>   전부 이 문서의 기능 번호다. 입력과 출력, 규칙, 예외, 완료 조건이 거기 다 있다.
> - [`.reference/flow-scenarios.md`](.reference/flow-scenarios.md) — 2번(해피패스), 7번(라이더 경합),
>   9번(수락과 만료 경합)이 여기서 바로 구현할 내용이다.
> - [`.reference/dispatch-internals.md`](.reference/dispatch-internals.md) — 5장(락 두 개의 역할 차이),
>   8장(수락 CAS), 11장(하나씩 빼먹으면 어떻게 깨지는지)

### 공통 (COM)
- [x] `libs/common` 에 `RedisKeys.ridersHeartbeat` 추가 — 오프라인 정리용 ZSET 인덱스
- [x] 좌표 검증 유틸 (한국 범위) + `zoneId` 계산 유틸 — `common.geo.Coordinates` / `Zones`
- [x] `GlobalExceptionHandler` — 기능 정의서 3.6 의 에러 코드 표대로 (`ErrorCode` enum + `ApiResponse.code`)
- [x] 컨슈머 공통 에러 핸들러 — `BusinessException` 은 즉시 DLT, 나머지는 3회 백오프

공통에서 겪은 것
- `common` 은 서비스 패키지(`com.delivery.orderapi` 등) 밖이라 컴포넌트 스캔에 안 걸린다.
  서비스마다 `@Import` 를 붙이는 대신 자동설정(`META-INF/spring/...AutoConfiguration.imports`)으로 올렸다.
- 그래서 `spring-boot-starter-web` 과 `spring-kafka` 는 `compileOnly` 로 둔다. `api` 로 걸면
  카프카를 안 쓰는 `rider-simulator` 까지 끌고 온다. 자동설정의 `@ConditionalOnClass` 가 그걸 받아준다.
- DLT 발행은 목적지 파티션을 `-1` 로 넘긴다. 기본값은 원본과 같은 파티션 번호인데
  원본은 6파티션, DLT 는 3파티션이라 4~6번에서 실패한 레코드가 발행부터 실패한다.
- `ExponentialBackOffWithMaxRetries` 는 스프링 코어에 없다. `ExponentialBackOff.setMaxAttempts()` 를 쓴다.
- `ApiResponse` 에 `code` 를 붙였다(실패일 때만 직렬화). 시뮬레이터가 `ALREADY_TAKEN` 과
  `OFFER_EXPIRED` 를 갈라서 다음 행동을 정해야 하는데 사람이 읽는 `message` 로는 못 가른다.
- 첫 테스트를 돌리자마자 `OutputDirectoryProvider not available` 로 죽었다. 그래들 8.13 이 들고 있는
  junit-platform-launcher 가 부트 3.5 BOM 의 엔진보다 낮아서다. 루트 `build.gradle.kts` 에
  `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` 를 넣어 버전을 맞췄다.

### order-api
- [x] `OR-01` `POST /api/orders` — 주문 생성, 멱등키 필수
      *왜 아웃박스인가: 주문 INSERT 와 이벤트 INSERT 를 한 트랜잭션에 넣으면 "DB엔 주문이 있는데
      배차가 안 걸렸다" 가 구조적으로 안 생긴다. 카프카 발행 실패와 커밋 실패가 갈라지는 순간이 없어진다.*
- [x] `OR-02` `GET /api/orders/{orderId}` — 상태와 attempt, timeline (`order_status_history` 테이블 추가)
- [ ] `OR-03` `POST /api/orders/{orderId}/pickup`
- [ ] `OR-04` `POST /api/orders/{orderId}/complete` — `delivery.completed` 발행, 라이더 해제
- [ ] `OR-05` `POST /api/orders/{orderId}/cancel` — 진행 중 제안을 `CANCELLED` 로
- [x] `OR-06` 아웃박스 폴러 200ms — `SELECT ... FOR UPDATE SKIP LOCKED`
- [ ] `OR-07` `dispatch.assigned` / `dispatch.failed` 소비 → 상태 반영 (조건부 갱신으로 멱등)
- [x] `orders`, `outbox` 테이블 — JPA 엔티티로 잡았다 (`ddl-auto: update`)

OR-01 에서 확인한 것 (MySQL + 레디스만 띄우고 order-api 한 대)
- 정상 주문 201, 같은 멱등키로 다시 부르면 200 에 같은 `orderId`, `orders` 는 1건 그대로
- 같은 멱등키로 동시에 10번 때렸더니 201 하나에 200 아홉 개, 주문 행은 1건이었다.
  레디스 `SET NX` 가 문 앞에서 하나만 통과시킨다는 걸 눈으로 봤다.
- 멱등키 없음 → `MISSING_IDEMPOTENCY_KEY`, 도쿄 좌표 → `INVALID_COORDINATE`,
  강남→강릉(165km) → `INVALID_REQUEST`, 금액 0 → `INVALID_REQUEST` 전부 400
- `outbox` 행의 `published_at` 은 아직 NULL 이다. 채우는 건 OR-06 폴러 몫.

OR-01 에서 정한 것
- `orders` PK 는 TSID(`BIGINT`), `outbox` PK 는 자동증가 정수. 주문 아이디는 카프카 키와
  레디스 키로 그대로 흘러다녀야 해서 DB 에 들어가기 전에 이미 있어야 하고, 아웃박스는 폴러가
  오래된 것부터 집어가야 해서 "먼저 들어온 게 반드시 작은 번호" 인 게 중요하다.
  (처음엔 UUIDv7 문자열로 갔다가 TSID 로 바꿨다. 아래 참고)
- 아이디를 우리가 직접 넣으면 스프링 데이터 `save()` 가 "이미 있는 행인가" 를 확인하려고
  SELECT 를 한 번 날리고 INSERT 한다. 주문마다 쓸데없는 쿼리가 하나씩 붙어서
  `Persistable` 을 구현해 `isNew` 를 직접 알려줬다.
- 거리는 접수 때 한 번 재서 `distance_meters` 에 저장한다. 20km 검사하느라 어차피 재는데,
  OR-04 의 `delivery.completed` 에 또 필요하다.
- 저장이 실패하면 멱등키를 놓아준다. 안 그러면 실패한 요청의 키가 한 시간 남아서
  손님이 다시 눌러도 막히는데 정작 주문은 어디에도 없다.

아이디 타입을 UUIDv7 → TSID 로 바꾼 이유 (OR-02 들어가기 전에 정리)
- UUIDv7 `CHAR(36)` 은 한 행에 36바이트인데, InnoDB 는 세컨더리 인덱스마다 PK 를 통째로
  복사해서 들고 있다. 인덱스가 하나만 있어도 값이 두 번 저장되는 셈이다.
  TSID 는 `BIGINT` 8바이트라 같은 자리에서 4분의 1이 안 된다.
- `AUTO_INCREMENT` 는 안 된다. 아이디가 DB 에 들어가기 전에 이미 카프카 키와 레디스 키로
  필요해서다. 게다가 1씩 늘어나는 주문번호는 아침저녁으로 한 번씩 주문해보면
  하루 주문량이 그대로 새어 나간다.
- TSID 는 앞쪽 42비트가 타임스탬프라 UUIDv7 처럼 시간순으로 늘어난다. 미리 만들 수 있다는
  점도 같다. 크기만 줄인 셈이다.
- **인스턴스마다 노드 번호를 줘야 한다.** 안 주면 라이브러리가 무작위로 고르는데,
  `scale.sh` 로 인스턴스를 늘렸다 줄였다 하다 보면 언젠가 두 대가 같은 번호를 뽑는다.
  `_common.sh` 가 포트를 1024 로 나눈 나머지를 `-Dtsidcreator.node` 로 넣는다.
  (8090~8097, 8190~8197, 8290~8297 은 나머지가 서로 안 겹치는 걸 확인했다)
- JSON 에는 숫자로 나간다. 소비자가 전부 자바라 괜찮은데, 나중에 브라우저 프론트가 붙으면
  문자열로 바꿔야 한다. 자바스크립트 정수는 2^53 까지만 안전해서 TSID 뒷자리가 뭉개진다.

OR-02 에서 정한 것과 겪은 것
- timeline 때문에 `order_status_history` 테이블을 새로 만들었다. `orders` 행은 "지금 상태"
  하나만 들고 있어서 CREATED → DISPATCHING → ASSIGNED 로 넘어가면 앞의 둘이 덮여 사라진다.
  고객 지원에서 제일 자주 묻는 게 "왜 오래 걸렸냐" 인데 그걸 답하려면 단계별 시각이 남아야 한다.
- 상태마다 컬럼을 따로 두는 방법(assigned_at, picked_up_at ...)은 안 쓴다. 상태가 늘 때마다
  컬럼이 늘고, 배차는 후보가 바뀌면서 DISPATCHING 을 여러 번 지나가는데 그걸 못 담는다.
- `OrderStatusRecorder` 를 따로 뒀다. 레포지토리 직접 호출이면 한 줄인데, OR-03/04/05/07 이
  전부 상태를 바꿔서 그중 하나만 기록을 빼먹으면 timeline 에 구멍이 뚫린다. 이름 있는 자리를
  만들어두면 빼먹었을 때 눈에 띈다.
- **시각이 마이크로초로 나가는 걸 잡았다.** 자바 9부터 `Instant.now()` 가 OS 가 주는 대로
  마이크로초까지 받아온다. 기능 정의서 3.2 는 밀리초인데 응답에 `13:14:20.568110Z` 가 찍혔다.
  같은 시각을 MySQL(datetime(6)), 레디스(epoch ms 정수), JSON 세 군데에 넣는데 레디스에서만
  잘리면 나중에 두 값을 빼봤을 때 미묘하게 어긋난다. `common.Times.now()` 로 만들 때부터 자른다.

OR-06 에서 확인한 것 (완료 조건 그대로)
- order-api 두 대(8090, 8190)에 번갈아 주문 100건 → `order.created` 에 **정확히 100건**,
  메시지 키(orderId)가 전부 서로 달라서 중복 0. `outbox` 100행 모두 `published_at` 채워짐.
- 폴링 지연(아웃박스에 들어간 뒤 카프카로 나가기까지): 최소 58ms, 평균 297ms, 최대 901ms.
  **이 숫자가 Debezium 으로 바꿨을 때 비교할 기준선이다.** 200ms 주기니까 평균이 그 절반쯤
  나올 줄 알았는데, 100건이 한꺼번에 몰리면서 한 배치에 다 안 들어가 뒤로 밀린 게 있다.
- 실제로 나가는 SQL 확인:
  `SELECT * FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED`

SKIP LOCKED 가 왜 필요한지 세션 두 개로 직접 봤다
- 세션 A 가 111~113 을 잠근 상태에서
  - `FOR UPDATE SKIP LOCKED` → **275ms** 만에 **114~116** 을 받아간다 (다른 행)
  - `FOR UPDATE` 만 → **3787ms** 를 기다렸다가 **111~113** 을 받는다 (같은 행)
- 두 번째가 문제인 이유가 두 겹이다. 기다리느라 인스턴스를 늘린 의미가 없어지고,
  기다린 끝에 받는 게 앞사람이 방금 발행한 그 행이라 카프카에 같은 이벤트가 두 번 나간다.

OR-06 에서 정한 것
- 스케줄러(`OutboxPoller`)와 트랜잭션(`OutboxRelay`)을 다른 빈으로 나눴다. 같은 클래스 안에서
  `@Transactional` 메서드를 부르면 프록시를 안 거쳐서 트랜잭션이 안 걸리고, 그러면
  `SELECT ... FOR UPDATE` 가 즉시 커밋돼 잠금이 하나도 안 남는다. 조용히 잘못되는 종류다.
- `fixedRate` 가 아니라 `fixedDelay`. 한 번이 200ms 를 넘겼을 때 다음 실행이 겹쳐서 몰아치는 걸 막는다.
- 폴러의 `poll()` 은 예외를 반드시 삼킨다. 스프링 스케줄러는 예외가 새어 나가면 그 작업을
  아예 다시 안 돌린다. 그러면 아웃박스가 조용히 쌓이기만 하고 아무도 모른다.
- 100건을 한 건씩 "보내고 확인" 하면 왕복이 100번이라 200ms 주기를 못 맞춘다. 다 던져놓고
  `flush()` 한 다음 확인만 돌린다.
- 실패해도 버리지 않는다. `attempt_count` 만 올리고 10회를 넘으면 경고를 남긴다. 몇 번 실패했다고
  포기하면 "DB 엔 주문이 있는데 배차가 안 걸린" 주문이 생겨서 아웃박스를 쓴 이유가 사라진다.

Debezium CDC 로 교체하면서 (기본값이 `delivery.outbox.mode=CDC` 로 바뀌었다)
- 지연이 평균 **297ms → 33ms** 로 줄었다 (중앙값 16ms, p95 140ms). 폴러가 느린 건 코드가
  나빠서가 아니라 주기가 200ms 라 방금 들어온 행이 평균 100ms 를 그냥 기다려서다.
  자세한 비교는 `.reference/tech-choice.md` 1번.
- **커넥터가 RUNNING 인데 이벤트가 한 건도 안 나가는 걸로 한참 헤맸다.** 상태만 보면 멀쩡했는데
  커넥트 로그에 `{delivery=UNKNOWN_TOPIC_OR_PARTITION}` 이 계속 찍히고 있었다.
  Debezium MySQL 커넥터는 DDL 변경 이벤트를 `topic.prefix` 와 같은 이름의 토픽에 쓰는데,
  우리는 파티션 수를 못 박으려고 브로커 auto-create 를 꺼놨으니 그 토픽이 없다.
  `include.schema.changes=false` 로 껐다. **커넥터 상태가 RUNNING 이라고 일이 되고 있는 게 아니다.**
- `snapshot.mode=schema_only` 다. 처음 붙을 때 기존 행을 안 읽는다. 폴러가 이미 내보낸
  것들이라 다시 보내면 중복이 되기 때문인데, 처음부터 CDC 로 시작한다면 `initial` 이어야 한다.
- 폴러 코드는 안 지웠다. `delivery.outbox.mode=POLLER` 로 띄우면 다시 돈다.
  같은 조건에서 다시 재보려면 필요하고, 커넥트 없이 앱만 띄울 때도 쓸 수 있다.
- **아웃박스 행을 치우는 일이 새로 생겼다.** 폴러 때는 `published_at` 이 기록이라 남겨뒀는데,
  CDC 는 binlog 를 읽으므로 행은 binlog 에 적히는 순간 할 일이 끝난다. 안 치우면 계속 쌓이기만
  한다 — 초당 200 주문이면 하루 1700만 행이다. `OutboxPurger` 가 한 시간 지난 행을 지운다.
  바로 안 지우고 한 시간 두는 건 "이벤트가 들어가긴 했나" 를 눈으로 볼 창을 남기려는 것이다.
- 스크립트 버그로 한 번 시간을 날렸다. 같은 셸에서 `nohup java ... &` 로 앱을 띄우고 그 뒤에
  `curl ... & ... wait` 를 쓰면, `wait` 가 자바 프로세스까지 기다려서 영영 안 끝난다.
  앱 기동과 부하 주기는 셸을 나눠야 한다.

### location-ingest
- [ ] `LI-01` `POST /api/riders/{riderId}/location` → `rider.location` (key = riderId)
      *왜: 이 서비스는 상태가 하나도 없다. 그래서 확장 시나리오 A 의 기준선이 된다.*
- [ ] 프로듀서 튜닝 — `acks=1`, `linger.ms=20`, `batch.size=64KB`, `compression.type=lz4`
      *왜: 위치는 한 점 잃어도 3초 뒤 다음 점이 온다. 신뢰성보다 처리량이 맞는 유일한 토픽.*
- [ ] 이동거리 필터 15m — 직전 좌표를 인스턴스 메모리에 캐시
      *왜 레디스를 안 쓰나: 왕복이 생기면 무상태라는 이점이 사라진다. 인스턴스가 늘면 필터가
      느슨해지는데 그건 감수한다 (기능 정의서 LI-01 규칙 2번)*

### geo-indexer
- [ ] `GI-01` `rider.location` 소비 → `GEOADD` + `HSET rider:state` + `ZADD riders:heartbeat`
- [ ] 배치 안에서 라이더별로 마지막 좌표만 남기고 파이프라인으로 한 번에 쓰기
- [ ] `status` 는 조건부로만 갱신 — `OFFERED` / `DELIVERING` 은 절대 안 건드린다
      *안 지키면: 배달 중 라이더가 새 주문 후보로 다시 잡힌다 (흐름 시나리오 10번)*
- [ ] `GI-02` 오프라인 정리 10초 주기 — `ZRANGEBYSCORE` 로 대상 찾고 `SET lock:sweep NX` 로 단독 실행
- [ ] `GI-03` `GET /api/riders/{riderId}/state` — 디버깅용
- [ ] 수동 ack + `auto-offset-reset: latest`
      *왜: 밀린 위치는 쓸모없다. 과거를 따라잡느니 현재부터 보는 게 맞다.*

### dispatch-engine
- [ ] `DE-01` `order.created` 소비 — 리스 획득 → 좀비 판정 → 후보 검색 → 제안
- [ ] 좀비 판정 표 그대로 구현 (기능 정의서 DE-01 규칙 2번)
      *`EXISTS` 만 보면 "해시는 있는데 타이머가 없는" 주문이 영구 방치된다*
- [ ] `DE-02` `GEOSEARCH ... ASC COUNT 30` → 파이프라인으로 상태 조회 → 점수순 10명
      *`COUNT` 와 `ASC` 를 같이 줘야 조기 종료된다. 안 주면 반경 안 500명을 다 계산한다*
- [ ] `DE-03` 제안 발송 — `lock:rider` 획득, 새 `offerId` 발급, 한 번만 발행
- [ ] publisher confirm — 실패하면 롤백하고 카프카 ack 하지 않기
- [ ] `DE-04` `POST /api/offers/{offerId}/accept` — Lua CAS, 반환값 4가지를 HTTP 응답으로
- [ ] `DE-05` `POST /api/offers/{offerId}/reject` — `dispatch.dlx` 에 직접 발행해 즉시 다음 후보로
- [ ] `DE-06` `GET /api/dispatch/{orderId}` — 레디스 상태 덤프
- [ ] Lua 스크립트 3종 — 락 해제, 제안 수락, 제안 만료

### offer-relay
- [ ] `RE-01` 래빗엠큐 토폴로지 선언 — `RabbitTopologyConfig`
      *왜: 이게 래빗엠큐를 쓰는 이유 전부다. "특정 한 명에게, 10초 안에, 안 받으면 다음 사람" 을
      카프카로는 못 만든다.*
      *주의: 타이머 큐에 리스너를 붙이면 TTL 이 흐를 틈이 없어서 재제안이 영원히 안 돈다.*
- [ ] `RE-02` 만료 제안 처리 — 규칙 8단계를 순서대로
- [ ] **펜싱 규칙** — 메시지의 `offerId` 가 레디스의 현재 `offerId` 와 다르면 버린다
      *안 지키면: 거절로 이미 다음 후보에게 넘어갔는데 옛 타이머가 그걸 또 끊는다 (기능 정의서 3.9)*
- [ ] 직전 라이더 `lock:rider` 해제 + `status` 를 `IDLE` 로
      *빼먹으면 그 라이더가 12초 동안 다른 주문의 후보가 못 된다*
- [ ] `max-attempts` 소진 시 `dispatch.failed` 발행

### notification-worker
- [ ] `NW-01` `dispatch.offer.notify` 소비 → `notify.push` 에 priority 9 로 투입
- [ ] `NW-02` `notify.push` 소비 → 시뮬레이터 웹훅으로 POST (가짜 푸시)
      *이 웹훅이 프론트 없이 루프를 닫는 장치다. 제안 발송에서 라이더 수락까지 사람 손 없이 돈다*
- [ ] 레디스 토큰버킷 — 인스턴스 수와 무관한 전역 초당 한도
- [ ] `fake-latency-ms`, `fail-rate` 를 설정으로 (시나리오 C 재현용)
- [ ] 실패 시 DLQ

### rider-simulator
- [ ] `SM-01` `POST /sim/start` — 라이더 N명 가상 스레드 루프 + 주문 생성기
- [ ] `SM-02` `POST /sim/stop`
- [ ] `SM-03` `GET /sim/status` — 수락/거절/무응답 건수까지
- [ ] `SM-04` `POST /sim/push` 웹훅 — 확률에 따라 수락, 거절, 무응답
      *왜: 프론트가 없으니 이게 유일한 손잡이다. 이 서비스의 품질이 실험의 품질을 결정한다.*
- [ ] 수락 뒤 픽업과 배달 완료까지 이어서 호출 (시간을 압축해서 5초, 30초)
- [ ] 라이더가 실제로 움직이게 만들기 — 안 움직이면 이동거리 필터에 다 걸려 트래픽이 안 생긴다

### settlement-service
- [ ] `SE-01` `delivery.completed` 소비 → `settlement_detail` + `settlement_daily`
      *`INSERT IGNORE` 로 detail 을 먼저 넣고, 영향 행이 1일 때만 daily 를 더한다.
      이 구조가 6단계 리플레이 멱등성의 핵심이다*
- [ ] `SE-02` `GET /api/settlements`

**1단계 완료 조건 (D1, D2):** 시뮬레이터를 켜면 주문이 생기고 제안이 가고 수락하면 배차가 확정되고
배달 완료까지 이어진다. `acceptRate` 를 0으로 두면 attempt 가 5까지 올라가고 `FAILED` 가 된다.

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
- [ ] **배차 상태를 MySQL 로 옮겨본다** — `order_dispatch` 테이블에 `lease_until` 컬럼
      *레디스로 Lua CAS 와 락 TTL 을 직접 겪은 다음에 하는 게 중요하다. SQL `UPDATE ... WHERE` 한 줄로
      같은 걸 하는 걸 보면 DB 가 그동안 뭘 공짜로 주고 있었는지 알게 된다.*
      *비교 지표: 배차 p99, 초당 처리량, 그리고 프로세스를 죽였을 때 좀비 주문 건수*
- [ ] 결과를 `.reference/tech-choice.md` 에 표로 정리 — "왜 이 셋을 같이 쓰는지" 에 대한 답
      (파일은 만들었다. 아웃박스 폴러 vs Debezium 비교가 1번으로 들어가 있다)

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
- [ ] `.reference/flow-scenarios.md` 의 "아직 안 정한 것" 5개에 답을 채운다
- [ ] `.reference/dispatch-internals.md` 의 "구현하기 전에 정할 것" 5개에 답을 채운다
- [ ] `.reference/` 문서 정리 — 기술 선택 근거, 확장 시나리오 결과, 겪은 함정 목록
- [ ] 처음 보는 사람이 `./scripts/start.sh` 하나로 전부 띄울 수 있는지 확인

---

## 나중에 (하고 싶어지면)

- [ ] 카프카 스트림즈로 존별 실시간 수급 집계 (라이더 대비 주문 비율)
- [x] 아웃박스 폴러를 Debezium CDC 로 교체 — 결과는 [`.reference/tech-choice.md`](.reference/tech-choice.md) 1번
- [ ] Redisson 분산락 vs 직접 만든 `SET NX PX` 비교
- [ ] 쿠버네티스로 옮기고 HPA/KEDA 를 제대로
- [ ] 트랜잭셔널 프로듀서(exactly-once) 실험 — 얼마나 느려지는지 재본다
