#!/usr/bin/env bash
# 인프라 기동 → 토픽 생성 → 빌드 → 서비스 기동.
#   SKIP_BUILD=true ./scripts/start.sh   이미 빌드된 jar 로 바로 띄우기
#   SKIP_INFRA=true ./scripts/start.sh   컨테이너가 이미 떠 있을 때
set -euo pipefail
source "$(dirname "$0")/_common.sh"

mkdir -p "$LOGS" "$PIDS" "$ROOT/infra/otel"

# ── 1. 인프라 ───────────────────────────────────────────────────────────────
if [[ "${SKIP_INFRA:-}" != "true" ]]; then
  info "인프라 기동 (docker compose)..."
  docker compose -f "$COMPOSE_FILE" up -d
  wait_port "MySQL"     33306 90
  wait_port "Redis"     6380  30
  wait_port "Kafka"     9094  90
  wait_port "RabbitMQ"  5672  90
  wait_port "Collector" 4317  60
  wait_port "Grafana"   3001  90
  wait_port "Connect"   8083  120
  success "인프라 준비 완료"

  info "카프카 토픽 생성..."
  bash "$ROOT/infra/create-topics.sh"

  # 아웃박스 행을 카프카로 내보내는 게 이제 앱이 아니라 Debezium 이다.
  # 이게 등록 안 되면 주문은 들어가는데 order.created 는 한 건도 안 나간다.
  info "Debezium 커넥터 등록..."
  bash "$ROOT/infra/register-debezium.sh"
fi

# ── 2. 빌드 ─────────────────────────────────────────────────────────────────
download_otel_agent

if [[ "${SKIP_BUILD:-}" != "true" ]]; then
  info "전체 모듈 빌드..."
  ( cd "$ROOT" && ./gradlew $(printf ':%s:bootJar ' "${SERVICES[@]}") -x test --parallel -q )
  success "빌드 완료"
fi

# ── 3. 서비스 기동 ──────────────────────────────────────────────────────────
for svc in "${SERVICES[@]}"; do
  start_spring "$svc" "$(port_of "$svc")"
done

# ── 4. 안내 ─────────────────────────────────────────────────────────────────
echo ""
success "전체 기동 완료"
echo ""
echo -e "  ${CYAN}Grafana${NC}         http://localhost:3001    ${GRAY}(로그·메트릭·트레이스 다 여기서 본다)${NC}"
echo -e "  ${CYAN}Prometheus${NC}      http://localhost:9099"
echo -e "  ${CYAN}Kafka UI${NC}        http://localhost:9091"
echo -e "  ${CYAN}RabbitMQ${NC}        http://localhost:15672    ${GRAY}(dev_user / dev_password)${NC}"
echo -e "  ${CYAN}RedisInsight${NC}    http://localhost:5541"
echo -e "  ${CYAN}Tempo${NC}           http://localhost:3200"
echo -e "  ${CYAN}Loki${NC}            http://localhost:3100"
echo ""
echo -e "  헬로월드 확인:  ${YELLOW}for p in 8090 8091 8092 8093 8094 8095 8096 8097; do curl -s localhost:\$p/hello; echo; done${NC}"
echo -e "  상태 확인:      ${YELLOW}./scripts/status.sh${NC}"
echo -e "  로그:           ${LOGS}/"
