# Multi-Client Chat Server in Java

A simple **multi-client chat server built from scratch in Java** using raw TCP sockets and Java multithreading.

I built this project as a **summer internship project under Prof. Pawan Kumar, Department of Mathematics, IIT Kharagpur**, mainly to understand how networked applications actually work at a lower level rather than relying on frameworks.

The project started as a basic echo server and gradually evolved into a chat application with multiple clients, authentication, private messaging, and chat rooms.

---

## What I Built

The server can handle multiple clients at the same time. Each connected client gets its own `ClientHandler` running on a separate thread, while the main server thread is responsible only for accepting new connections.

The current version supports:

* Multiple clients chatting simultaneously
* Unique usernames
* User registration and login
* Password-based authentication with a 3-attempt limit
* Chat rooms
* Room-specific broadcasting
* Private messages
* Joining and leaving rooms
* Listing users in the current room
* A custom Java console client with a separate listener thread

The goal was not to build a production-ready messaging application, but to understand the fundamentals behind **TCP networking, concurrency, synchronization, and client-server architecture**.

---

## How It Works

The basic architecture looks like this:

```text
                         ChatServer
                       ServerSocket
                            |
              +-------------+-------------+
              |             |             |
              v             v             v
        ClientHandler  ClientHandler  ClientHandler
             A              B              C
          Thread           Thread         Thread
              |             |              |
           Client A       Client B       Client C
```

### `ChatServer`

The main server:

* Opens a `ServerSocket`
* Listens on port `5000`
* Accepts incoming connections
* Creates a new `ClientHandler` for every client
* Starts a new thread for that handler

The important part is that the server itself doesn't sit around waiting for one client to finish chatting. It immediately goes back to `accept()` and can handle new connections.

### `ClientHandler`

Every connected client has its own handler.

It takes care of:

* Username registration/login
* Reading messages
* Broadcasting messages
* Private messaging
* Room management
* `/who`
* `/join`
* `/leave`
* Disconnecting the client cleanly

Each handler also keeps track of the client's current room.

### `ChatClient`

I also wrote a small console client instead of relying entirely on `netcat` or `telnet`.

The client uses two threads:

```text
Main Thread
    |
    +--> Reads keyboard input
    |
Listener Thread
    |
    +--> Continuously reads messages from server
```

This means incoming messages can appear immediately even when the user is typing.

---

## Features

### 1. Multiple Clients

The server follows a **thread-per-client** approach.

For example:

```text
Client A -> Thread A
Client B -> Thread B
Client C -> Thread C
Client D -> Thread D
```

This allowed me to experiment with concurrent socket communication using Java's built-in threading tools.

---

### 2. Usernames and Authentication

When a user connects, they provide a username.

If the username doesn't exist:

```text
New username -- set a password to register
```

The account is created.

If the username already exists, the user has to provide the correct password.

There is also a **3-attempt retry limit** before the connection is closed.

Duplicate usernames are prevented using synchronized check-and-reserve logic so that two clients connecting at almost the same time cannot claim the same username.

---

### 3. Chat Rooms

Users can move between rooms using:

```text
/join dev-team
```

Messages are only broadcast to users currently in the same room.

For example:

```text
general
  ├── Atharv
  └── Rahul

dev-team
  ├── Kiyo
  └── Aman
```

A message sent by Atharv in `general` will not be received by users in `dev-team`.

Rooms don't have their own separate data structure. Each `ClientHandler` simply stores its current room:

```text
currentRoom = "general"
```

When broadcasting, the server checks the current room of each connected client.

This keeps the implementation relatively simple and gives the server a single source of truth for room membership.

---

### 4. Private Messaging

Users can send a private message using:

```text
/msg username hello
```

The message is sent directly to that user's socket and doesn't depend on whether both users are in the same room.

---

### 5. Room Commands

Available commands:

| Command              | What it does                      |
| -------------------- | --------------------------------- |
| `/join <room>`       | Join or switch to a room          |
| `/leave`             | Return to the `general` room      |
| `/who`               | Show users currently in your room |
| `/msg <user> <text>` | Send a private message            |
| `quit`               | Disconnect from the server        |

Normal text messages are broadcast to everyone else in the current room.

---

## Tech Stack

