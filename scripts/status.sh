#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/_common.sh"

check_proc() {
  local label=$1 port=$2 note=${3:-}
  local pid="-" dead=""
  if [[ -f "$PIDS/$label.pid" ]]; then
    pid=$(cat "$PIDS/$label.pid")
    kill -0 "$pid" 2>/dev/null || dead=" ${RED}(dead)${NC}"
  fi
  if nc -z localhost "$port" 2>/dev/null; then
    printf "  %-24s %b  :%-6s PID %-8s%b %b\n" "$label" "${GREEN}UP  ${NC}" "$port" "$pid" "$dead" "${GRAY}$note${NC}"
  else
    printf "  %-24s %b  :%-6s PID %-8s%b\n" "$label" "${RED}DOWN${NC}" "$port" "$pid" "$dead"
  fi
}

check_docker() {
  local label=$1 container=$2 port=$3 note=${4:-}
  local running
  running=$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || echo "false")
  if [[ "$running" == "true" ]]; then
    printf "  %-24s %b  :%-6s %b\n" "$label" "${GREEN}UP  ${NC}" "$port" "${GRAY}$note${NC}"
  else
    printf "  %-24s %b  :%-6s\n" "$label" "${YELLOW}STOP${NC}" "$port"
  fi
}

echo ""
echo -e "${CYAN}────────────────────────────────────────────────────────────${NC}"
echo -e "${CYAN}  Delivery System — 상태${NC}"
echo -e "${CYAN}────────────────────────────────────────────────────────────${NC}"
echo ""
echo -e "  ${GRAY}[ 저장소 · 메시징 ]${NC}"
check_docker "MySQL"        delivery-mysql        33306
check_docker "Redis"        delivery-redis        6380
check_docker "RedisInsight" delivery-redisinsight 5541  "http://localhost:5541"
check_docker "Kafka"        delivery-kafka        9094
check_docker "Kafka UI"     delivery-kafka-ui     9091  "http://localhost:9091"
check_docker "RabbitMQ"     delivery-rabbitmq     5672  "http://localhost:15672"
echo ""
echo -e "  ${GRAY}[ 관측 ]${NC}"
check_docker "OTel Collector" delivery-otel-collector 4317
check_docker "Prometheus"     delivery-prometheus     9099 "http://localhost:9099"
check_docker "Loki"           delivery-loki           3100
check_docker "Tempo"          delivery-tempo          3200
check_docker "Grafana"        delivery-grafana        3001 "http://localhost:3001"
check_docker "kafka-exporter" delivery-kafka-exporter 9308
check_docker "redis-exporter" delivery-redis-exporter 9121
echo ""
echo -e "  ${GRAY}[ 서비스 ]${NC}"
for svc in "${SERVICES[@]}"; do
  check_proc "$svc" "$(port_of "$svc")"
done

# scale.sh 로 띄운 추가 인스턴스 (라벨 뒤에 -N 이 붙는다)
shopt -s nullglob
extra=("$PIDS"/*-[0-9].pid)
if (( ${#extra[@]:-0} )); then
  echo ""
  echo -e "  ${GRAY}[ 추가 인스턴스 ]${NC}"
  for f in "${extra[@]}"; do
    label=$(basename "$f" .pid)
    svc="${label%-*}"; idx="${label##*-}"
    base=$(port_of "$svc") || continue
    check_proc "$label" "$(( base + 100 * (idx - 1) ))"
  done
fi
shopt -u nullglob
echo ""
echo -e "${CYAN}────────────────────────────────────────────────────────────${NC}"
echo ""
