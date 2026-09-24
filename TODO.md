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
- [x] `OR-03` `POST /api/orders/{orderId}/pickup`
- [x] `OR-04` `POST /api/orders/{orderId}/complete` — `delivery.completed` 발행, 라이더 해제
- [x] `OR-05` `POST /api/orders/{orderId}/cancel` — 진행 중 제안을 `CANCELLED` 로
- [x] `OR-06` 아웃박스 폴러 200ms — `SELECT ... FOR UPDATE SKIP LOCKED`
- [x] `OR-07` `dispatch.assigned` / `dispatch.failed` 소비 → 상태 반영 (조건부 갱신으로 멱등)
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

OR-07 에서 정한 것
- **읽고 바꾸지 않고, `UPDATE ... WHERE status IN (...)` 한 번으로 바꾼다.** `dispatch.assigned` 와 `order.status` 가
  다른 토픽이라 두 스레드가 같은 주문을 동시에 만질 수 있다. 조건을 WHERE 에 넣으면 MySQL 이 행을 잠근 채 판정한다.
  바뀐 행이 있을 때만 timeline 에 한 줄 남긴다. 중복 이벤트마다 적으면 같은 ASSIGNED 가 두 줄 생긴다.
- **ASSIGNED 는 CREATED 에서도 받는다.** DISPATCHING 은 `order.status`, ASSIGNED 는 `dispatch.assigned` 로 와서
  ASSIGNED 가 먼저 도착할 수 있다. 그 뒤에 늦게 온 DISPATCHING 은 CREATED 가 아니라 버려진다. 상태는 뒤로 안 간다.
  CANCELLED 는 어떤 결과로도 안 바뀐다.
- **`order.status` 에서 DISPATCHING 하나만 받는다.** 기능 정의서 OR-07 에는 없는 부분이다. OR-02 조회의 timeline
  예시에 DISPATCHING 이 있는데 order-api 가 그걸 알 길이 이 토픽뿐이다. ASSIGNED 는 attempt 가 실린 `dispatch.assigned` 로
  받고, PICKED_UP 과 DELIVERED 는 order-api 가 내보내는 것이라 버린다. 그래서 `order.status.DLT` 토픽도 새로 만들었다.
  브로커가 토픽 자동 생성을 꺼놔서, 없으면 실패한 레코드를 DLT 로 보내다가 그것마저 실패한다.
- **timeline 시각은 이벤트에 적힌 시각이다.** 받은 시각으로 적으면 컨슈머가 밀렸을 때 8초 만에 수락한 주문이
  "배차까지 3분" 으로 보인다.
- **없는 주문의 결과는 경고만 남기고 버린다.** 새 컨슈머 그룹이 earliest 로 처음부터 읽으면 DB 에 없는 옛 주문이 온다.
  예외로 올리면 3번 재시도하고 DLT 로 가는데, 몇 번 해도 없는 건 없다.
- **이벤트를 `libs/common` 의 레코드로 바꿨다 (`DispatchAssigned`, `DispatchFailed`, `OrderStatusChanged`).**
  발행하는 쪽이 Map 에 필드 이름을 적어 보내고 있었는데, 받는 쪽도 따로 적으면 한쪽만 바꿨을 때 값이 조용히 0 이 된다.
  `DispatchResultListenerTest` 는 진짜 `DispatchEventPublisher` 가 만든 JSON 을 리스너에 먹여서 둘이 맞는지 본다.

OR-07 을 붙이다가 고친 것
- **`DispatchEventPublisher` 가 발행 실패를 삼키고 있었다.** 주석엔 "실패하면 예외가 올라가서 ack 를 안 한다" 고 적혀
  있었는데, `send()` 가 돌려주는 future 를 버려서 비동기 실패가 한 번도 안 올라왔다. 라이더가 수락해서 레디스엔 ACCEPTED 인데
  `dispatch.assigned` 가 조용히 안 나가면 주문은 영원히 DISPATCHING 이다. 이제 브로커 응답을 최대 5초 기다린다.
- [ ] **DE-04 수락은 이걸로도 다 안 막힌다.** 레디스에 ACCEPTED 를 쓴 다음 발행이 실패하면 라이더는 500 을 받고,
      다시 누르면 409 라서 이벤트는 끝내 안 나간다. 지금은 ERROR 가 남는 데까지만 막았다.
      `dispatch-engine` 의 `NO_CANDIDATE` 도 비슷하다 — 보드에 FAILED 를 쓴 뒤 발행이 실패하면 재시도 때 이미 FAILED 라 건너뛴다.
      제대로 하려면 레디스 쪽 아웃박스(발행할 이벤트를 같은 Lua 안에서 리스트에 넣고 따로 내보내기)가 필요하다. 5단계 카오스 때 볼 것.

- **FAILED 주문의 attempt 가 0 으로 보였다.** `dispatch.failed` 에 attempt 가 없어서 OR-07 이 상태만 바꿨다. 다섯 번 제안하고
  실패한 주문과 후보가 아예 없던 주문이 조회에서 똑같이 보여서 "왜 배차가 안 됐냐" 에 답을 못 한다. `DispatchFailed` 에
  attempt 를 싣고 `OrderRepository.fail` 이 같이 쓰게 했다. relay 는 실제로 간 제안 수를, dispatch-engine(후보 없음, 전원 찜 당함)은 0 을 싣는다.
  토픽에 남은 옛 이벤트는 attempt 가 없어서 0 으로 읽힌다(그걸 보는 테스트를 붙였다).
  아무도 안 받는 주문을 실제로 넣어보니 조회에 `FAILED attempt=5`, 이벤트에 `"attempt":5` 가 실렸다.

OR-07 에서 확인한 것 (`./scripts/start.sh` 로 전부 띄우고, 라이더 6명은 location-ingest 로 좌표를 보내고)
- 아무도 안 받은 주문: 진행 중 `DISPATCHING`, 52.3초에 `FAILED`. timeline `CREATED → DISPATCHING(+1.9s) → FAILED(+52.3s)`.
- 1순위 거절, 2순위 수락: `ASSIGNED`, riderId 채워짐, `attempt=2`. timeline `CREATED → DISPATCHING(+2.1s) → ASSIGNED(+4.3s)`.
- 같은 `dispatch.assigned` 를 토픽에 한 번 더 넣었더니 상태, attempt, timeline 줄 수 전부 그대로였고
  `order_status_history` 에도 상태마다 한 줄씩이었다.
- order-api 가 처음 붙으면서 토픽을 처음부터 읽었다. 앞선 테스트에서 실패로 끝난 주문 5건이 FAILED 로 채워지고,
  DB 에 없는 주문 1건은 경고만 남기고 버렸다. ERROR 0건, lag 0.
- MySQL 테스트에서 `assign` 의 상태 조건을 `OR 1 = 1` 로 망가뜨리면, 상태 조건에 기대는 테스트 3개
  (늦은 결과로 PICKED_UP 이 되돌아가는지, 취소된 주문이 되살아나는지, 중복)만 실패하는 걸 확인했다.

OR-03, OR-04 에서 정한 것
- **라이더를 놓아줄 때 "이 주문으로 배달 중일 때만" 푼다 (`finish-delivery.lua`).** 기능 정의서 OR-04 규칙 2번은
  조건 없는 `HSET status IDLE` 이고, 공용 `RiderState` 에도 그렇게 쓰는 `markIdle` 이 준비돼 있었다. 그런데 완료가 재시도로
  두 번 오면 두 번째가 올 때쯤 라이더는 이미 다음 주문의 제안을 들고 있을 수 있다. 거기에 IDLE 을 쓰면 제안을 들고 있는
  사람이 또 후보로 뽑힌다. `markIdle` 은 지웠다. 남겨두면 누군가 또 쓴다.
- **레디스 정리는 DB 커밋이 끝난 다음에 한다.** 반대면 라이더를 풀어준 뒤 커밋이 실패하는 순간이 생긴다. 그러면 주문은
  PICKED_UP 인데 라이더는 새 콜을 받는다. 그리고 **이미 DELIVERED 인 재요청에서도 정리를 한 번 더 한다.** 첫 요청이 커밋 뒤
  레디스에서 실패했으면 두 번째 요청이 마저 끝내야 해서다. 정리가 조건부라 두 번 해도 괜찮다.
- **정리 순서는 라이더 상태 → 찜 → 제안 보드와 후보 목록이다.** 중간에 죽는다면 상태가 풀린 쪽이 낫다. 찜은 수락 12초 뒤면
  이미 저절로 풀려 있고 보드와 후보는 TTL 이 있는데, 상태만 DELIVERING 으로 남으면 그 라이더는 영영 새 콜을 못 받는다.
