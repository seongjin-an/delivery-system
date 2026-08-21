#!/usr/bin/env bash
# 인수 없으면 전체, 있으면 해당 모듈만.
#   ./scripts/build.sh                       전체
#   ./scripts/build.sh geo-indexer order-api  일부
set -euo pipefail
source "$(dirname "$0")/_common.sh"

MODULES=("$@")
[[ ${#MODULES[@]} -eq 0 ]] && MODULES=("${SERVICES[@]}")

TASKS=()
for m in "${MODULES[@]}"; do TASKS+=(":$m:bootJar"); done

info "빌드: ${MODULES[*]}"
( cd "$ROOT" && ./gradlew "${TASKS[@]}" -x test --parallel )

echo ""
success "빌드 완료"
echo ""
for m in "${MODULES[@]}"; do
  jar=$(find "$ROOT/services/$m/build/libs" -name "*.jar" ! -name "*plain*" 2>/dev/null | head -1 || true)
  [[ -n "$jar" ]] && echo "  $m  $(basename "$jar")  ($(du -sh "$jar" | cut -f1))"
done
echo ""
