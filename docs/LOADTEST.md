# Load test results

Measured on 27 October 2026 with `scripts/load-matrix.sh` (60 s per level) against the docker
compose stack. Machine: Intel Core i5-10210U @ 1.60 GHz (4 cores, 8 threads), Windows laptop with
8 GB RAM, of which WSL 2 (Ubuntu 24.04) sees 3 GiB. The load generator, the chat server (in Docker)
and Prometheus/Grafana all ran on this one laptop, so they competed for the same CPU.

## Results

| Clients | Rooms | Target msg/s | Deliveries/s (msgs × others in room) | Delivered / expected | Errors | End-to-end p50 / p95 / p99 / max (ms) | Server p95 / p99 (ms) | Peak CPU (chat container) | Peak memory |
|---|---|---|---|---|---|---|---|---|---|
| 20 | 4 | 50 | 200 | 12,000 / 12,000 | 0 | 1.31 / 1.98 / 4.02 / 44.49 | 0.49 / 0.95 | 111% | 59.32 MiB |
| 100 | 10 | 200 | 1,800 | 107,928 / 107,928 | 0 | 1.55 / 271.77 / 687.74 / 2,040.17 | 5.50 / 221.73 | 108% | 84.87 MiB |
| 200 | 20 | 500 | 4,500 | 269,964 / 269,964 | 0 | 1.19 / 10.67 / 45.29 / 534.47 | 0.86 / 3.89 | 170% | 108.2 MiB |
| 500 | 25 | 1,000 | 19,000 | 1,139,601 / 1,139,601 | 0 | 457.03 / 8,078.45 / 12,462.63 / 19,211.73 | 789.49 / ≥ 1,000 | 293% | 178.8 MiB |

- **End-to-end** (`tools/LoadTest.java`): from send to receipt at every other client in the room,
  through the real TCP stack and Docker's port forwarding.
- **Server** (Prometheus, `chat_message_handling_seconds`): the server's own time per message, from
  reading the line to finishing the write to every recipient. Bucket-based, so approximate; "≥ 1,000"
  means the value is above the largest bucket (1 s).
- **Peak CPU** from `docker stats`; 100% is one full core.

Level 2 was repeated, because its tail was worse than level 3's:

| Level 2 run | End-to-end p50 / p95 / p99 / max (ms) |
|---|---|
| in the matrix | 1.55 / 271.77 / 687.74 / 2,040.17 |
| repeat 1 | 1.54 / 3.04 / 68.01 / 389.22 |
| repeat 2 | 1.37 / 17.84 / 102.07 / 1,010.25 |
| repeat 3, with GC logging | 1.72 / 100.30 / 435.32 / 1,526.72 |

## Findings

1. **No message was lost at any level.** All 1.5 million deliveries arrived, with 0 errors.
2. **Up to 4,500 deliveries per second the median stays at 1.2–1.6 ms.** That is level 3: 200
   clients sending 500 messages per second in rooms of 10.
3. **Level 4 is past this laptop's limit.** 19,000 deliveries per second (rooms of 20) used about
   3 cores in the chat container alone, and messages queued: median 457 ms, p95 8 s. They were late,
   not lost. If that lasted 2 minutes, the `ChatHighMessageLatency` alert (server p95 > 50 ms) would
   fire, which is the intended behaviour.
4. **The median is stable from run to run, but the tail is not.** Four runs of level 2 gave a p95
   of 3, 18, 100 and 272 ms, with the median between 1.37 and 1.72 ms. Every run had a few
   stalls of 0.4–2 s.
5. **The stalls are not garbage collection.** With GC logging on (repeat 3), the JVM paused once,
   for 79 ms, during a run whose worst message took 1,527 ms. CPU pressure inside WSL was low
   (`/proc/pressure/cpu`: tasks waited for a CPU 3% of the last minute, 5% of the last 5 minutes).
6. **Most likely cause, not proven: the load generator shares the laptop.** The server's
   handling time includes waiting for each recipient to read: if the load-test client, which runs
   one reader thread per connection, falls behind, the server's writes to it slow down too (the
   first level-2 run had a server p99 of 222 ms). Windows can also pause the whole WSL virtual
   machine, which nothing inside Linux can see. **Next step:** run the load generator on a
   different machine, and record GC and Linux scheduler statistics over the same window.

## What "normal" looks like (used by the runbook)

At up to 4,500 deliveries per second on this laptop:

| Metric | Normal |
|---|---|
| Server handling time | p95 under 1 ms, p99 under 5 ms |
| End-to-end latency | median 1–2 ms; occasional single stalls of up to about 2 s on this shared machine |
| Memory (chat container) | 60–110 MiB |
| CPU (chat container) | 1–2 cores |

The alert threshold (server p95 > 50 ms for 2 minutes) is far above normal, and level 4 crossed it.
That is the gap between "busy" and "overloaded".

## Reproduce

```bash
./scripts/load-matrix.sh                                     # the four levels; rewrites the raw file
java -cp out LoadTest localhost 5000 100 10 200 60           # one level by hand
CHAT_JAVA_OPTS="-Xlog:gc" docker compose up -d chat          # server with GC logging
docker compose logs chat | grep "Pause"                      # GC pauses
```

Raw `RESULT` lines from the matrix run: [`loadtest-results.txt`](loadtest-results.txt).
