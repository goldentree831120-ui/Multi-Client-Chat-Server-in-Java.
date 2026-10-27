#!/usr/bin/env bash
# M-A step 4 in one command: create a local Kubernetes cluster with kind, deploy the chat server
# and Prometheus, check them, send real traffic through a port-forward, then delete the chat Pod
# and watch the Deployment replace it.
#   ./scripts/k8s-up.sh            (remove everything later with ./scripts/k8s-down.sh)
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.local/bin:$PATH"
NS=chat
PF_PIDS=()
cleanup() { for p in "${PF_PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

for tool in docker kind kubectl java javac curl python3; do
  command -v "$tool" >/dev/null || { echo "missing: $tool (for kind/kubectl run ./scripts/install-k8s-tools.sh)"; exit 1; }
done
docker info >/dev/null 2>&1 || { echo "Docker isn't running: sudo service docker start"; exit 1; }
if [ -n "$(docker compose ps -q 2>/dev/null)" ]; then
  echo "The docker compose stack (step 3) is using ports 5000/9100/9090. Stop it first: docker compose down"
  exit 1
fi

echo "== 1. cluster"
if kind get clusters | grep -qx chat; then echo "ok  cluster 'chat' already exists"
else kind create cluster --config k8s/kind-cluster.yaml --wait 120s; fi
kubectl config use-context kind-chat >/dev/null
kubectl get nodes

echo "== 2. image: build locally, then copy it into the cluster's node (no registry needed)"
docker build -q -t chat-server:local . >/dev/null
kind load docker-image chat-server:local --name chat
# Prometheus is NOT copied in: Docker Hub's prom/prometheus is a multi-platform image, and
# `kind load` fails on those ("content digest ... not found"). The node pulls it itself instead.

echo "== 3. deploy"
kubectl apply -f k8s/namespace.yaml
kubectl -n "$NS" create configmap prometheus-config \
  --from-file=prometheus-k8s.yml=monitoring/prometheus/prometheus-k8s.yml \
  --from-file=alerts.yml=monitoring/prometheus/alerts.yml \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f k8s/chat-server.yaml -f k8s/prometheus.yaml
kubectl -n "$NS" rollout restart deployment/chat-server deployment/prometheus >/dev/null  # pick up a rebuilt image / new config
kubectl -n "$NS" rollout status deployment/chat-server --timeout=180s
kubectl -n "$NS" rollout status deployment/prometheus --timeout=180s
kubectl -n "$NS" get deployments,pods,services -o wide

echo "== 4. reach the Services from this machine (kubectl port-forward)"
start_pf() {
  kubectl -n "$NS" port-forward "$1" "$2" >/dev/null 2>&1 &
  PF_PIDS+=($!)
}
start_pf svc/chat-server 5000:5000
start_pf svc/chat-server 9100:9100
start_pf svc/prometheus 9090:9090
for _ in $(seq 1 30); do curl -fsS localhost:9100/readyz >/dev/null 2>&1 && curl -fsS localhost:9090/-/ready >/dev/null 2>&1 && break; sleep 1; done
curl -fsS localhost:9100/readyz >/dev/null || { echo "FAIL  chat server not reachable through the port-forward"; exit 1; }
echo "ok  chat server /readyz through the Service"
prom_value() {
  curl -fsS --get localhost:9090/api/v1/query --data-urlencode "query=$1" \
    | python3 -c "import json,sys; r=json.load(sys.stdin)['data']['result']; print(r[0]['value'][1] if r else 'none')"
}
for _ in $(seq 1 30); do [ "$(prom_value 'up{job="chat"}')" = "1" ] && break; sleep 2; done
[ "$(prom_value 'up{job="chat"}')" = "1" ] || { echo "FAIL  Prometheus can't scrape the chat Service"; exit 1; }
echo "ok  Prometheus (in the cluster) scrapes chat-server.chat.svc.cluster.local:9100"

echo "== 5. traffic: 20 clients, 4 rooms, 50 msg/s for 30 s through the port-forward"
javac -d out src/*.java tools/*.java
java -cp out LoadTest localhost 5000 20 4 50 30
echo "ok  server counted $(curl -fsS localhost:9100/metrics | awk '$1=="chat_messages_processed_total{type=\"broadcast\"}" {print $2}') broadcast messages"

echo "== 6. self-healing: delete the chat Pod and watch the Deployment replace it"
old=$(kubectl -n "$NS" get pods -l app=chat-server -o jsonpath='{.items[0].metadata.name}')
t0=$(date +%s)
kubectl -n "$NS" delete pod "$old" --wait=false
kubectl -n "$NS" wait --for=delete "pod/$old" --timeout=60s >/dev/null 2>&1 || true
kubectl -n "$NS" wait --for=condition=Ready pod -l app=chat-server --timeout=120s >/dev/null
new=$(kubectl -n "$NS" get pods -l app=chat-server -o jsonpath='{.items[0].metadata.name}')
echo "ok  old Pod $old deleted; new Pod $new Ready after $(( $(date +%s) - t0 )) s"
echo "    (open connections and accounts were lost: they live in the old Pod's memory)"

cleanup; PF_PIDS=()
echo
echo "All done. To look around yourself:"
echo "  kubectl -n chat get pods -w                              watch Pods"
echo "  kubectl -n chat describe pod -l app=chat-server          probes, events, restarts"
echo "  kubectl -n chat logs deploy/chat-server                  server log"
echo "  kubectl -n chat port-forward svc/prometheus 9090:9090    then open http://localhost:9090"
echo "Remove the cluster with ./scripts/k8s-down.sh"
