#!/usr/bin/env bash
# 스크립트들이 공유하는 것들. 서비스 목록과 포트를 한 곳에서만 고치게 하려고 뺐다.
# (social-discovery 에서는 스크립트마다 목록이 흩어져 있어서 서비스 추가할 때마다 빼먹었다)

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS="$ROOT/logs"
PIDS="$ROOT/pids"
COMPOSE_FILE="$ROOT/infra/compose.yaml"

# 기동 순서대로. offer-relay 가 래빗엠큐 토폴로지를 선언하므로 dispatch-engine 보다 먼저 뜬다.
SERVICES=(
  order-api
  location-ingest
  geo-indexer
  offer-relay
  dispatch-engine
  notification-worker
  settlement-service
  rider-simulator
)

port_of() {
  case "$1" in
    order-api)           echo 8090 ;;
    location-ingest)     echo 8091 ;;
    geo-indexer)         echo 8092 ;;
    dispatch-engine)     echo 8093 ;;
    offer-relay)         echo 8094 ;;
    notification-worker) echo 8095 ;;
    settlement-service)  echo 8096 ;;
    rider-simulator)     echo 8097 ;;
    *) return 1 ;;
  esac
}

# Java 21 고정 — Gradle 빌드와 실행이 같은 JDK 를 쓰게 한다
JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
if [[ -z "$JAVA_HOME" ]]; then
  echo "Java 21 이 없다. 'brew install --cask temurin@21' 또는 sdkman 으로 깔고 다시 실행." >&2
  exit 1
fi
export JAVA_HOME
JAVA_CMD="$JAVA_HOME/bin/java"

# OTel 자바 에이전트 — 트레이스/로그를 수집기로 보낸다. 없으면 앱은 그냥 뜨고 관측만 빠진다.
OTEL_AGENT_VERSION="${OTEL_AGENT_VERSION:-2.11.0}"
OTEL_AGENT="$ROOT/infra/otel/opentelemetry-javaagent.jar"
OTEL_AGENT_URL="https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"
OTEL_ENDPOINT="${OTEL_ENDPOINT:-http://localhost:4317}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; GRAY='\033[0;90m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; }

wait_port() {
  local name=$1 port=$2 timeout=${3:-60}
  for ((i=0; i<timeout; i++)); do
    if nc -z localhost "$port" 2>/dev/null; then
      success "$name is up (:$port)"
      return 0
    fi
    sleep 1
  done
  error "$name 이 ${timeout}s 안에 안 떴다 — ./scripts/logs.sh $name"
  return 1
}

download_otel_agent() {
  [[ -f "$OTEL_AGENT" ]] && return 0
  info "OTel 자바 에이전트 v${OTEL_AGENT_VERSION} 내려받는다..."
  curl -L --fail --silent --show-error -o "$OTEL_AGENT" "$OTEL_AGENT_URL" \
    || { rm -f "$OTEL_AGENT"; warn "에이전트 다운로드 실패 — 트레이스/로그 수집 없이 진행한다"; return 0; }
  success "OTel 에이전트 준비 완료"
}

# 인스턴스 이름(예: geo-indexer-2)으로 프로세스를 띄운다.
#   $1 서비스명  $2 포트  $3 인스턴스 라벨(로그/PID 파일 이름)
start_spring() {
  local svc=$1 port=$2 label=${3:-$1}

  if nc -z localhost "$port" 2>/dev/null; then
    warn "$label — :$port 이 이미 열려 있다. 건너뛴다"
    return 0
  fi

  local jar
  jar=$(find "$ROOT/services/$svc/build/libs" -name "*.jar" ! -name "*plain*" 2>/dev/null | head -1)
  if [[ -z "$jar" ]]; then
    error "$svc jar 가 없다 — ./scripts/build.sh $svc 먼저"
    return 1
  fi

  local opts=()
  if [[ -f "$OTEL_AGENT" ]]; then
    opts=(
      "-javaagent:${OTEL_AGENT}"
      "-Dotel.service.name=${svc}"
      # 인스턴스를 여러 개 띄우면 트레이스에서 누가 처리했는지 구분되어야 한다
      "-Dotel.resource.attributes=service.instance.id=${label},deployment.environment=local"
      "-Dotel.exporter.otlp.endpoint=${OTEL_ENDPOINT}"
      "-Dotel.exporter.otlp.protocol=grpc"
      "-Dotel.traces.exporter=otlp"
      "-Dotel.logs.exporter=otlp"
      # 메트릭은 actuator 를 프로메테우스가 직접 긁으므로 여기선 끈다 (중복 수집 방지)
      "-Dotel.metrics.exporter=none"
      "-Dotel.propagators=tracecontext,baggage"
      # 카프카/래빗엠큐 헤더로 traceparent 를 넘기는 계측
      "-Dotel.instrumentation.kafka.experimental-span-attributes=true"
      "-Dotel.instrumentation.rabbitmq.enabled=true"
    )
  fi

  info "$label 기동 ..."
  # 빈 배열 전개는 macOS bash 3.2 + set -u 에서 죽는다 (에이전트 다운로드 실패 시 재현)
  SERVER_PORT="$port" "$JAVA_CMD" ${opts[@]+"${opts[@]}"} -jar "$jar" > "$LOGS/$label.log" 2>&1 &
  echo $! > "$PIDS/$label.pid"
  wait_port "$label" "$port" 90
}
