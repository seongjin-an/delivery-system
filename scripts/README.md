# scripts

| 스크립트 | 하는 일 |
|---|---|
| `start.sh` | 인프라 기동 → 토픽 생성 → 전체 빌드 → 서비스 순차 기동 |
| `stop.sh` | 모든 서비스 종료 (추가 인스턴스까지) |
| `status.sh` | 컨테이너 · 서비스 · 추가 인스턴스 상태 한 화면 |
| `logs.sh` | 특정 서비스/인스턴스 로그 tail |
| `build.sh` | 전체 또는 일부 모듈 빌드 |
| `restart.sh` | 서비스 하나만 재빌드 + 재기동 |
| `scale.sh` | **서비스 인스턴스 수를 N개로 맞춘다** — 이 프로젝트의 핵심 손잡이 |
| `_common.sh` | 서비스 목록 · 포트 · OTel 에이전트 옵션 공용 정의 |

## 자주 쓸 패턴

```bash
# 처음 시작 (인프라부터 전부)
./scripts/start.sh

# 이미 빌드된 jar 로 바로 띄우기
SKIP_BUILD=true ./scripts/start.sh

# 컨테이너는 이미 떠 있을 때
SKIP_INFRA=true SKIP_BUILD=true ./scripts/start.sh

# 헬로월드 확인
for p in 8090 8091 8092 8093 8094 8095 8096 8097; do curl -s localhost:$p/hello; echo; done

# 상태
./scripts/status.sh

# geo-indexer 를 4개로 늘리기 → 8092, 8192, 8292, 8392
./scripts/scale.sh geo-indexer 4
./scripts/logs.sh geo-indexer-3

# 다시 1개로
./scripts/scale.sh geo-indexer 1

# 하나만 재기동
./scripts/restart.sh dispatch-engine
./scripts/restart.sh dispatch-engine --skip-build

# 서비스만 종료 (컨테이너 유지)
./scripts/stop.sh

# 컨테이너까지 전부
STOP_INFRA=true ./scripts/stop.sh
```

## 포트 맵

| 서비스 | 기본 포트 | 2번째 인스턴스 | 3번째 |
|---|---|---|---|
| order-api | 8090 | 8190 | 8290 |
| location-ingest | 8091 | 8191 | 8291 |
| geo-indexer | 8092 | 8192 | 8292 |
| dispatch-engine | 8093 | 8193 | 8293 |
| offer-relay | 8094 | 8194 | 8294 |
| notification-worker | 8095 | 8195 | 8295 |
| settlement-service | 8096 | 8196 | 8296 |
| rider-simulator | 8097 | 8197 | 8297 |

인스턴스를 늘렸으면 `infra/prometheus/prometheus.yml` 의 `targets` 에도 그 포트를 넣어야
그라파나에서 인스턴스별로 갈라 볼 수 있다. 안 넣으면 "늘렸는데 그래프가 안 변한다"고 착각하게 된다.
