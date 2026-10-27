#!/usr/bin/env bash
# One command for M-A step 3: start chat + Prometheus + Grafana, send 60 s of real chat traffic,
# and check every piece from the outside. Leaves everything running so you can open Grafana.
#   ./scripts/monitoring-up.sh          (stop later with: docker compose down)
set -euo pipefail
cd "$(dirname "$0")/.."

wait_for() {  # url, name
  for _ in $(seq 1 60); do
    curl -fsS "$1" >/dev/null 2>&1 && { echo "ok  $2 is up"; return 0; }
    sleep 2
  done
  echo "FAIL  $2 did not come up ($1)"
  docker compose ps
  exit 1
}
prom_value() {  # PromQL -> first value, or "none"
  curl -fsS --get localhost:9090/api/v1/query --data-urlencode "query=$1" \
    | python3 -c "import json,sys; r=json.load(sys.stdin)['data']['result']; print(r[0]['value'][1] if r else 'none')"
}

docker compose up -d --build
wait_for localhost:9100/readyz "chat server"
wait_for localhost:9090/-/ready "Prometheus"
wait_for localhost:3000/api/health "Grafana"

for _ in $(seq 1 30); do
  [ "$(prom_value 'up{job="chat"}')" = "1" ] && break
  sleep 2
done
[ "$(prom_value 'up{job="chat"}')" = "1" ] && echo "ok  Prometheus is scraping the chat server"
curl -fsS localhost:3000/api/dashboards/uid/chat-server >/dev/null && echo "ok  Grafana has the 'Chat server' dashboard"
echo "ok  alert rules loaded: $(curl -fsS localhost:9090/api/v1/rules \
  | python3 -c "import json,sys; print(', '.join(r['name'] for g in json.load(sys.stdin)['data']['groups'] for r in g['rules']))")"

echo "sending 60 s of chat traffic (20 clients, 4 rooms, 50 msg/s) so the graphs have data..."
javac -d out src/*.java tools/*.java
java -cp out LoadTest localhost 5000 20 4 50 60
sleep 10
# Read the raw counter: increase() over a window is an estimate and can overshoot at the edges.
echo "ok  Prometheus reads $(prom_value 'sum(chat_messages_processed_total{type="broadcast"})') broadcast messages handled since the server started"
echo
echo "Open in your browser:"
echo "  Grafana     http://localhost:3000      (the 'Chat server' dashboard is the home page)"
echo "  Prometheus  http://localhost:9090/alerts"
echo "Stop everything with: docker compose down"