- **403 을 409 보다 먼저 본다.** 영향 행 수가 0 이면 이유를 가르는데, 라이더가 다르면 상태가 뭐든 403 이다. 남의 주문이
  이미 PICKED_UP 이라고 200 을 주면 다른 라이더 앱에 "픽업 완료" 가 뜬다.
- **`elapsedSeconds` 는 수락부터 완료까지로 정했다.** 기능 정의서엔 뜻이 없다. 접수부터 세면 배차가 늦은 게 라이더 시간에 섞인다.
  수락 시각은 OR-07 이 timeline 에 남긴 ASSIGNED 에서 꺼낸다.
- `riderId` 는 `Long` 에 `@NotNull` 이다. `long` 이면 빼먹었을 때 0 이 들어가서 403 이 나가고, 앱 개발자는 권한 문제를 한참 들여다본다.

OR-03, OR-04 에서 확인한 것 (서비스 8개를 다 띄우고 라이더 6명이 location-ingest 로 좌표를 보내고)
- 수락 → 완료부터 누름 409 → 남이 픽업 403 → 픽업 200 → 픽업 재시도 200 → 완료 200.
  timeline `CREATED → DISPATCHING(+1.2s) → ASSIGNED(+1.8s) → PICKED_UP(+3.7s) → DELIVERED(+3.7s)`.
- 완료 뒤 라이더 `IDLE`, `idleSince` 새로 찍힘, `lock:rider` 와 제안 보드, 후보 목록 전부 0개.
- `delivery.completed` 와 `order.status`(PICKED_UP, DELIVERED)가 아웃박스 → Debezium 을 거쳐 orderId 키로 나갔다.
- **재시도 시나리오를 실제로 만들었다.** 완료한 라이더에게 다음 주문 B 의 제안이 가게 한 뒤 A 완료를 다시 보냈다. 200 이 나갔고,
  라이더는 `OFFERED` 에 B 의 offerId, 찜도 B 것 그대로였다. `delivery.completed` 는 A 에 대해 1건뿐이었다.
- 전체 테스트 196건 통과. `finish-delivery.lua` 의 조건 두 줄을 빼면 재시도 테스트와 "다른 주문 배달 중" 테스트 두 개만 실패한다.

OR-05 에서 정한 것
- **취소도 배차 리스(`lock:dispatch`)를 먼저 잡는다.** 기능 정의서엔 없다. 안 잡으면 "보드를 CANCELLED 로 바꿨는데, 후보를 막 찾은
  dispatch-engine 이 OFFERED 로 덮어쓰며 제안을 보낸다" 가 된다. 라이더가 그걸 받으면 취소된 주문 때문에 DELIVERING 으로 갇힌다.
  offer-relay 가 재제안할 때 리스를 잡는 것과 같은 이유다. 3초 안에 못 잡으면 503 `DISPATCH_BUSY` 를 준다(새 에러 코드).
- **보드는 무조건 CANCELLED 로 바꾸고, 없으면 만든다 (`cancel-offer.lua`).** 아직 제안이 안 나간 주문을 취소했는데 보드가 없으면,
  뒤늦게 order.created 를 읽은 dispatch-engine 이 처음 보는 주문으로 보고 배차를 시작한다. CANCELLED 보드가 있으면 `shouldProceed` 가 멈춘다.
  이후 오는 만료 메시지(재제안)와 수락, 거절은 전부 이 CANCELLED 에 막힌다.
- **수락과 취소가 겹칠 때는 양쪽이 다 확인한다.** 수락(DE-04)은 리스를 안 잡는다. 수락 Lua 가 ACCEPTED 를 쓰고 자바가 라이더를
  DELIVERING 으로 바꾸기까지 1ms 남짓인데, 그 사이 취소가 오면 취소 쪽은 라이더가 아직 DELIVERING 이 아니라 못 푼다. 그래서 수락 쪽이
  DELIVERING 으로 바꾼 뒤 보드를 한 번 더 읽고, CANCELLED 면 스스로 라이더를 풀고 410 을 준다. 어느 순서로 섞이든 둘 중 하나가 푼다.
- 풀어줄 라이더는 두 군데서 찾는다. 제안을 들고 있던 라이더(보드가 OFFERED)는 `release-rider.lua` 로, 배달 중이던 라이더(DB 의 riderId,
  없으면 보드가 ACCEPTED 였을 때의 라이더)는 `finish-delivery.lua` 로. 배달이 10분 넘게 걸리면 보드는 TTL 로 사라져 있어서 DB 를 먼저 본다.
- FAILED 도 409 로 막았다. 기능 정의서는 DELIVERED 만 막는데, 배차를 포기한 주문을 취소할 이유가 없다. 이미 CANCELLED 면 200 이고 레디스 정리를 한 번 더 한다.

OR-05 에서 확인한 것 (`./scripts/start.sh` 로 전부 띄우고, 라이더 6명이 좌표를 보내고)
- 제안 중 취소: 라이더가 곧바로 `IDLE`, 찜 0. 12초 뒤 타이머가 와도 보드 CANCELLED, attempt 1 그대로. 재제안이 안 나갔다.
- 수락 뒤 취소: 배달 중이던 라이더가 `IDLE`, 찜 0. 그 라이더가 픽업을 누르면 409. 배달 끝난 주문 취소는 409. 취소한 뒤 수락은 410.
- **수락과 취소를 동시에 90번 날렸다.** 수락 지연을 0~150ms, 0~40ms 로 섞어서 수락이 먼저인 경우 12번, 취소가 먼저인 경우 58번이 나왔고
  (20번은 지연 없이 전부 수락이 먼저), **취소된 주문을 들고 DELIVERING 으로 남은 라이더는 0명이었다.** 그중 2번은 수락 Lua 와 DELIVERING 사이에
  취소가 끼어든 경우였고, 수락 쪽 재확인이 라이더를 풀었다. 재확인이 없었으면 이 2번은 라이더가 갇혔다.
- [ ] 보드 TTL(10분)보다 order.created 를 늦게 읽으면(컨슈머가 10분 넘게 밀리면) 취소한 주문을 dispatch-engine 이 다시 배차할 수 있다.
      dispatch-engine 이 DB 를 안 보기 때문이다. 5단계 카오스 테스트에서 컨슈머를 오래 멈춰볼 때 같이 볼 것.

### location-ingest
- [x] `LI-01` `POST /api/riders/{riderId}/location` → `rider.location` (key = riderId)
      *왜: 이 서비스는 상태가 하나도 없다. 그래서 확장 시나리오 A 의 기준선이 된다.*
- [x] 프로듀서 튜닝 — `acks=1`, `linger.ms=20`, `batch.size=64KB`, `compression.type=lz4`
      *왜: 위치는 한 점 잃어도 3초 뒤 다음 점이 온다. 신뢰성보다 처리량이 맞는 유일한 토픽.*
- [x] 이동거리 필터 15m — 직전 좌표를 인스턴스 메모리에 캐시
      *왜 레디스를 안 쓰나: 왕복이 생기면 무상태라는 이점이 사라진다. 인스턴스가 늘면 필터가
      느슨해지는데 그건 감수한다 (기능 정의서 LI-01 규칙 2번)*
- [ ] 완료 조건 확인 — 시뮬레이터(SM-01)가 있어야 라이더 100명을 3초 주기로 돌릴 수 있다

LI-01 에서 정한 것
- **안 움직여도 10초에 한 번은 보낸다 (`max-silence`).** 기능 정의서에는 없는 규칙이다.
  15m 필터만 두면 가게 앞에서 콜을 기다리는 라이더가 좌표를 계속 보내는데도 발행이 한 건도 안 되고,
  GI-02 가 heartbeat 가 30초 끊긴 걸 보고 지도에서 빼버린다. 배차받기 제일 좋은 자리에 있는 사람이
  사라지는 거다. 그래서 LI-01 완료 조건의 "세워두면 발행이 멈춘다" 는 "10초에 한 번으로 줄어든다" 로 바뀐다.
  인스턴스가 k 대면 최악의 공백이 10 + 3k 초라서 6대까지만 30초 안에 든다. 7대 이상 띄울 거면 이 값을 줄여야 한다.
- **비교 기준은 직전에 "받은" 좌표가 아니라 직전에 "발행한" 좌표다.** 받은 좌표랑 비교하면 3초에 10m 씩
  가는 라이더는 매번 15m 미만이라 영영 안 나간다.
