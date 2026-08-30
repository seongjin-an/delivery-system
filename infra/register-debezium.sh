#!/usr/bin/env bash
# Debezium 커넥터 등록 (멱등). 아웃박스 테이블의 INSERT 를 카프카로 흘려보낸다.
#
# 두 가지를 한다.
#   1. MySQL 에 debezium 계정과 복제 권한을 만든다 (이미 있으면 그냥 지나간다)
#   2. 커넥트에 커넥터 설정을 밀어넣는다 (PUT 이라 있으면 갱신, 없으면 생성)
#
# scripts/start.sh 가 토픽 생성 직후에 부른다.
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-delivery-mysql}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-1234}"
CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
CONFIG_FILE="$(dirname "$0")/debezium/order-outbox-connector.json"

echo "[debezium] MySQL 복제 계정 확인..."
docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" 2>/dev/null <<'SQL'
CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'debezium_password';
GRANT SELECT, RELOAD, SHOW DATABASES, LOCK TABLES, REPLICATION SLAVE, REPLICATION CLIENT
  ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;
SQL
echo "[debezium] ok  계정 준비 완료"

echo "[debezium] 커넥트 대기: $CONNECT_URL ..."
for _ in $(seq 1 60); do
  if curl -sf "$CONNECT_URL/connectors" >/dev/null 2>&1; then break; fi
  sleep 2
done
if ! curl -sf "$CONNECT_URL/connectors" >/dev/null 2>&1; then
  echo "[debezium] 커넥트가 안 뜬다 — docker logs delivery-connect" >&2
  exit 1
fi

NAME=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['name'])" "$CONFIG_FILE")
python3 -c "import json,sys;print(json.dumps(json.load(open(sys.argv[1]))['config']))" "$CONFIG_FILE" \
  > /tmp/debezium-config.json

# PUT /connectors/{name}/config 는 없으면 만들고 있으면 갱신한다. 두 번 돌려도 안전하다.
CODE=$(curl -s -o /tmp/debezium-resp.json -w '%{http_code}' \
  -X PUT "$CONNECT_URL/connectors/$NAME/config" \
  -H "Content-Type: application/json" --data-binary @/tmp/debezium-config.json)

if [[ "$CODE" != "200" && "$CODE" != "201" ]]; then
  echo "[debezium] 커넥터 등록 실패 (HTTP $CODE)" >&2
  cat /tmp/debezium-resp.json >&2; echo >&2
  exit 1
fi
echo "[debezium] ok  커넥터 '$NAME' 등록"

# RUNNING 이 될 때까지 기다린다. 등록만 되고 태스크가 죽는 경우가 흔해서 여기서 확인해야 한다.
for _ in $(seq 1 30); do
  STATE=$(curl -sf "$CONNECT_URL/connectors/$NAME/status" 2>/dev/null \
    | python3 -c "
import json,sys
try:
    d = json.load(sys.stdin)
    tasks = d.get('tasks', [])
    print(tasks[0]['state'] if tasks else d['connector']['state'])
except Exception:
    print('UNKNOWN')
" 2>/dev/null || echo UNKNOWN)
  if [[ "$STATE" == "RUNNING" ]]; then
    echo "[debezium] ok  커넥터 태스크 RUNNING"
    exit 0
  fi
  if [[ "$STATE" == "FAILED" ]]; then
    echo "[debezium] 커넥터 태스크가 FAILED 다:" >&2
    curl -sf "$CONNECT_URL/connectors/$NAME/status" >&2; echo >&2
    exit 1
  fi
  sleep 2
done
echo "[debezium] 커넥터가 RUNNING 까지 안 갔다 (마지막 상태: $STATE)" >&2
exit 1
