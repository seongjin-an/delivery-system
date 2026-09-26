# 2단계 실험 — 레디스 GEO 없이 MySQL 공간 인덱스로 후보를 찾아본다

이 브랜치(`exp/geo-mysql`)는 main 에 머지하지 않는다. 해석은 main 의 `.reference/tech-choice.md` 4절에 있다.

## 무엇을 바꿨나

- `dispatch-engine`
  - `GEO_STORE=mysql` 이면 `CandidateFinder` 의 "가까운 30명" 단계를 `MysqlNearbySearch` 가 한다.
    반경을 감싸는 네모로 공간 인덱스를 먼저 타고(`MBRContains`), 남은 것만 `ST_Distance_Sphere` 로 정렬한다.
    상태(IDLE 인지)는 그대로 레디스 `rider:state` 에서 읽는다. 바뀐 건 거리 검색 하나뿐이다.
  - `GET /api/candidates?lat=&lng=` — 주문 없이 후보 검색만 부르는 실험용 엔드포인트.
  - `candidate_nearby_search{store}` — 거리 검색 단계만 잰 시간.
  - `commons-pool2` 를 넣었다. 레디스 파이프라인이 검색마다 연결을 새로 열어서 초당 1000건에서 포트가 바닥났다
    (아래 "같이 찾은 것"). 이건 main 에도 고쳐 넣었다.
- `geo-indexer` — `GEO_STORE=mysql` 이면 레디스에 쓴 뒤 같은 배치를 MySQL `rider_position` 에 upsert 한다.
  `geo_index_write{store}` 로 두 쓰기를 따로 잰다.
- `schema.sql` — `POINT SRID 4326` 과 공간 인덱스. SRID 를 컬럼에 박아야 인덱스를 탄다.

## 돌리는 법

```bash
docker compose -f infra/compose.yaml up -d && bash infra/create-topics.sh
docker exec -i delivery-mysql mysql -udev_user -pdev_password delivery < experiments/geo-mysql/schema.sql
./gradlew :dispatch-engine:bootJar :geo-indexer:bootJar :location-ingest:bootJar
GEO_STORE=mysql ./scripts/restart.sh geo-indexer --skip-build     # 레디스 + MySQL 둘 다 쓴다
./scripts/restart.sh location-ingest --skip-build
GEO_STORE=redis ./scripts/restart.sh dispatch-engine --skip-build  # 또는 mysql
cd experiments/geo-mysql
k6 run -e RATE=3333 -e RIDERS=10000 -e DURATION=15m locations.js &  # 라이더 1만 명이 3초마다
./reads.sh <라벨>                                                    # 검색 초당 50, 200, 500, 1000 건
```

좌표 쓰기를 끄고 읽기만 잴 때는 geo-indexer 를 `SWEEP_ENABLED=false` 로(안 그러면 30초 뒤 GEO 에서 빠진다),
dispatch-engine 을 `DELIVERY_DISPATCH_MYSQL_ONLINE_WITHIN=2h` 로 띄운다(안 그러면 30초 뒤 후보가 0명이다).

## 환경

1, 2실험과 같은 노트북. MySQL 8.0.36. 이번엔 `scripts/restart.sh` 로 띄워서 OTel 에이전트가 붙어 있다.
k6 두 개(좌표, 검색)와 JVM 셋, 도커가 CPU 를 나눠 쓴다. 절대값보다 같은 조건에서의 비교로 봐야 한다.

## 원본 숫자 (`results/`)

| 판 | 파일 |
|---|---|
| 검색, 좌표 쓰기 켬 | `redis-writes-{50,200,500,1000}-*`, `mysql-writes-{50,200,500}-*` |
| 검색, 좌표 쓰기 끔 | `redis-nowrites-*`, `mysql-nowrites-*`, 풀 켠 뒤 `redis-nowrites-pool-{500,1000}-*` |
| 좌표 쓰기 | `locations-k6.txt`(레디스 + MySQL), `locations-redisonly*-k6.txt` |

`*-k6.txt` 는 k6 요약, `*-server.txt` 는 거리 검색 단계만 잰 서버 지표, `*-cpu.txt` 는 판 중간의 컨테이너 CPU 다.