- **발행에 실패하면 필터 캐시에서 그 라이더를 지운다.** 안 지우면 카프카에는 없는데 필터는 보냈다고 믿어서,
  다음 좌표가 15m 안이면 최대 10초 동안 옛 자리에 멈춰 있게 된다.
- **`delivery.timeout.ms` 를 3초로 줄였다.** 기본값 120초면 브로커가 죽어도 레코드가 버퍼에서 2분 동안 재시도한다.
  실제로 카프카를 내려보니 10초가 지나도 실패 지표가 0 이었다. 줄이고 나서는 2~4초 안에 실패로 잡혔다.
- **KafkaAdmin 에 클러스터 ID 를 미리 넣었다.** `observation-enabled` 가 켜져 있으면 KafkaTemplate 이 스팬 태그용
  클러스터 ID 를 전역 락 안에서 30초 타임아웃으로 물어보고, 실패하면 캐시도 안 한다. 카프카가 죽은 채로 띄워보니
  요청이 전부 10초 넘게 멈췄다(curl 타임아웃). 스레드 덤프를 떠보니 `KafkaTemplate.clusterId()` 락 앞에 줄 서 있었다.
  넣고 나서는 0.5초(`max.block.ms`) 만에 202 가 나간다. **다른 서비스도 같은 설정이라 같은 함정이 있다.**
  거기는 카프카 없이는 어차피 일을 못 해서 급하진 않지만, 5단계 카오스 테스트 때 한 번 볼 것.

LI-01 에서 확인한 것 (카프카, 레디스, location-ingest, geo-indexer 를 띄우고 HTTP 로)
- 첫 좌표, 제자리 재전송, 9m 이동, 20m 이동을 보냈더니 토픽 오프셋이 39 → 41 로 2건만 늘었다.
  레디스에는 마지막 점(20m 이동, lat 37.498275)이 `IDLE` 로 들어갔다. HTTP → 카프카 → 레디스가 이어졌다.
- 한 자리에 서서 3초마다 5번(0~12초) 보냈더니 발행은 2건이었다. 첫 발행 하나랑 12초째 KEEPALIVE 하나.
- 1시간 뒤 `sentAt` 을 보냈더니 `location_ingest_future_sent_at_total` 이 1 올랐다. (레디스의 `lastSeenAt` 은
  처음부터 geo-indexer 가 받은 시각이라 이걸로는 확인이 안 된다. 덮어쓴 값 자체는 `LocationIngestServiceTest` 가 본다.
  지금은 `sentAt` 을 읽는 곳이 없고, 3단계에서 위치 지연을 잴 때 음수가 안 나오게 하려는 것이다)
- 범위 밖 좌표는 `400 INVALID_COORDINATE`, 발행 안 함.
- 응답 시간은 카프카가 정상일 때 3~20ms 였다 (기동 직후 첫 요청만 0.6초).

**geo-indexer 를 재시작하면 좌표가 35초쯤 멈춘다**
- `restart.sh` 로 옛 프로세스를 내리고 새로 띄웠더니 새 컨슈머가 그룹에 들어가는 데 35초가 걸렸다.
  그동안 들어온 좌표는 lag 로 쌓였다가 한꺼번에 처리됐다. 정상 종료였다면 컨슈머가 떠난다고 알리고 나가서
  바로 리밸런싱됐어야 하니, 옛 멤버가 깔끔하게 안 나가서 브로커가 세션 타임아웃(45초)을 기다린 것으로 보인다.
  옛 프로세스는 카프카가 내려가 있던 동안 하룻밤 떠 있던 거라 왜 안 나갔는지는 아직 확인 못 했다.
- 30초 오프라인 정리(GI-02)보다 길다. GI-02 를 붙이고 나면 geo-indexer 를 재시작할 때마다 온라인 라이더가
  한꺼번에 지도에서 빠질 수 있다. 4단계 B-2(리밸런싱 관찰)에서 같이 볼 것.

### geo-indexer
- [x] `GI-01` `rider.location` 소비 → `GEOADD` + `HSET rider:state` + `ZADD riders:heartbeat`
- [x] 배치 안에서 라이더별로 마지막 좌표만 남기고 파이프라인으로 한 번에 쓰기
- [x] `status` 는 조건부로만 갱신 — `OFFERED` / `DELIVERING` 은 절대 안 건드린다
      *안 지키면: 배달 중 라이더가 새 주문 후보로 다시 잡힌다 (흐름 시나리오 10번)*
- [x] `GI-02` 오프라인 정리 10초 주기 — `ZRANGEBYSCORE` 로 대상 찾고 `SET lock:sweep NX` 로 단독 실행
- [x] `GI-03` `GET /api/riders/{riderId}/state` — 디버깅용
      *기능 정의서는 해시와 GEO 여부만 보여주라는데, "지금 후보로 뽑힐 수 있나" 와 못 뽑히는 이유(`blockers`)를 더했다.
      GEO 에 없음, IDLE 이 아님, 좌표가 30초 넘게 끊김, 다른 주문이 찜함 — 배차가 안 될 때 사람이 머릿속으로 맞춰보는 네 가지다.
      없는 라이더도 404 가 아니라 200 에 "rider:state 가 없다" 로 답한다. 없다는 것 자체가 이유라서다.
      실제로 띄워서 보니 좌표를 보내는 라이더는 `candidate=true`, 예전 테스트에서 수락만 시키고 둔 라이더는
      "status 가 DELIVERING 이라서 못 뽑힌다" 가 바로 나왔다.*
- [x] 수동 ack + `auto-offset-reset: latest`
      *왜: 밀린 위치는 쓸모없다. 과거를 따라잡느니 현재부터 보는 게 맞다.*

GI-01 에서 정한 것
- **상태 갱신은 Lua 여야 한다.** `HGET status` 로 읽고 자바에서 판단한 뒤 `HSET` 하면 그 사이에
  dispatch-engine 이 끼어든다. `t=0 오프라인이네 → t=1 배차가 제안을 보냄(OFFERED) →
  t=2 우리가 IDLE 을 씀` 이면, 제안을 들고 있는 라이더가 "한가함" 이 돼서 다른 주문이 또 뽑아간다.
  제안 보드의 펜싱 규칙이나 `release-rider.lua` 와 같은 생각이다.
- **모르는 status 값은 안 건드린다.** 승격 조건을 "없거나 OFFLINE 일 때" 로만 뒀다. 우리가 모르는
  상태를 IDLE 로 덮는 것보다 그냥 두는 쪽이 덜 위험하다 — 후보 검색이 IDLE 만 뽑으니까
  안 뽑히고 끝난다.
- **이건 `libs/common` 으로 안 옮겼다.** `riders:online` 과 `riders:heartbeat` 에 **쓰는** 건
  geo-indexer 뿐이고 dispatch-engine 은 GEOSEARCH 로 읽기만 한다. `OfferSender` 를 옮긴 건
  두 서비스가 같은 로직을 진짜로 돌려서였고 여기는 아니다. 필드 이름이 어긋날 위험은
  `RiderStateFields` 가 이미 막는다.
- **실패해도 재시도하지 않고 ack 한다.** 다른 컨슈머와 정반대라 헷갈릴 만한 부분이다.
  `order.created` 는 한 건을 놓치면 손님 주문이 사라지니 DLT 까지 가며 붙잡지만, 위치는 한 점을
  놓쳐도 3초 뒤 다음 점이 온다. 재시도하면 그동안 파티션이 막혀서 **밀린 좌표가 더 쌓인다.**
  신선도가 전부인 데이터에서 밀리는 건 잃는 것보다 나쁘다. `auto-offset-reset: latest` 와 같은
  결정이다. 대신 조용히 넘어가지 않게 ERROR 로 찍고 `geo_index_dropped_total` 을 올린다.
- **파이프라인 안에서는 EVALSHA 가 아니라 EVAL 이다.** EVALSHA 는 서버에 스크립트가 없으면
  NOSCRIPT 가 오는데, 파이프라인은 결과를 맨 끝에 한꺼번에 받아서 **중간에 알아채고 다시 보낼
  방법이 없다.** 레디스를 재시작하거나 `SCRIPT FLUSH` 가 한 번 돌면 그 배치가 통째로 날아간다.
  스크립트 본문을 매번 보내는 만큼 바이트는 손해지만(라이더 한 명당 1KB 남짓) 이게 맞다.
  스프링의 `RedisTemplate.execute(script, ...)` 도 파이프라인 안에서는 같은 이유로 EVAL 로 떨어진다.
