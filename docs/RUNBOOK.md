# Runbook: chat server

What to check when an alert fires or users say chat is slow.

Where to look: Grafana `http://localhost:3000` (dashboard "Chat server"),
Prometheus `http://localhost:9090/alerts`, logs with `docker compose logs chat`
(on Kubernetes: `kubectl -n chat logs deploy/chat-server`).

## What normal looks like

Measured with `scripts/load-matrix.sh` on an i5-10210U laptop (details: [LOADTEST.md](LOADTEST.md)).
Up to 4,500 deliveries per second (messages per second × people in the room minus one):

| Metric | Normal | Worry |
|---|---|---|
| Server handling time (dashboard "p95 handling time") | p95 < 1 ms, p99 < 5 ms | p95 > 50 ms for 2 min (the alert) |
| End-to-end latency (`LoadTest`) | median 1–2 ms | median above 100 ms |
| Chat container memory | 60–110 MiB | still rising after load stops |
| Chat container CPU (`docker stats`) | 1–2 cores | about 3 cores: that is where this laptop saturated (19,000 deliveries/s) |

On this laptop, single stalls of up to about 2 s happen even at normal load while a load generator
runs on the same machine (see LOADTEST.md, finding 6). One stall is not an incident; a p95 rise that
lasts minutes is.

## Latency is high

Alert `ChatHighMessageLatency`: p95 server handling time above 50 ms for 2 minutes.

1. **Is it load?** Dashboard "Message rate by type" and "Open connections". Multiply messages
   per second by the room size: past roughly 4,500 deliveries per second this laptop slows down,
   and at 19,000 the median wait reached 457 ms. If the rise lines up with more traffic, the server
   is overloaded, not broken.
2. **Is one room huge?** Handling time includes writing the message to every recipient in the
   room, so one big room makes every message in it slower. `/who` in that room shows its size.
3. **Is the machine starved?** `docker stats`: chat container near 3 cores, or memory near its limit.
   `cat /proc/pressure/cpu`: a high `some avg60` means processes are waiting for a CPU.
4. **Is it garbage collection?** Restart with GC logging, `CHAT_JAVA_OPTS="-Xlog:gc" docker compose up -d chat`,
   then `docker compose logs chat | grep Pause`. Pauses of hundreds of ms line up with GC; in the
   load tests there was one 79 ms pause, so GC was not the cause there.
5. **Is one client stuck?** A client that stops reading fills its TCP buffer, and writes to it
   block the thread of whoever is sending into that room. Handling time then rises for that room only.
6. If nothing explains it, restart: `docker compose restart chat` (Kubernetes:
   `kubectl -n chat rollout restart deployment/chat-server`). Connected users are dropped and
   accounts are lost, because they live in memory.

## Server down

Alert `ChatServerDown`: Prometheus could not scrape `/metrics` for 30 seconds.

1. `docker compose ps`: is the `chat` container running or restarting?
   (Kubernetes: `kubectl -n chat get pods`; the Deployment replaced a deleted Pod in 6 s in testing.)
2. `docker compose logs --tail 50 chat`: exceptions, `Address already in use`, out of memory.
3. `curl localhost:9100/healthz` and `curl localhost:9100/readyz` from this machine.
4. If the container keeps restarting, run the image by hand to see the error:
   `docker run --rm -p 5000:5000 -p 9100:9100 chat-server:local`.
