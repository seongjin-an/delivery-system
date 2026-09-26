# 2단계 실험 — 배차 상태를 MySQL 로 옮겨본다

이 브랜치(`exp/dispatch-state-mysql`)는 main 에 머지하지 않는다. 해석은 main 의 `.reference/tech-choice.md` 5절에 있다.

## 무엇을 바꿨나

`DISPATCH_STATE_STORE=mysql` 로 띄우면 제안 현황판(`dispatch:offer:{orderId}` 해시)과 배차 리스(`lock:dispatch:{orderId}`)가
MySQL `order_dispatch` 행 하나로 간다. 세 서비스(order-api, dispatch-engine, offer-relay)가 같은 값이어야 한다.
라이더 찜, 라이더 상태, 후보 목록은 그대로 레디스에 둔다.

- `libs/common`
  - `MysqlOfferBoard` — Lua 세 개(respond, expire, cancel)를 `UPDATE ... WHERE state = 'OFFERED' AND offer_id = ?` 로 한다.
    0행이면 한 번 더 읽어서 이유(이미 수락, 만료, 남의 제안)를 가른다. 취소만 이전 상태가 필요해서 `SELECT ... FOR UPDATE` 트랜잭션이다.
  - `MysqlDispatchLease` — `UPDATE ... SET lease_owner = ?, lease_until = ? WHERE lease_until IS NULL OR lease_until < now` 한 줄.
  - `MysqlDispatchOutbox` — 수락 때 `dispatch.assigned`, `order.status` 를 order-api 의 `outbox` 테이블에 넣는다.
    Debezium 이 `destination_topic` 으로 내보낸다.
  - `ExperimentTimers` — 판 전후 누적값의 차이로 분포를 보려고 고정 구간을 단 타이머.
- `dispatch-engine` — MySQL 판에서 수락(DE-04)은 현황판 UPDATE 와 아웃박스 INSERT 를 한 트랜잭션에 묶고, 카프카로 직접 보내지 않는다.
- 타이머: `dispatch_duration`(DE-01 배차 한 건), `relay_duration`(RE-02 재제안 한 건), `offer_accept_duration`, `offer_reject_duration`.
- `MysqlDispatchStateTest` — 레디스 Lua 테스트(`DispatchScriptsRedisTest`)의 현황판, 리스 경우 19개를 그대로 옮기고, 수락과 만료를
  동시에 100번 날려서 매번 한쪽만 이기는지 보는 경주와 리스 만료를 더했다.

## 돌리는 법

```bash
docker exec -i delivery-mysql mysql -udev_user -pdev_password delivery < experiments/dispatch-state-mysql/schema.sql
DISPATCH_STATE_STORE=redis ./scripts/start.sh           # 또는 mysql
cd experiments/dispatch-state-mysql
RATE=10 ./load.sh warmup && rm results/warmup-*          # 데운다. 버린다
RATE=20 ./load.sh <라벨>                                 # 라이더 2000명, 주문 초당 20건 60초
KILL=dispatch RATE=20 ./load.sh <라벨>                   # 20초째 dispatch-engine kill -9, 10초 뒤 재기동
KILL=redis RATE=20 ./load.sh <라벨>                      # 20초째 레디스 컨테이너 kill -9, 5초 뒤 재기동
```

`mysql-all.sh`, `run-all.sh`, `kill-repeat.sh`, `redis-repeat.sh` 는 위를 이어서 돌리는 묶음이다.

## 읽을 때 조심할 것

- kill 판의 `relay_duration` 은 쓰면 안 된다. dispatch-engine 이 재시작돼서 지표가 0 부터 세지니 판 전 스크레이프를 비웠는데,
  offer-relay 는 안 죽여서 재제안 지표가 누적값 그대로다.
- 워밍업 판 하나에서 시뮬레이터가 막 뜬 서비스에 3초 타임아웃으로 수락을 실패 처리했는데 서버에선 수락이 돼 있었다.
  그 라이더들은 DELIVERING 으로 갇혀서, `load.sh` 는 DELIVERING 을 빼고 한가한 라이더만 세서 기다린다.
- OTel 에이전트가 붙은 채로 `scripts/start.sh` 로 띄웠다. 같은 노트북에 서비스 여덟 개와 컨테이너 열다섯 개가 같이 돈다.

## 원본 숫자 (`results/`)

`<라벨>-latency.txt`(타이머 분포), `<라벨>-orders.txt`(주문 최종 상태, 평균 attempt), `<라벨>-sim.json`(시뮬레이터),
`<라벨>-cpu.txt`(판 중간 컨테이너 CPU), `<라벨>-timeline.txt`.