* **Java 21**
* `java.net.Socket`
* `java.net.ServerSocket`
* `java.io.BufferedReader`
* `java.io.PrintWriter`
* `java.lang.Thread`
* `CopyOnWriteArrayList`
* `ConcurrentHashMap`
* `synchronized`

No external networking or messaging framework is used.

Everything is implemented using Java's standard library.

---

## Project Structure

```text
src/
├── EchoServer.java        v1: basic single-client echo server
├── ChatServer.java        main server; also starts the metrics endpoint
├── ClientHandler.java     authentication, messaging and rooms
├── ChatClient.java        console client
├── Metrics.java           counters, gauges and a latency histogram (Prometheus format)
└── MetricsServer.java     HTTP endpoint: /metrics, /healthz, /readyz
test/
└── ChatServerTest.java    automated tests: starts the real server and connects real clients
tools/
└── LoadTest.java          traffic generator measuring end-to-end latency
monitoring/                Prometheus config + alert rules (with tests), Grafana provisioning + dashboard
scripts/                   docker-smoke.sh, monitoring-up.sh
docs/RUNBOOK.md            what to check when an alert fires
Dockerfile, docker-compose.yml
```

---

## Running the Project

### Requirements

* JDK 17 or later
* Terminal
* Linux/WSL/Windows terminal

The project was developed and tested using **JDK 21**.

### Start the Server

```bash
javac -d out src/*.java
java -cp out ChatServer
```

You should see:

```text
Chat server listening on port 5000 ...
```

Metrics are served on port 9100 (`curl localhost:9100/metrics`, see **Monitoring** below).

### Start the Java Client

Open another terminal:

```bash
java -cp out ChatClient                    # or: java -cp out ChatClient <host> <port>
```

Open multiple terminals to connect multiple clients.

---

## Testing with Netcat

You can also connect without the custom Java client:

```bash
nc -v localhost 5000
```

This is useful for quickly testing the server and its protocol.

For a better terminal experience on Linux/WSL:

```bash
rlwrap nc -v localhost 5000
```

---

## Example

A client might see:

```text
Enter your username: atharv

New username -- set a password to register: ****

Account created. Welcome, atharv!

kiyo has joined the chat

/join dev-team

You are now in room: dev-team

/msg kiyo hey, meet in dev-team

[PM to kiyo]: hey, meet in dev-team

/who

Users in room 'dev-team': atharv (you)
```

---

## Development Journey

I didn't build everything at once. The project was developed incrementally, with each version being compiled and tested before moving to the next feature.

### v1 — Echo Server

Started with a basic single-client TCP echo server.

The purpose was simply to understand:

* `ServerSocket`
* `Socket`
* Input/output streams
* The basic client-server communication model

### v2 — Multiple Clients

Added the thread-per-client architecture.

```text
Server
 |
 +-- ClientHandler 1
 +-- ClientHandler 2
 +-- ClientHandler 3
```

This was the first step toward concurrent communication.

### v3 — Usernames

Added usernames and protection against duplicate usernames.

### v4 — Private Messaging

Added:

```text
/msg <user> <message>
```

### v5 — Chat Rooms

Added:

```text
/join <room>
/who
```

and changed broadcasting so that messages stay within the current room.

### v6 — Leave Command

Added:

```text
/leave
```

as a shortcut for returning to the `general` room.

### Authentication

Finally, login and registration were added on top of the existing system.

---

## Testing

I manually tested the server using multiple concurrent terminal sessions, including **5+ simultaneous clients**.

Some of the cases tested were:

* Multiple clients connecting simultaneously
* Duplicate username attempts
* Login failures
* Login retry limits
* Room switching
* Room-specific broadcasting
* Private messages
* Messages sent to users who don't exist
* Malformed commands
* Client disconnections

### Automated tests

`test/ChatServerTest.java` starts the real server on free ports and connects real clients
(28 checks, no test framework needed):

```bash
javac -d out src/*.java test/*.java && java -ea -cp out ChatServerTest
```

---

## A Note About Security

This project is primarily for learning and demonstration.

Passwords are currently stored in an in-memory `ConcurrentHashMap` in plaintext.

**This is not suitable for a real production application.**

A production implementation should use:

