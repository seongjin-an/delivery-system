#!/usr/bin/env bash
# 5초마다 한 줄씩 찍는다. 실험 내내 켜두고 나중에 CSV 로 읽는다.
#   ./sample.sh > run.csv
# 칸: 시각, 래빗 큐 깊이, 래빗 메모리(MB), 래빗 알람, 래빗 컨테이너 메모리, 카프카 컨테이너 메모리,
#     카프카 랙, 래빗 데이터 디렉터리(MB), 카프카 데이터 디렉터리(MB),
#     geo-indexer 누적 레코드, 순서 뒤집힘, 묵은 정도 p99(초), 묵은 정도 max(초)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AUTH=dev_user:dev_password
echo "time,q_depth,rabbit_mem_mb,alarms,rabbit_ctr_mem,kafka_ctr_mem,kafka_lag,rabbit_disk_mb,kafka_disk_mb,geo_records,geo_regression,stale_p99_s,stale_max_s"
while true; do
  t=$(date +%H:%M:%S)
  q=$(curl -s -u $AUTH localhost:15672/api/queues/%2F/rider.location | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("messages",""))
except Exception: print("")')
  node=$(curl -s -u $AUTH localhost:15672/api/nodes | python3 -c 'import sys,json
n=json.load(sys.stdin)[0]; a=[]
a += ["mem"] if n.get("mem_alarm") else []
a += ["disk"] if n.get("disk_free_alarm") else []
print("%d,%s" % (n["mem_used"]/1048576, "+".join(a) or "-"))')
  ctr=$(docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' delivery-rabbitmq delivery-kafka | awk '{print $2}' | paste -sd, -)
  lag=$(docker exec delivery-kafka kafka-consumer-groups --bootstrap-server localhost:9094 --describe --group geo-indexer 2>/dev/null \
        | awk '$2=="rider.location" && $6 ~ /^[0-9]+$/ {s+=$6} END {print s+0}')
  rd=$(du -sm "$ROOT/infra/.data/rabbitmq" | cut -f1)
  kd=$(du -sm "$ROOT/infra/.data/kafka" | cut -f1)
  geo=$(curl -s --max-time 2 localhost:8092/actuator/prometheus | awk '
    /^geo_index_records_total/ {r=$2}
    /^geo_index_order_regression_total/ {g=$2}
    /^geo_index_staleness_seconds\{.*quantile="0.99"/ {p=$2}
    /^geo_index_staleness_seconds_max/ {m=$2}
    END {printf "%s,%s,%.1f,%.1f", r, g, p, m}')
  [[ -z "$geo" ]] && geo=",,,"
  echo "$t,$q,$node,$ctr,$lag,$rd,$kd,$geo"
  sleep 5
done
