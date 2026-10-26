# Runbook: chat server

What to check when an alert fires. Step 5 adds measured baselines from load tests.

Where to look: Grafana `http://localhost:3000` (dashboard "Chat server"),
Prometheus `http://localhost:9090/alerts`, logs with `docker compose logs chat`.

## Latency is high

Alert `ChatHighMessageLatency`: p95 server handling time above 50 ms for 2 minutes.

1. **Is it load?** Dashboard "Message rate by type" and "Open connections". A jump in either
   that lines up with the latency rise means the server is simply busy.
2. **Is one room huge?** Handling time includes writing the message to every recipient in the
   room, so one big room makes every message in it slower. `/who` in that room shows its size.
3. **Is the machine starved?** `docker stats`: CPU or memory of the `chat` container near its
   limit (the JVM uses up to 75% of the container's memory, then garbage collects harder).
4. **Is one client stuck?** A client that stops reading fills its TCP buffer, and writes to it
   block the sender's thread.
5. If nothing explains it, restart: `docker compose restart chat`. Connected users are dropped
   and must log in again; accounts are kept in memory, so they are lost too.

## Server down

Alert `ChatServerDown`: Prometheus could not scrape `/metrics` for 30 seconds.

1. `docker compose ps`: is the `chat` container running or restarting?
2. `docker compose logs --tail 50 chat`: exceptions, `Address already in use`, out of memory.
3. `curl localhost:9100/healthz` and `curl localhost:9100/readyz` from this machine.
4. If the container keeps restarting, run the image by hand to see the error:
   `docker run --rm -p 5000:5000 -p 9100:9100 chat-server:local`.
