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
├── EchoServer.java
│   └── v1: Basic single-client echo server
│
├── ChatServer.java
│   └── Main server
│
├── ClientHandler.java
│   └── Client authentication, messaging and rooms
│
├── ChatClient.java
│   └── Console client
│
└── v2-only/
    ├── ChatServer.java
    └── ClientHandler.java
```

The `v2-only` directory contains an earlier version of the project before usernames and rooms were introduced.

---

## Running the Project

### Requirements

* JDK 17 or later
* Terminal
* Linux/WSL/Windows terminal

The project was developed and tested using **JDK 21**.

### Start the Server

```bash
cd src

javac ChatServer.java ClientHandler.java

java ChatServer
```

You should see:

```text
Chat server listening on port 5000 ...
```

### Start the Java Client

Open another terminal:

```bash
cd src

javac ChatClient.java

java ChatClient
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
* Write automated integration tests

The biggest architectural improvement would probably be moving from **one thread per client** to **non-blocking I/O**, which would make the server more scalable with a large number of simultaneous connections.

---

## Acknowledgements

This project was developed as a **summer internship project under the guidance of Prof. Pawan Kumar, Department of Mathematics, IIT Kharagpur**.

The project was built primarily as a hands-on exercise to understand Java networking, multithreading, and client-server architecture from the ground up.