- **좌표 검증을 컨슈머에서 또 한다.** location-ingest 를 못 믿어서가 아니라 토픽에 누가 뭘 넣을지
  몰라서다. 범위 밖 좌표가 `GEOADD` 로 들어가면 레디스가 거절하면서 **파이프라인 전체가 실패한다** —
  좌표 하나 때문에 멀쩡한 라이더 200명이 같이 날아간다. 못 읽는 JSON 도 같은 이유로 건너뛴다.
- `lastSeenAt` 은 **서버가 받은 시각**이지 `sentAt` 이 아니다. GI-02 가 이 값으로 "이 사람 사라졌나"
  를 판단하는데, 그 판단이 라이더 휴대폰 시계에 좌우되면 안 된다. 대신 컨슈머가 밀리면 오래된
  좌표가 방금 것처럼 보이는 단점이 있는데, 그건 컨슈머 랙을 직접 보면 된다(시나리오 B).
- 배치 안 중복 제거는 `sentAt` 비교가 아니라 **리스트 순서**로 한다. 파티션 키가 riderId 라
  한 라이더의 좌표는 같은 파티션에 순서대로 들어오고 컨슈머도 그 순서로 받는다.
  휴대폰 시계보다 카프카가 보장하는 순서를 믿는 게 낫다.

GI-02 에서 정한 것
- **라이더 한 명의 판정과 정리를 Lua 하나로 묶었다 (`sweep-rider.lua`).** 대상을 뽑은 시점과 처리하는 시점
  사이에 새 좌표가 들어올 수 있다. 그걸 그대로 OFFLINE 으로 만들면 3초 뒤 GI-01 이 IDLE 로 되살리긴 하는데
  그때 `idleSince` 가 새로 찍혀서, 20분 기다린 라이더가 대기 보너스를 통째로 잃는다. 그래서 스크립트 안에서
  heartbeat 점수를 다시 보고, 그 사이 새로워졌으면 손을 안 댄다.
- **OFFERED 는 OFFLINE 으로 안 바꾸고 GEO 에서만 뺀다.** 바꿔두면 터널을 빠져나온 라이더 좌표가 1초 뒤에
  들어왔을 때 GI-01 이 "오프라인이던 사람이네" 하고 IDLE 로 올린다. 제안이 살아 있는데 한가한 사람이 된다.
  heartbeat 에는 남겨서, 제안이 만료돼 IDLE 이 되면 다음 주기에 다시 걸리게 했다.
- **IDLE 과 OFFLINE 은 heartbeat 에서도 뺀다.** 안 빼면 퇴근한 라이더가 10초마다 계속 다시 뽑힌다.
  다시 좌표를 보내면 GI-01 이 넣어준다. 해시가 아예 없는 라이더는 status 만 든 해시를 새로 만들지 않는다.
- **대상은 페이지로 나눠 읽고, 구간에 남는 애들(배달 중, 제안 중)만큼 건너뛴다.** 매번 앞에서 500명만 읽으면
  지하 주차장에 들어간 배달 중 라이더 500명이 앞자리를 다 차지했을 때 뒤의 IDLE 라이더가 영영 정리가 안 된다.
  건너뛰는 계산을 일부러 망가뜨려 보니 이걸 보는 테스트가 실패하는 것도 확인했다.
- **락은 안 푼다.** 8초 뒤 저절로 풀리고 다음 주기는 10초 뒤다. 끝나자마자 풀면 주기가 2초 어긋난 다른
  인스턴스가 바로 잡아서 한 주기에 두 번 돈다. 락 값에는 `geo-indexer:8092` 처럼 누가 잡았는지 적는다.
- **첫 실행을 60초 늦췄다 (`initial-delay`).** geo-indexer 를 재시작하니 파티션을 받는 데 35초가 걸렸다.
  그 사이에 스위퍼가 돌면 멀쩡히 좌표를 보내는 라이더가 전부 오프라인이 되고 `idleSince` 까지 날아간다.
  다만 이건 인스턴스 한 대일 때 얘기다. 여러 대면 다른 인스턴스의 스위퍼가 그대로 돌고, 리밸런싱 중에는
  같은 일이 생긴다. 한꺼번에 너무 많이 빠지면 멈추는 안전장치(유레카의 self-preservation 같은 것)는
  4단계 B-2 에서 리밸런싱을 보면서 필요한지 정한다.

GI-02 에서 확인한 것 (레디스, 카프카, geo-indexer 를 띄우고 토픽에 직접 넣어서)
- 라이더 A(IDLE), B(DELIVERING) 좌표를 한 번씩 넣고 멈췄다. A 는 31~36초 사이에 GEO 에서 빠지고 OFFLINE 이 됐다.
  B 는 44초가 지나도 GEO 와 heartbeat 에 그대로였다.
- 기능 정의서 완료 조건은 "30초 안에 사라진다" 인데 실제로는 **30~40초**다. 30초 기준에 10초 주기라 그렇다.
  30초 안에 꼭 빠져야 하면 `offline-after` 를 20초로 줄여야 한다.
- 두 대(8092, 8192)를 띄웠더니 30초 동안 8192 만 3번 돌고 8092 는 3번 다 `lock_held` 로 건너뛰었다.
  8192 가 매 주기 1~2초 먼저 깨어나서 늘 이긴다. 한 주기에 한 번만 돌면 되니 누가 이기든 상관없다.

GI-01 에서 확인한 것 (LI-01 이 아직 없어서 `rider.location` 에 직접 넣고)
- 좌표 6건 → `riders:online` 6명, `riders:heartbeat` 6명, `rider:state` 에 lat/lng/lastSeenAt/
  status=IDLE/idleSince 다 채워짐.
- `GEOSEARCH` 가 거리순으로 나온다 — 21.6m, 196.9m, 405.3m, 613.7m, 822.4m, 1031.0m.
  주문을 넣으니 제일 가까운 21.6m 라이더가 1순위로 뽑혔다. **카프카 → 레디스 → 배차가 이어졌다.**
- **규칙 4번을 실제로 봤다.** 라이더가 수락해서 `DELIVERING` 이 된 뒤 좌표를 3번 더 보냈는데
  status 는 `DELIVERING` 그대로고 lat 만 37.4979 → 37.5069 로 갱신됐다. `currentOrderId` 도 살아 있다.
- **배치 중복 제거가 도는 걸 지표로 확인했다.** 라이더 2명의 좌표 30건을 한꺼번에 던졌더니
  `geo_index_records_total` 은 +30, `geo_index_riders_total` 은 **+2**. 레디스 쓰기 28번을 아꼈다.
  남은 좌표도 각각 마지막 것(37.5007, 37.5008)이었다.

GI-01 에서 남은 것
- `geo_index_records_total` 과 `geo_index_riders_total` 의 차이가 곧 "배치 안 중복" 인데,
  이게 커지면 컨슈머가 밀리고 있다는 신호다. 정상이면 라이더가 3초마다 한 점을 보내니 한 배치에
  같은 사람이 두 번 들어올 일이 거의 없다. 시나리오 B 에서 이 비율을 그래프로 본다.
- 라이더가 오프라인이 돼도 `riders:online` 에서 안 빠진다. GI-02 몫이다.
- `sentAt` 을 아직 아무 데도 안 쓴다. 3단계에서 "휴대폰이 보낸 시각 → 인덱스에 반영된 시각"
  지연을 재는 데 쓸 값이다.

### dispatch-engine
- [x] `DE-01` `order.created` 소비 — 리스 획득 → 좀비 판정 → 후보 검색 → 제안
- [x] 좀비 판정 표 그대로 구현 (기능 정의서 DE-01 규칙 2번)
      *`EXISTS` 만 보면 "해시는 있는데 타이머가 없는" 주문이 영구 방치된다*
- [x] `DE-02` `GEOSEARCH ... ASC COUNT 30` → 파이프라인으로 상태 조회 → 점수순 10명
      *`COUNT` 와 `ASC` 를 같이 줘야 조기 종료된다. 안 주면 반경 안 500명을 다 계산한다*
- [x] `DE-03` 제안 발송 — `lock:rider` 획득, 새 `offerId` 발급, 한 번만 발행
- [x] publisher confirm — 실패하면 되돌리고 다음 후보로 (카프카 ack 는 예외로 막는다)
- [x] `DE-04` `POST /api/offers/{offerId}/accept` — Lua CAS, 반환값 4가지를 HTTP 응답으로
      *`lock:rider` 는 안 푼다. 배달 완료(OR-04)까지 들고 있고, 12초 TTL 이 지난 뒤부터는
      `status = DELIVERING` 이 대신 지킨다*