* Salted password hashing
* TLS/HTTPS-style encrypted communication
* Persistent database storage
* Proper session management
* Input validation and rate limiting

The current implementation intentionally keeps these parts simple so that the networking and concurrency concepts remain easy to understand.

---

## What I Learned

This project gave me practical experience with several concepts that are difficult to fully understand just from theory:

* How TCP client-server communication works
* How sockets handle input/output
* Why blocking I/O can become a problem
* How multiple clients can be handled concurrently
* Race conditions and synchronization
* Thread-safe collections
* Designing a simple application-level protocol
* Separating server responsibilities from client responsibilities
* Managing shared state between concurrent threads

One of the main things I learned was that even a seemingly simple application like a chat server quickly introduces interesting problems around **concurrency and shared state**.

---

## Monitoring (Prometheus metrics)

Added for observability practice: the server now exposes Prometheus metrics, liveness and
readiness endpoints, with no extra dependencies.

```bash
curl localhost:9100/metrics
curl localhost:9100/healthz             # liveness  (used by Kubernetes later)
curl localhost:9100/readyz              # readiness: 200 once the chat port is bound
```

Settings (environment variables): `CHAT_PORT` (5000), `METRICS_PORT` (9100),
`CHAT_LOG_MESSAGES` (`true`; set `false` under load so console printing doesn't
distort latency).

### Metrics

| Metric | Type | What it answers |
|---|---|---|
| `chat_active_connections` | gauge | How many sockets are open right now (including clients still logging in)? |
| `chat_logged_in_users` | gauge | How many people can actually chat? |
| `chat_connections_accepted_total` | counter | Connection rate: `rate(...[1m])` |
| `chat_messages_processed_total{type}` | counter | Message rate by kind: broadcast, private, join, leave, who, quit |
| `chat_message_handling_seconds` | histogram | p50/p95 handling time: `histogram_quantile(0.95, rate(..._bucket[5m]))` |
| `chat_username_rejections_total` | counter | How often a username collision is refused |
| `chat_login_failures_total` | counter | Logins dropped after 3 wrong passwords (a spike can mean password guessing) |
| `process_start_time_seconds` | gauge | Detects restarts |

**What the latency histogram measures:** from the moment the server has read a full
line to when it has finished writing it to every recipient. That is the server's own
work, including fan-out to everyone in the room. It does not include network time,
which the server can't see; the load-test client in step 5 measures end to end.

**Why hand-written instead of the Prometheus Java client:** the code has no build tool
yet, and this keeps it at zero dependencies. The format is the standard text exposition
format, checked with the official Python parser (`prometheus_client.parser`). Swapping
in `io.prometheus:prometheus-metrics-core` later only touches `Metrics.java`.

### Changes to the chat code

All marked in the source:

- `[metrics]` lines in `ClientHandler` and `ChatServer`; the message `if`-chain moved
  into `handleLine()` so each line can be timed and labelled.
- `[fix]` **Broadcast reached clients that hadn't logged in.** `broadcastToRoom` wrote to
  every handler in the list, including clients still at the username/password prompt.
  A client whose thread hadn't set `out` yet caused a `NullPointerException` in the
  *sender's* thread, which disconnected the sender. It now only writes to logged-in
  clients. The test `client at the login prompt does not receive chat` fails on the
  old code and passes on the new.
- `[fix]` `out`, `username`, `currentRoom` are `volatile`: they are written by one
  client's thread and read by others (broadcast, `/who`, `/msg`).
- `[fix]` "has left the chat" is only announced for users who had joined; `/msg` can't
  target someone still at the password prompt.
- Ports come from environment variables so Docker and Kubernetes can set them.

---

## Run it in Docker

```bash
docker build -t chat-server:local .        # compiles AND runs the 28 tests; no image if a test fails
docker run --rm -p 5000:5000 -p 9100:9100 chat-server:local
java -cp out ChatClient localhost 5000     # from another terminal (host and port are optional)
curl localhost:9100/metrics
./scripts/docker-smoke.sh                  # builds, starts, checks from outside, cleans up
```

How the image is built (`Dockerfile`):

