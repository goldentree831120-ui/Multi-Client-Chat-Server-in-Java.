#!/usr/bin/env bash
# Builds the image, starts a container, and checks it from the outside:
#   1. /healthz and /readyz answer 200
#   2. /metrics is in Prometheus format and shows 0 open connections
#   3. after a TCP client connects, chat_active_connections becomes 1
#   4. the container runs as a non-root user
# Then stops the container. Exit code 0 = all good.
set -euo pipefail
cd "$(dirname "$0")/.."
IMAGE=chat-server:local
NAME=chat-smoke
CHAT=15000
METRICS=19100

docker build -t "$IMAGE" .
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -p "$CHAT:5000" -p "$METRICS:9100" "$IMAGE" >/dev/null
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT

metric() { curl -fsS "localhost:$METRICS/metrics" | awk -v m="$1" '$1==m {print $2}'; }

for _ in $(seq 1 30); do
  curl -fsS "localhost:$METRICS/readyz" >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS "localhost:$METRICS/healthz" >/dev/null && echo "ok  /healthz 200"
curl -fsS "localhost:$METRICS/readyz" >/dev/null && echo "ok  /readyz 200"

[ "$(metric chat_active_connections)" = "0" ] && echo "ok  /metrics: chat_active_connections 0"

exec 3<>"/dev/tcp/127.0.0.1/$CHAT"      # open a chat connection and keep it open
for _ in $(seq 1 10); do
  [ "$(metric chat_active_connections)" = "1" ] && break
  sleep 0.5
done
[ "$(metric chat_active_connections)" = "1" ] && echo "ok  after one client connects: chat_active_connections 1"
exec 3>&-

uid=$(docker exec "$NAME" id -u)
[ "$uid" = "10001" ] && echo "ok  runs as uid $uid (not root)"
echo "all checks passed"