- [x] `dispatch:offer:by-id:{offerId}` 역인덱스를 새로 만들었다 (아래 참고)
- [x] `DE-05` `POST /api/offers/{offerId}/reject` — `dispatch.dlx` 에 직접 발행해 즉시 다음 후보로
- [x] `DE-06` `GET /api/dispatch/{orderId}` — 레디스 상태 덤프
- [x] Lua 스크립트 — 락 해제, 제안 응답(수락·거절 공용), 제안 만료(RE-02),
      후보 목록 저장, 라이더 놓아주기. 3종이 아니라 5종이 됐다 (아래 참고)

### offer-relay
- [x] `RE-01` 래빗엠큐 토폴로지 선언 — `libs/common` 으로 옮겼다 (아래 참고)
      *왜: 이게 래빗엠큐를 쓰는 이유 전부다. "특정 한 명에게, 10초 안에, 안 받으면 다음 사람" 을
      카프카로는 못 만든다.*
      *주의: 타이머 큐에 리스너를 붙이면 TTL 이 흐를 틈이 없어서 재제안이 영원히 안 돈다.*
- [x] `RE-02` 만료 제안 처리 — 규칙 8단계를 순서대로
- [x] **펜싱 규칙** — 메시지의 `offerId` 가 레디스의 현재 `offerId` 와 다르면 버린다
      *안 지키면: 거절로 이미 다음 후보에게 넘어갔는데 옛 타이머가 그걸 또 끊는다 (기능 정의서 3.9)*
- [x] 직전 라이더 `lock:rider` 해제 + `status` 를 `IDLE` 로
      *빼먹으면 그 라이더가 12초 동안 다른 주문의 후보가 못 된다*
- [x] `max-attempts` 소진 시 `dispatch.failed` 발행
- [x] 만료 큐에도 DLX — 재제안이 3회 재시도로도 실패하면 `dispatch.offer.expired.dlq` 로
      *그냥 버리면 그 주문은 재제안을 영영 못 받는다. 보드엔 EXPIRED 가 남고 타이머는 이미 없다*
- [ ] `RE-03` 좀비 스위퍼 — 기능 정의서대로 2단계로 미룬다

### notification-worker
- [x] `NW-01` `dispatch.offer.notify` 소비 → `notify.push` 에 priority 9 로 투입
- [x] `NW-02` `notify.push` 소비 → 시뮬레이터 웹훅으로 POST (가짜 푸시)
      *이 웹훅이 프론트 없이 루프를 닫는 장치다. 제안 발송에서 라이더 수락까지 사람 손 없이 돈다*
- [x] 레디스 토큰버킷 — 인스턴스 수와 무관한 전역 초당 한도
- [x] `fake-latency-ms`, `fail-rate` 를 설정으로 (시나리오 C 재현용)
- [x] 실패 시 DLQ
- [x] `NW-03` `POST /api/notify/marketing` — priority 1 대량 투입 (우선순위가 실제로 도는지 보려고 같이 붙였다)

NW 에서 정한 것
- **`notify.x`, `notify.push`, DLQ 선언이 아무 데도 없었다.** 상수만 있었다. 그 상태로 NW-01 이 보내면 받을 큐가 없어서
  메시지가 에러 없이 사라진다. RE-01 때처럼 `libs/common` 의 `RabbitTopologyConfig` 에 넣었다.
  `notify.push` 는 `x-max-priority=10` 이라 이 줄 없이 한 번이라도 큐가 만들어졌으면 PRECONDITION_FAILED 로 기동이 막힌다.
- **남은 시간은 보낼 때 다시 잰다.** 기능 정의서는 "남은 시간 10초" 인데, 큐에서 4초 기다렸으면 라이더한테 남은 건 6초다.
  메시지에는 `offeredAt` 을 싣고 NW-02 가 보내는 순간에 계산한다. 1초도 안 남았으면 안 보내고 `push_expired_total` 만 올린다.
  라이더가 눌러도 410 이고 외부 API 한도만 하나 쓴다.
- **토큰 버킷은 레디스 `TIME` 으로 잰다.** 인스턴스마다 시계가 다른데 각자 자기 시계로 채우면, 200ms 앞선 인스턴스가 끼어들 때마다
  토큰이 40개(초당 200 기준)씩 더 생긴다. 재시도할 때마다 토큰을 새로 받는다 — 외부 API 입장에선 재시도도 한 번의 요청이다.
- **실패는 프로세스 안에서 3번까지 다시 보내고, 그래도 안 되면 `requeue=false` 로 DLQ.** `requeue=true` 면 같은 메시지가 곧바로 다시 들어와서 끝없이 돈다.
  5% 실패에 세 번 연속 실패는 0.0125% 라 만 건에 한 건쯤 DLQ 로 간다.
- 마케팅은 웹훅으로 안 보낸다. 시뮬레이터가 받을 게 없고, 지연과 토큰은 똑같이 먹어서 우선순위 실험에는 충분하다.
- `storeName` 은 null 이다. 주문에 가게 이름이 없고 storeId 만 있다. 예상 수익도 아직 안 싣는다.

NW 에서 확인한 것 (서비스 8개를 다 띄우고, 웹훅은 SM-04 가 없어서 받은 걸 파일에 적는 가짜 서버로)
- 주문을 넣으면 제안 1.3초 뒤 푸시가 나가고 웹훅 본문에 `expiresInSec: 9` 가 실렸다. 아무도 안 받으니 10초 간격으로 다음 라이더 푸시가 이어졌다.
- 처음엔 웹훅 본문이 비어 보였다. 스프링 `SimpleClientHttpRequestFactory` 가 본문을 `Transfer-Encoding: chunked` 로 보내는데
  가짜 서버가 `Content-Length` 만 봐서였다. 워커가 아니라 가짜 서버 문제였다 (스프링인 시뮬레이터는 chunked 를 읽는다).
- **prefetch 250 은 우선순위를 망가뜨린다.** 마케팅 1만 건을 넣고 곧바로 주문을 넣었더니 15초가 지나도 제안 푸시가 안 왔다.
  컨슈머 6개가 250개씩 1500건을 미리 쥐고 있어서, priority 9 인 제안도 누군가의 버퍼 249건 뒤에 붙는다. 그 주문의 제안 5건이 전부
  34초 뒤에야 꺼내졌고(남은 시간 -24초) 만료로 걸러졌다. 우선순위는 큐 안에서만 앞지르고, 이미 컨슈머에게 넘어간 건 못 앞지른다.
- **prefetch 20 으로 띄우니** 같은 실험에서 제안 푸시가 4.5초(주문 접수부터, 배차 2초 포함) 만에 닿았고 `expiresInSec: 7`, 그때까지 나간 마케팅은 223건이었다.
  제안이 9700여 건을 앞지른 거다. 기본값 250 은 기능 정의서가 시나리오 C 에서 보라고 둔 값이라 그대로 두고 `PUSH_PREFETCH` 로 열어뒀다.
- **한도를 초당 20 으로 낮추고 워커 2대를 띄웠더니** 15초 동안 150 + 136 건, 합쳐서 초당 19.0 건이었다. 나눠 가졌다면 40 이 나간다.
- [ ] **한도보다 많이 들어오면 큐에서 기다리는 게 아니라 DLQ 로 빠진다.** 위 실험에서 15초 동안 보낸 건 286 건인데 DLQ 로 간 게 221 건이었다.
      컨슈머 12개가 토큰 하나를 두고 다투니 300ms 안에 못 받는 게 많다. 기능 정의서 규칙대로지만 제안까지 이렇게 버려진다.
      시나리오 C-1(prefetch 와 백오프로 푸는 것)에서 같이 볼 것.

### rider-simulator
- [x] `SM-01` `POST /sim/start` — 라이더 N명 가상 스레드 루프 + 주문 생성기
- [x] `SM-02` `POST /sim/stop`
- [x] `SM-03` `GET /sim/status` — 수락/거절/무응답 건수까지
- [x] `SM-04` `POST /sim/push` 웹훅 — 확률에 따라 수락, 거절, 무응답
      *왜: 프론트가 없으니 이게 유일한 손잡이다. 이 서비스의 품질이 실험의 품질을 결정한다.*
- [x] 수락 뒤 픽업과 배달 완료까지 이어서 호출 (시간을 압축해서 5초, 30초)
- [x] 라이더가 실제로 움직이게 만들기 — 안 움직이면 이동거리 필터에 다 걸려 트래픽이 안 생긴다
- [ ] `SM-05` 부하 프로파일 (`steady`, `lunch-peak`, `rider-drain`, `burst`) — 4단계 확장 실험 들어가기 전에

