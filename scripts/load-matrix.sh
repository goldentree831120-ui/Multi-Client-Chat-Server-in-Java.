#!/usr/bin/env bash
# M-A step 5: load test at four levels against the docker compose stack, recording for each:
#   end-to-end latency (LoadTest), server-side p95 (Prometheus), deliveries vs expected, errors,
#   and peak CPU / memory of the chat container (docker stats).
# Writes docs/loadtest-latest.md (table + this machine's hardware) and docs/loadtest-results.txt (raw).
# docs/LOADTEST.md is the hand-written analysis of the run of 27 Oct 2026 and is not overwritten.
#   ./scripts/load-matrix.sh            about 6 minutes
set -euo pipefail
cd "$(dirname "$0")/.."

PROM=${PROM_URL:-http://localhost:9090}
SECONDS_PER_LEVEL=${SECONDS_PER_LEVEL:-60}
# clients rooms msg/s
LEVELS=("20 4 50" "100 10 200" "200 20 500" "500 25 1000")

if [ "${SKIP_DOCKER:-0}" != "1" ]; then
  docker info >/dev/null 2>&1 || { echo "Docker isn't running: sudo service docker start"; exit 1; }
  docker compose up -d --build
fi
for _ in $(seq 1 60); do
  curl -fsS localhost:9100/readyz >/dev/null 2>&1 && curl -fsS "$PROM/-/ready" >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS localhost:9100/readyz >/dev/null || { echo "chat server not up on localhost:9100"; exit 1; }
CONTAINER=""
[ "${SKIP_DOCKER:-0}" != "1" ] && CONTAINER=$(docker compose ps -q chat)

javac -d out src/*.java tools/*.java

prom_value() {
  curl -fsS --get "$PROM/api/v1/query" --data-urlencode "query=$1" \
    | python3 -c "import json,sys; r=json.load(sys.stdin)['data']['result']; print(r[0]['value'][1] if r else 'nan')"
}

cpu_model=$(lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -1)
mem_gb=$(free -g | awk '/^Mem:/ {print $2}')
results=docs/loadtest-results.txt
mkdir -p docs
{
  echo "# $(date -u +%Y-%m-%dT%H:%M:%SZ)  $cpu_model, $(nproc) logical CPUs, ${mem_gb} GiB RAM (as seen by WSL)"
} > "$results"

rows=()
for level in "${LEVELS[@]}"; do
  read -r clients rooms rate <<<"$level"
  echo
  echo "=== $clients clients, $rooms rooms, $rate msg/s for $SECONDS_PER_LEVEL s"
  stats_file=$(mktemp)
  sampler=""
  if [ -n "$CONTAINER" ]; then
    ( while true; do docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' "$CONTAINER" >> "$stats_file" 2>/dev/null; done ) &
    sampler=$!
  fi
  out=$(java -cp out LoadTest localhost 5000 "$clients" "$rooms" "$rate" "$SECONDS_PER_LEVEL" 2>&1 | tee /dev/stderr)
  [ -n "$sampler" ] && { kill "$sampler" 2>/dev/null || true; wait "$sampler" 2>/dev/null || true; }
  sleep 6   # one more scrape so Prometheus has the end of the run
  result=$(grep '^RESULT' <<<"$out" || echo "RESULT failed")
  window=$((SECONDS_PER_LEVEL + 5))
  srv_p95=$(prom_value "histogram_quantile(0.95, sum by (le) (increase(chat_message_handling_seconds_bucket[${window}s])))")
  srv_p99=$(prom_value "histogram_quantile(0.99, sum by (le) (increase(chat_message_handling_seconds_bucket[${window}s])))")
  cpu_max=$(awk '{gsub("%","",$1); if ($1+0>m) m=$1+0} END {if (NR) printf "%.0f", m}' "$stats_file")
  mem_max=$(awk '{print $2}' "$stats_file" | sort -h | tail -1)
  rm -f "$stats_file"
  echo "$result server_p95_s=$srv_p95 server_p99_s=$srv_p99 cpu_max_pct=${cpu_max:-n/a} mem_max=${mem_max:-n/a}" | tee -a "$results"
  rows+=("$result server_p95_s=$srv_p95 server_p99_s=$srv_p99 cpu_max_pct=${cpu_max:-n/a} mem_max=${mem_max:-n/a}")
  sleep 10
done

python3 - "$results" > docs/loadtest-latest.md <<'PY'
import sys
lines = open(sys.argv[1]).read().splitlines()
header, rows = lines[0], [l for l in lines[1:] if l.startswith("RESULT")]
def kv(l): return dict(p.split("=", 1) for p in l.split()[1:] if "=" in p)
def ms(x):
    try: v = float(x)
    except ValueError: return x
    return "≥ 1000" if v >= 1 else f"{v * 1000:.2f}"   # 1 s is the largest histogram bucket
print("# Load test results (latest run, generated)\n")
print(f"Measured with `scripts/load-matrix.sh` against the docker compose stack. Machine: {header[2:].split('  ', 1)[1]}.")
print(f"Run at {header[2:].split('  ', 1)[0]}. Client and server ran on the same machine, so they compete for the same CPU.\n")
print("| Clients | Rooms | Target msg/s | Sent | Deliveries (received / expected) | Errors | End-to-end p50 / p95 / p99 / max (ms) | Server p95 / p99 (ms) | Peak CPU | Peak memory |")
print("|---|---|---|---|---|---|---|---|---|---|")
for l in rows:
    d = kv(l)
    if "clients" not in d:
        print("| run failed | | | | | | | | | |"); continue
    print(f"| {d['clients']} | {d['rooms']} | {d['rate']} | {d['sent']} | {d['received']} / {d['expected']} | {d['errors']} | "
          f"{d['p50_ms']} / {d['p95_ms']} / {d['p99_ms']} / {d['max_ms']} | {ms(d['server_p95_s'])} / {ms(d['server_p99_s'])} | "
          f"{d['cpu_max_pct']}{'%' if d['cpu_max_pct'] != 'n/a' else ''} | {d['mem_max']} |")
print("\n- **End-to-end** (from `tools/LoadTest.java`): send time to receive time at every other client in the room, through the real TCP stack.")
print("- **Server** (from Prometheus, `chat_message_handling_seconds`): the server's own work per message, from reading the line to writing it to every recipient. Bucket-based, so approximate.")
print("- **Peak CPU** is `docker stats` for the chat container; 100% = one full core.")
print("- Each level has rooms of (clients / rooms) people, so every message is delivered to that many minus one.")
PY
echo
echo "wrote docs/loadtest-latest.md and $results"
cat docs/loadtest-latest.md
