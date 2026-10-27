# Kubernetes, explained with this project

Everything below refers to the files in `k8s/` and to what `scripts/k8s-up.sh` does.

## The pieces

**Cluster and node.** A cluster is a set of machines (nodes) that Kubernetes runs containers on.
`kind` ("Kubernetes in Docker") makes a whole cluster on one laptop: the node is itself a Docker
container (`docker ps` shows `chat-control-plane`). `k8s/kind-cluster.yaml` asks for one node.

**Pod.** The smallest thing Kubernetes runs: one or more containers that share a network address.
Our chat server Pod has one container from the `chat-server:local` image. Pods are disposable:
when one dies it is not repaired, it is replaced by a new Pod with a new name and a new IP.

**Deployment.** Says "keep N Pods of this template running" (`k8s/chat-server.yaml`). If a Pod
dies, or you delete it, the Deployment (through a ReplicaSet) starts a new one. Step 6 of
`k8s-up.sh` deletes the chat Pod on purpose and times how long the replacement takes to be Ready.
Changing the template (a new image, new settings) makes the Deployment roll out new Pods.

**Service.** Pods come and go with different IPs, so clients need a stable address. A Service
gives a fixed name, `chat-server.chat.svc.cluster.local`, and sends traffic to whichever Pods have
the label `app: chat-server` **and are Ready**. Prometheus scrapes that name
(`monitoring/prometheus/prometheus-k8s.yml`), so it keeps working when the Pod is replaced.
Our Services are `ClusterIP`: reachable only inside the cluster. From the laptop,
`kubectl port-forward svc/chat-server 5000:5000` tunnels a local port to it.

**Namespace.** A folder for resources. Everything here is in `chat`, so
`kubectl -n chat get all` shows only this project and `kubectl delete namespace chat` removes it.

**ConfigMap.** Configuration files stored in the cluster and mounted into Pods. Prometheus's
config and alert rules are one ConfigMap, built by `k8s-up.sh` from `monitoring/prometheus/`, so
docker compose and Kubernetes use the **same** alert rules file.

## Probes: how Kubernetes knows a Pod is healthy

| Probe | Endpoint | Question | If it fails |
|---|---|---|---|
| readiness | `GET /readyz` on port 9100 | "Can I send this Pod traffic?" 200 once the chat port is bound | the Service stops routing to the Pod; it is NOT restarted |
| liveness | `GET /healthz` on port 9100 | "Is the process stuck?" | after 3 failures in a row (30 s) the container is restarted |

They are separate on purpose: a Pod that is starting up, or briefly overloaded, should get no
new traffic, but restarting it would only make things worse.

## Choices in `k8s/chat-server.yaml`

| Setting | Why |
|---|---|
| `replicas: 1` | Users, rooms and accounts live in the server's memory. Two Pods would be two separate chat servers, and users on different Pods couldn't see each other. Scaling out needs shared state first (for example Redis pub/sub for messages and a database for accounts). |
| `strategy: Recreate` | During an update the old Pod stops before the new one starts, so there are never two chat servers with split users. The cost: a few seconds of downtime on each update. |
| `resources` requests / limits | Requests (100m CPU, 128 Mi) are what the scheduler reserves; limits (1 CPU, 256 Mi) are hard caps. The JVM sizes its heap to 75% of the memory limit (`-XX:MaxRAMPercentage=75` in the Dockerfile). |
| `runAsNonRoot`, `runAsUser: 10001`, drop all capabilities, no privilege escalation, read-only root filesystem (`/tmp` is a small writable volume) | A bug in the server can't act as root or change the container's files. |
| `imagePullPolicy: IfNotPresent` | The image is built locally and copied into the node with `kind load docker-image`; there is no registry to pull from. |

## What a restart costs

When the Pod is replaced, every open connection drops and every registered account is gone,
because they lived in the old Pod's memory. That is the honest limit of this design, and the
reason `replicas` is 1. Persistence would fix the accounts; reconnect logic in the client would
make a restart less visible to users.