| Choice | Why |
|---|---|
| Two stages: JDK to build, JRE to run | The build stage compiles and **runs the test suite**; the final image ships only the JRE and the compiled server classes (no compiler, no tests, no source). |
| `eclipse-temurin:21` (Ubuntu Noble) | Official OpenJDK builds; Java 21 is the current LTS. |
| Runs as uid 10001, not root | A bug in the server can't act as root inside the container. Kubernetes can enforce this later with `runAsNonRoot`. |
| `-XX:MaxRAMPercentage=75` | The JVM sizes its heap from the container's memory limit, not the whole machine's. |
| `CHAT_LOG_MESSAGES=false` | Printing every chat line to the console slows the server under load and floods `docker logs`. |
| `.dockerignore` | Keeps `.git`, local builds and docs out of the image and the build context. |

`scripts/docker-smoke.sh` checks the running container from the outside: `/healthz` and `/readyz`
return 200, `/metrics` shows `chat_active_connections 0`, a real TCP connection moves it to 1,
and the process runs as uid 10001.

---

## Monitoring with Prometheus and Grafana

```bash
./scripts/monitoring-up.sh      # starts chat + Prometheus + Grafana, sends 60 s of traffic, checks everything
docker compose down             # stop
```

| Service | Address | What's there |
|---|---|---|
| Chat server | `localhost:5000`, metrics on `localhost:9100/metrics` | the server, built from the `Dockerfile` |
| Prometheus 3.5.0 | http://localhost:9090 | scrapes the server every 5 s, evaluates the alert rules |
| Grafana 12.1.1 | http://localhost:3000 | the **Chat server** dashboard, set as the home page; viewing needs no login |

All ports are bound to `127.0.0.1`, so nothing is reachable from other machines. Grafana's data
source and dashboard are provisioned from files in `monitoring/grafana/`, so a fresh
`docker compose up` always has them, with nothing to click through.

**Dashboard** (`monitoring/grafana/dashboards/chat-server.json`): server up, open connections,
logged-in users, messages per second, p95 handling time and firing alerts at the top; below,
message rate by type, p50/p95/p99 handling time, connections over time, and login problems
(username collisions, logins dropped after 3 wrong passwords).

**Alert rules** (`monitoring/prometheus/alerts.yml`), each pointing to a section of
[`docs/RUNBOOK.md`](docs/RUNBOOK.md):

| Alert | Fires when | Why that threshold |
|---|---|---|
| `ChatHighMessageLatency` | p95 server handling time > 50 ms for 2 min | normal is far below 1 ms; 50 ms means something is wrong, and 2 min avoids paging on one burst. No traffic means no alert. |
| `ChatServerDown` | Prometheus can't scrape `/metrics` for 30 s | 6 failed scrapes in a row, not one slow one |

The rules have unit tests (`monitoring/prometheus/alerts.test.yml`): fast traffic doesn't fire,
slow traffic fires only after 2 minutes, an idle server stays quiet, and the down alert waits 30
seconds. Run them with Prometheus's `promtool`:

```bash
docker run --rm -v "$PWD/monitoring/prometheus:/p" --entrypoint promtool prom/prometheus:v3.5.0 test rules /p/alerts.test.yml
```

**Traffic generator** (`tools/LoadTest.java`): opens real client connections, registers users,
puts them in rooms and sends timed messages, measuring end-to-end latency from send to receipt
by the other clients in the room. Step 5 uses it for the load test.

```bash
javac -d out src/*.java tools/*.java
java -cp out LoadTest localhost 5000 20 4 50 60   # host port clients rooms msg/s seconds
```

---

## Future Improvements

There are several directions I would like to take this project further:

* Replace thread-per-client with Java NIO and a `Selector`-based architecture
* Add persistent storage for users and chat history
* Add file transfer
* Improve authentication using password hashing
* Add TLS encryption
* Build a GUI client using JavaFX or Swing
* Add message timestamps and chat history
* Add better error handling and logging
* Extend the automated tests (load and failure scenarios)

The biggest architectural improvement would probably be moving from **one thread per client** to **non-blocking I/O**, which would make the server more scalable with a large number of simultaneous connections.

---

## Acknowledgements

This project was developed as a **summer internship project under the guidance of Prof. Pawan Kumar, Department of Mathematics, IIT Kharagpur**.

The project was built primarily as a hands-on exercise to understand Java networking, multithreading, and client-server architecture from the ground up.