SM 에서 정한 것
- **웹훅은 판단만 하고 곧바로 200 을 준다.** 수락 대기(2초)를 웹훅 안에서 하면 notification-worker 의 읽기 타임아웃(2초)에 걸려
  발송 실패로 보고 같은 제안을 또 보낸다. 수락, 픽업, 완료는 가상 스레드에서 이어서 부른다.
- **정지하면 새 주문과 좌표만 멈추고, 이미 수락한 배달은 완료까지 마저 부른다.** 끊어버리면 그 라이더는 DELIVERING 으로 영영 남는다
  (GI-02 도 배달 중인 라이더는 안 치운다). 정지한 뒤에 온 제안은 받지 않는다. 멈춘 뒤 30~40초 동안은 레디스에서 아직 IDLE 이라 제안이 오는데,
  받으면 정지한 판에서 새 배달이 시작된다.
- **라이더는 방향을 30도까지만 틀면서 걷는다.** 무작위 방향으로 걸어도 15m 필터는 넘지만(필터는 직전 발행 점과 비교한다)
  10분에 240m 쯤밖에 못 벗어나서 라이더 분포가 처음 뿌린 그대로 굳는다. 30도씩 틀면 1.5km 쯤 간다.
- 목적지는 가게에서 0.5~3km 안이다. 판 전체에서 뽑으면 8km 판에서 16km 짜리 주문이 섞인다.
- 실패한 호출은 예외로 끊지 않고 `ApiResponse.code` 로 이유별로 센다(`accept:OFFER_EXPIRED` 같은 것). 409 한 번에 루프가 서면 실험이 멈춘다.
- 상태 응답(SM-03)에 `pickedUp`, `delivered`, `unknownRider`, `failures`, `locationsSent` 를 더했다. `accepted` 는 수락하기로 정한 수라서
  끝까지 갔는지는 `delivered` 로 봐야 한다.

SM 에서 확인한 것 — 1단계 완료 조건 (`./scripts/start.sh` 로 전부 띄우고)
- **D1: 라이더 100명, 주문 초당 1건, 반경 2km, 90초.** 주문 91건, 제안 165번, 수락 85 · 거절 27 · 무응답 53(정지 뒤 12건 포함).
  **수락한 85건이 전부 픽업과 배달 완료까지 갔다.** 정지 40초 뒤 `delivered` 가 64 → 85 로 따라붙었다. 실패한 호출 0건.
  DB 로는 DELIVERED 85, FAILED 6(정지 뒤 무응답으로 다섯 번 돈 것), 평균 attempt 1.59.
- **D2: `acceptRate` 0 으로 주문 15건.** 제안 52번이 전부 무응답이었고 15건 모두 attempt 5 에서 `MAX_ATTEMPTS` 로 FAILED 가 됐다.
- 여러 판을 돌린 뒤 시뮬레이션 주문 209건이 전부 DELIVERED(169) 나 FAILED(40) 로 끝났다. 중간 상태로 멈춘 건 없다.
- LI-01 완료 조건도 여기서 봤다. 라이더 100명 3초 주기면 location-ingest 가 초당 32.5~33.6 건을 받는다(기능 정의서 "30건 안팎").
- [ ] **첫 판에서 시뮬레이터의 초당 좌표 수가 91 로 나왔다.** 같은 때 location-ingest 는 초당 33 을 받았으니 트래픽은 정상이고 표시만 틀렸다.
      같은 조건으로 세 번 더 돌렸을 때는 35 로 맞았고 원인은 못 찾았다. 누적 `locationsSent` 를 넣어서 ingest 누적과 맞춰볼 수 있게 했다(재시작 뒤 249 = 249).
- [ ] **FAILED 주문은 DB 의 attempt 가 0 이다.** OR-07 이 FAILED 를 반영할 때 attempt 를 안 채우고, `dispatch.failed` 에도 attempt 가 없다.
      다섯 번 제안했는데 조회하면 0 으로 보인다. `DispatchFailed` 에 attempt 를 싣고 `markFailed` 가 채우게 하면 된다.
- 개발용 레디스에 DELIVERING 으로 남은 라이더가 셋 있다(`900000000000001`, `…3002`, `…2002`). GI-01, RE-02, GI-02 를 손으로 확인할 때
  수락만 시키고 완료를 안 한 테스트 라이더들이다. 시뮬레이터 라이더(TSID 아이디)는 하나도 안 남았다.

### settlement-service
- [x] `SE-01` `delivery.completed` 소비 → `settlement_detail` + `settlement_daily`
      *`INSERT IGNORE` 로 detail 을 먼저 넣고, 영향 행이 1일 때만 daily 를 더한다.
      이 구조가 6단계 리플레이 멱등성의 핵심이다*
- [x] `SE-02` `GET /api/settlements`

SE 에서 정한 것
- **수수료 공식을 정했다.** 기능 정의서엔 `fee_krw` 컬럼만 있다. 기본 3,000원에 1km 를 넘으면 500m 마다 500원(올림).
  2.3km 면 4,500원이다. 행마다 `fee_policy`(`v1-base3000`)를 남긴다. 공식을 바꾸고 리플레이할 때 `INSERT IGNORE` 가
  옛 행을 건너뛰어서 금액이 안 바뀌는데(SE-03), 그때 옛 공식 행이 몇 개 남았는지를 `GROUP BY fee_policy` 한 번으로 본다.
- **정산 날짜는 한국 날짜다.** `completedAt` 은 UTC 라 그대로 자르면 새벽 1시(KST) 배달이 전날(UTC 16시)로 간다.
- **`INSERT IGNORE` 를 기능 정의서대로 쓰되, 넣기 전에 값을 본다.** `ON DUPLICATE KEY UPDATE` 는 MySQL 드라이버 기본 설정에서
  중복이어도 영향 행 수를 1 로 돌려줘서 "0 이면 이미 집계한 주문" 판정이 통째로 깨진다. 대신 IGNORE 는 PK 중복만이 아니라
  null 이나 값 넘침도 경고로 바꾸고 기본값을 넣는다. 그래서 틀린 이벤트는 넣기 전에 `BusinessException` 으로 DLT 에 보낸다.
- daily 합계는 `VALUES()` 대신 `AS incoming` 문법으로 더한다. `VALUES()` 는 MySQL 8.0.20 부터 쓰지 말라고 나온다.
- **`stop.sh` 가 서비스 이름을 받게 했다.** 기능 정의서 SE-03 절차의 1번이 `./scripts/stop.sh settlement-service` 인데
  스크립트가 인자를 안 봐서, 치면 서비스 8개가 다 내려갔다. 이제 이름을 주면 그 서비스(추가 인스턴스 포함)만 내린다.

