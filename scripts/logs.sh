#!/usr/bin/env bash
# 특정 서비스 로그 tail. 인스턴스 라벨(geo-indexer-2)도 그대로 받는다.
#   ./scripts/logs.sh dispatch-engine
#   ./scripts/logs.sh geo-indexer-2 200
set -euo pipefail
source "$(dirname "$0")/_common.sh"

if [[ $# -lt 1 ]]; then
  echo "사용법: $0 <서비스|인스턴스라벨> [줄수]"
  echo ""
  echo "현재 로그 파일:"
  ls -1 "$LOGS" 2>/dev/null | sed 's/\.log$//' | sed 's/^/  /' || echo "  (없음)"
  exit 1
fi

LOG_FILE="$LOGS/$1.log"
LINES=${2:-100}

if [[ ! -f "$LOG_FILE" ]]; then
  error "로그 파일이 없다: $LOG_FILE"
  echo "떠 있는지 확인: ./scripts/status.sh"
  exit 1
fi

echo "==> $LOG_FILE (마지막 $LINES 줄, 이후 따라간다)"
echo ""
tail -n "$LINES" -f "$LOG_FILE"