SE 에서 확인한 것 — SE-01 완료 조건, 1단계 D1 의 마지막 조각 (`./scripts/start.sh` 로 전부 띄우고)
- settlement 가 처음 붙으면서 토픽에 쌓여 있던 배달 170건을 집계했다. detail 170행, daily 건수 합 170, 수수료 합 양쪽 729,000원.
- 시뮬레이터(#15 브랜치에서 jar 만 빌드해서)로 90초 돌리니 배달 162건이 실시간으로 들어와 332건이 됐다.
  **DB 의 DELIVERED 주문 332건 = settlement_detail 332행.** D1 의 "settlement_daily 에 행이 쌓인다" 까지 사람 손 없이 이어졌다.
- **리플레이:** daily 250행을 떠두고, SE-03 절차대로 settlement 만 멈추고 `--to-earliest` 로 리셋한 뒤 다시 띄웠다.
  332건이 전부 다시 흘러와 `settlement_duplicate_total` 332, `settlement_recorded_total` 0. **daily 250행의 md5 가 전후 똑같았다.** 한 원도 안 달라졌다.
- 단위 테스트에서 `if (inserted == 0) return false` 를 빼면 "같은 배달 두 번" 테스트가 실패하는 것도 확인했다. 기능 정의서가 말한 "정산이 두 배가 된다" 가 그 자리다.

> DE-01~03 을 만들면서 정리한 문서 두 개
> - [`.reference/dispatch-implementation.md`](.reference/dispatch-implementation.md) — 만들어보니 이랬다 (설계와 달라진 부분, 실측값)
> - [`.reference/rabbitmq-timer.md`](.reference/rabbitmq-timer.md) — 10초 타이머가 실제로 어떻게 도는지

DE-01 에서 확인한 것
- 라이더 4명을 레디스에 직접 심고(geo-indexer 가 아직 없어서) 주문 하나를 넣었더니
  `dispatch.offer.timer` 와 `dispatch.offer.notify` 에 **각각 1건씩** 들어갔다.
  발행은 한 번인데 브로커가 두 큐로 복제해준 것이다. 완료 조건 그대로다.
- 10초 뒤 타이머 큐가 0이 되고 `dispatch.offer.expired` 에 1건이 떨어졌다.
  TTL + DLX 가 라우팅 키를 `offer.expired` 로 바꿔 옮겨준다. **이게 래빗엠큐를 쓰는 이유 전부다.**
- 후보 점수도 의도대로 나왔다. 가장 가까운 R1 이 1순위, 남은 목록은 R2 → R3 순서.
  배달 중인 R4 는 `status` 필터에 걸려 아예 안 뽑혔다. GEO 는 좌표만 알기 때문에
  상태를 붙이는 단계가 없으면 배달 중인 사람에게 제안이 간다.
- `order.status` 에 `DISPATCHING` 이 갔고, 배차가 끝난 뒤 `lock:dispatch` 는 0개였다.

**과거 주문 221건이 한꺼번에 다시 배차됐다**
- dispatch-engine 컨슈머 그룹이 처음 뜨는데 `auto-offset-reset: earliest` 라서,
  그동안 테스트로 쌓아둔 `order.created` 221건을 전부 다시 읽고 배차를 시도했다.
  라이더가 없던 시점 것들이라 전부 `dispatch.failed` 가 됐다.
- 설정대로 동작한 것이라 버그는 아니다. 다만 **새 컨슈머 그룹을 붙일 때는 토픽에 쌓인
  과거분이 통째로 재생된다**는 걸 눈으로 봤다. 6단계에서 정산을 오프셋 리셋으로 재계산할 때
  똑같은 일이 일어날 텐데, 그때는 그게 목적이다.

DE-01 에서 정한 것
- **래빗엠큐 토폴로지 선언을 `libs/common` 으로 옮겼다.** 기능 정의서는 offer-relay 가
  선언한다고 돼 있는데, 그러면 dispatch-engine 이 먼저 뜨면 큐가 없어서 메시지가 조용히
  버려진다(에러도 안 난다). 자동설정으로 amqp 쓰는 서비스가 다 같이 선언하게 했다.
  스프링 AMQP 선언은 멱등이라 겹쳐도 괜찮다.
- 래빗엠큐 메시지를 JSON 으로 보낸다. 기본 컨버터(자바 직렬화)는 record 를 못 보내기도 하고,
  관리 UI 에서 메시지를 열어봐도 못 알아본다. 프론트가 없어서 큐를 눈으로 볼 일이 많다.
- **`rider:state` 에 `idleSince` 필드를 새로 정했다.** DE-02 의 대기 보너스가 이걸 쓰는데
  출처가 문서에 안 적혀 있었다. `status` 를 `IDLE` 로 바꾸는 쪽(GI-01, DE-05, OR-04)이
  이 값도 같이 써야 한다. 안 써도 에러는 안 나고 보너스만 0이 돼서 더 눈에 안 띈다.
- 리스와 제안 보드는 막는 게 다르다. 리스는 "지금 동시에" 를 막고, 보드는 "전에 처리했는지" 를
  본다. 리스는 15초 뒤 사라져서 20초 뒤에 온 중복 메시지를 못 막기 때문에 둘 다 필요하다.

DE-04 에서 정한 것
- **`dispatch:offer:by-id:{offerId}` 역인덱스를 새로 만들었다.** 제안 보드는 `orderId` 로 여는데
  라이더가 수락할 때 들고 오는 건 `offerId` 하나뿐이라(푸시에 실린 게 그거고 URL 도 그렇다)
  `orderId` 를 되찾을 자리가 없었다. `lock:rider:{riderId}` 값이 `orderId` 라 그걸 대신 볼까
  했는데, 하필 제일 중요한 경우에 틀린 답이 나온다 — 찜은 12초 뒤 풀리고 그 사이 다른 주문이
  같은 라이더를 잡으면 값이 새 `orderId` 로 바뀐다. 만료된 제안을 뒤늦게 수락한 라이더에게
  "만료됐어요" 대신 엉뚱한 주문의 판정이 돌아간다.
- **Lua 에서 `offerId` 검사를 `riderId` 검사보다 먼저 한다.** 기능 정의서의 예시 스크립트는
  상태와 `riderId` 만 보는데, 그러면 1순위가 만료돼 2순위로 넘어간 뒤 1순위가 뒤늦게 수락할 때
  보드의 `riderId` 가 2순위라서 403 `NOT_YOUR_OFFER` 가 나간다. 라이더 입장에서는 분명히
  자기한테 온 제안이었으니 틀린 말이고, 맞는 말은 410 `OFFER_EXPIRED` 다.
- 레디스에 직접 돌려서 반환값 6가지 경우를 확인했다 — 정상 수락 `1`, 두 번 수락 `-1`,
  만료 뒤 수락 `-2`, 다음 후보로 넘어간 뒤 수락 `-2`, 남의 제안 `0`, 보드 없음 `-2`.

DE-05 · RE-02 를 만들면서 — 공용 코드를 `libs/common` 으로 옮겼다
- 재제안은 첫 배차와 하는 일이 글자 하나까지 같다. 후보를 꺼내고, 라이더를 찜하고, 새 `offerId`
  로 발행한다. offer-relay 에 똑같이 다시 짜면 필드 이름 하나만 어긋나도 **에러 없이** 조용히
  잘못 돈다 — 모든 제안이 만료로 보이거나 펜싱이 통째로 안 걸린다. 그래서
  `OfferBoard`, `OfferSender`, `CandidateList`, `RiderLock`, `RiderState`, `DispatchLease`,
  `DispatchEventPublisher` 를 `common.dispatch` 로 옮기고 자동설정으로 올렸다.
- **락 TTL 세 개는 서비스 yml 에서 빼고 `OfferProperties` 기본값으로 박았다.** 양쪽 yml 에
  따로 두면 한쪽만 고치는 날이 온다. dispatch-engine 은 12초 찜하는데 offer-relay 는 8초로
  찜하면, 재제안받은 라이더의 찜이 제안보다 2초 먼저 풀려서 그 사이 다른 주문이 채간다.
  라이더 화면에 제안이 두 개 뜬다.
- **offer-relay 도 `lock:dispatch` 리스를 잡는다.** 기능 정의서에 없는데 필요하다.
  dispatch-engine 의 좀비 재배차(30초)와 relay 의 재제안이 겹치면 후보를 두 명 꺼내서
  제안이 두 개 나간다. 진 쪽 라이더는 "이미 다른 분이 받았어요" 를 받는데 찜은 12초 안 풀려서
  그동안 다른 주문의 후보도 못 된다.
- **만료 판정도 Lua 여야 한다.** 리스만으로는 못 막는다. 라이더의 수락은 HTTP 로 들어오고
  리스를 안 잡기 때문이다(잡을 이유가 없다. Lua CAS 로 충분하니까). 자바에서 읽고 판단하면
  `t=0 relay 가 OFFERED 를 읽음 → t=1 라이더가 수락 → t=2 relay 가 2순위에게 재제안` 이 난다.
  결국 막는 장치가 셋인데 **각각 막는 상대가 다르다** — 리스는 다른 인스턴스를, Lua 는 수락을,
  펜싱은 시간을 거슬러 도착한 메시지를 막는다.
- 수락과 거절을 Lua 하나(`respond-offer.lua`)로 합쳤다. 판정 절차가 같고 마지막에 쓰는 상태만
  다르다. 나눠두면 "offerId 를 riderId 보다 먼저 본다" 같은 규칙을 한쪽에만 고치게 된다.
- `acceptedAt` 을 `respondedAt` 으로 바꿨다. 수락 전용으로 두면 거절까지 걸린 시간을 잴 자리가
  없다. 어느 쪽이었는지는 `state` 를 같이 보면 된다.

읽다가 고친 것 네 개 (전부 조용히 잘못되는 종류였다)
- **`publishFailed` 만 `Instant.now()` 를 쓰고 있었다.** OR-02 에서 잡았던 마이크로초 문제가
  여기서 되살아나 있었다. `Times.now()` 로 바꿨다.
- **발행 실패 때 `attempt` 를 올리고 있었다.** 브로커가 안 받았으면 라이더는 제안을 못 본
  거라 올리면 안 된다. 후보 10명 중 셋이 발행에 실패하고 넷째가 성공하면 `attempt` 가 4로
  적히는데 실제로 간 제안은 1건이다. 두 번 더 만료되면 후보가 여섯 명 남았는데도
  `max-attempts` 5에 걸려 배차 실패로 끝난다.
- **라이더 찜 해제가 `GET` 후 `DEL` 이었다.** 찜은 12초에 저절로 풀린다. GET 으로 "내 주문이네"
  를 본 직후 TTL 이 지나고 그 라이더가 다른 주문에 찜당하면, 이어지는 DEL 이 **남의 찜을 지운다.**
  `release-rider.lua` 로 묶었다.
- **라이더 상태에도 펜싱이 필요했다.** 찜 해제만 조건부로 하고 `status` 를 IDLE 로 쓰는 건
  조건이 없었다. `t=3.00 거절 → t=3.01 2번 주문이 이 라이더에게 제안 → t=3.02 1번의 만료
  메시지 도착` 이면, 2번 제안을 들고 있는 라이더가 "한가함" 이 돼서 3번 주문이 또 뽑아간다.
  `rider:state` 에 `offerId` 필드를 새로 두고 "내가 보낸 그 제안일 때만" 놓아주게 했다.

덤으로 줄인 것
- 후보 목록 저장이 `DEL`+`RPUSH`+`EXPIRE` 3왕복이었다. `save-candidates.lua` 한 번으로 묶었다.
  왕복도 왕복이지만 **RPUSH 와 EXPIRE 사이에 죽으면 TTL 없는 키가 영원히 남는 게** 더 문제였다.
  배차 상태를 전부 "TTL 이 알아서 치워준다" 로 설계해놨는데 그 전제가 깨지는 자리다.

기동시켜보고 나서야 잡은 것 두 개 (단위 테스트로는 절대 안 잡힌다)
- **location-ingest 가 못 떴다.** `NoClassDefFoundError: ...RedisScript`.
  안쪽 설정 클래스에 `@ConditionalOnClass` 를 걸어놨는데도 그랬다. 스프링은 자동설정 클래스를
  **먼저 로딩하고** 안의 조건을 따지는데, 바깥 클래스가 Lua 스크립트를 static 필드로 들고
  있었다. `@ConditionalOnClass` 는 **그게 붙은 클래스만** 지켜준다. 문을 잠갔는데 벽이 유리인
  셈이다. 선택적 의존성을 건드리는 걸 전부 안쪽으로 내리고, 클래스패스를 가려서 돌려보는
  회귀 테스트(`FilteredClassLoader`)를 붙였다.
- **offer-relay 에 publisher confirm 설정이 없었다.** dispatch-engine yml 에만 있었는데
  `OfferSender` 를 공용으로 옮기면서 그 전제가 같이 안 따라왔다. 증상이 고약했다 — 확인
  future 가 영영 안 끝나서 후보 한 명당 5초 타임아웃이 나고 실패로 판정해 다음 후보로 넘어간다.
  **라이더 6명이 25초 만에 전부 타버리고 배차가 실패했다.** 로그엔 "제안 발행 확인 실패" 만
  찍혀서 브로커 문제처럼 보인다. 설정을 넣고, 확인이 꺼져 있으면 5초 기다리지 말고 곧바로
  "이 서비스 yml 에 publisher-confirm-type 을 넣어라" 로 터지게 했다.

**래빗엠큐 큐는 인자를 나중에 못 바꾼다 — 그리고 앱은 멀쩡히 뜬다**
- 만료 큐에 DLX 를 새로 붙였더니 브로커가 거절했다.
  `PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange' for queue 'dispatch.offer.expired'`
- 그런데 기동 스크립트는 "offer-relay is up" 을 찍는다. 선언에 실패한 채널만 닫히고 그 큐에
  리스너가 안 붙을 뿐이라, 겉보기엔 다 정상인데 만료 제안이 한 건도 처리되지 않는다.
  Debezium 커넥터가 RUNNING 인데 이벤트가 안 나가던 것과 똑같은 모양이다.
  **떴다고 일이 되고 있는 게 아니다.**
- 고치는 법은 큐를 지우고 다시 띄우는 것뿐이다.
  `docker exec delivery-rabbitmq rabbitmqctl delete_queue dispatch.offer.expired`
  확인은 `rabbitmqctl list_queues name messages consumers` 로 한다. 만료 큐의 consumers 가
  0이면 리스너가 안 붙은 것이다.

RE-02 에서 확인한 것 (geo-indexer 가 없어서 라이더 6명을 레디스에 직접 심고)
- **아무도 안 받았을 때:** attempt 가 1→2→3→4→5 로 올라가고 52초에 `FAILED`.
  재제안 간격이 전부 10.03초였다. 후보가 한 명 남은 채로 끝났다 — 소진이 아니라
  `max-attempts` 로 끝난 게 맞다. `dispatch.failed` 에 `MAX_ATTEMPTS` 가 나갔고,
  라이더 6명 모두 `IDLE` 로 돌아오고 `lock:rider` 가 전부 비었다.
- **거절했을 때:** 거절 22:28:36.895 → 재제안 22:28:36.943. **48ms** 만에 2순위로 넘어간다.
  10초를 안 기다린다.
- **펜싱이 실제로 걸리는 걸 봤다.** 위 거절에서 1순위 제안은 22:28:34.595 에 나갔으니 원래
  타이머는 22:28:44.6 쯤 도착한다. 그 시각에 재제안 로그가 **없다.** 다음 재제안은 46.967 로,
  2순위 제안(36.943)으로부터 정확히 10.02초 뒤다. 옛 타이머가 살아 있는 제안을 안 끊었다.
- **수락이 만료를 이긴다.** 수락한 뒤 13초를 기다려 타이머가 도착하게 뒀는데 `state` 가
  `ACCEPTED` 그대로였다.
- 수락/거절 응답 코드 네 가지 전부 확인 — 수락 200, 두 번 수락 409, **수락한 걸 거절 409**,
  남의 제안 403.
- 만료 DLQ 0건, 전 서비스 ERROR 0건. 알림 큐에 17건이 쌓여 있는데 이건 NW-01 이 아직 없어서다.

RE-02 를 main(GI-01, LI-01, GI-02 가 들어간 뒤) 위로 옮기고 다시 확인한 것
- 이번엔 라이더를 레디스에 심지 않았다. 6명이 location-ingest 로 3초마다 좌표를 보내게 하고
  `./scripts/start.sh` 로 서비스 8개를 다 띄웠다. HTTP 좌표 → 카프카 → 레디스 → 배차 → 래빗엠큐 타이머 →
  재제안이 처음으로 끝까지 이어졌다.
- 아무도 안 받으면 가까운 순서로 라이더 1 → 5 에게 10초 간격으로 가고 52초에 `FAILED`, `dispatch.failed` 에
  `MAX_ATTEMPTS`. 라이더 6명 모두 `IDLE`, `offerId` 비움, `lock:rider` 0개. 앞의 기록과 같다.
- 거절 → 다음 제안이 43ms (로그 시각 00.443 → 00.486). 2순위가 수락하니 `DELIVERING`, 13초 뒤 타이머가 와도 `ACCEPTED`.
- GI-02 와도 맞물린다. 좌표를 끊고 1분 뒤 IDLE 이던 5명은 `OFFLINE` 으로 빠졌고 배달 중인 2번은 GEO 에 남았다.
- 전체 테스트 22개 클래스 전부 통과 (레디스에 붙여서, 건너뛴 것 없이).

RE-02 에서 남은 것
- 만료 큐 리스너는 스프링 AMQP 의 `acknowledge-mode: auto` 다. 카프카 컨슈머는 전부 수동인데
  여기만 다르다. 카프카의 자동 커밋은 **시간을 보고** 오프셋을 옮겨서 처리 중에 죽으면 주문이
  사라지지만, AMQP 의 AUTO 는 **리스너가 예외 없이 끝났는지를 보고** ack 한다. 이름만 자동이지
  하는 일은 손으로 짤 ack 와 같다. 손으로 ack 하려고 예외를 잡으면 오히려 스프링의 재시도가
  안 걸려서, 레디스가 끊긴 동안 메시지가 초당 수천 번 다시 도는 뜨거운 루프가 된다.
- **확인 타임아웃 5초 × 후보 수가 리스 15초를 넘을 수 있다.** 위의 confirm 사고 때 실제로
  `리스를 잃은 채로 재제안을 진행했다` 경고가 찍혔다. 경고 장치는 제대로 동작한 셈인데,
  브로커가 느릴 때 이게 정상적으로 날 수 있다는 뜻이다. 4단계 부하 실험에서 다시 본다.
- 재제안 지표(`offer_expired_total`, `dispatch_attempts`)는 3단계에서 붙인다. 지금은 로그뿐이라
  만료 큐가 두꺼워졌을 때 펜싱에 걸린 건지 보드가 없어서인지를 로그를 뒤져야 안다.


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
