import java.io.*;
import java.net.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v5: v4c + Prometheus metrics (lines marked [metrics]) and a fix for
 * broadcasting to clients that have not finished logging in (marked [fix]).
 *
 * v4c: Handles ONE client's connection on its own dedicated thread.
 * Adds a basic register/login step (PLAINTEXT, in-memory only -- teaching
 * purposes, not secure) on top of room-scoped broadcast chat, private
 * messaging, room switching, and "/who".
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final List<ClientHandler> allClients;
    // volatile: these are written by this client's thread and read by OTHER
    // clients' threads (broadcast, /who, /msg). Without volatile, another thread
    // may see a stale value. [fix]
    private volatile PrintWriter out;
    private volatile String username;
    private volatile String currentRoom = "general"; // everyone starts in the same default room
    private volatile boolean loggedIn = false; // [fix] only logged-in clients receive chat

    // Shared across ALL ClientHandler instances/threads -- used to make
    // "check if username taken" + "reserve it" a single atomic operation.
    private static final Object userLock = new Object();

    // Registered accounts: username -> password (PLAINTEXT -- teaching only,
    // never do this in a real system). Lives only in memory: resets on server
    // restart since we have no persistent storage yet.
    private static final Map<String, String> registeredUsers = new ConcurrentHashMap<>();
    private static final int MAX_LOGIN_ATTEMPTS = 3;

    // Printing every chat line to stdout is fine for a demo, but under load the
    // console becomes the bottleneck and distorts the latency numbers.
    // CHAT_LOG_MESSAGES=false turns it off (the load test does this).
    private static final boolean LOG_MESSAGES =
            !"false".equalsIgnoreCase(System.getenv().getOrDefault("CHAT_LOG_MESSAGES", "true"));

    private boolean counted = false; // [metrics] did we inc ACTIVE_CONNECTIONS?

    public ClientHandler(Socket socket, List<ClientHandler> allClients) {
        this.socket = socket;
        this.allClients = allClients;
    }

    public String getUsername() {
        return username;
    }

    @Override
    public void run() {
        try (
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            this.out = writer; // stored so broadcast() can reach this client too
            Metrics.ACTIVE_CONNECTIONS.inc(); // [metrics] dec in disconnect()
            counted = true;

            // --- One-time handshake: get a unique username before any chat logic ---
            while (true) {
                out.println("Enter your username:");
                String candidate = in.readLine();

                if (candidate == null) {
                    // Client disconnected before ever choosing a username.
                    return;
                }
                candidate = candidate.trim();

                if (candidate.isBlank()) {
                    out.println("Username cannot be empty. Try again.");
                    continue;
                }

                // Atomic check-and-reserve: only one thread can be in here at a time,
                // so two people can never both "win" the same username.
                synchronized (userLock) {
                    if (isUsernameTaken(candidate)) {
                        Metrics.USERNAME_REJECTIONS.inc(); // [metrics]
                        out.println("Username '" + candidate + "' is already taken. Try another.");
                        continue;
                    }
                    username = candidate; // reserved -- no one else can take it now
                }
                break;
            }
            System.out.println(username + " connected from " + socket.getInetAddress());

            // --- Login / registration step ---
            if (!authenticate(in)) {
                Metrics.LOGIN_FAILURES.inc(); // [metrics]
                out.println("Too many failed attempts. Disconnecting.");
                return; // finally block still runs disconnect() cleanup
            }

            loggedIn = true;
            Metrics.LOGGED_IN_USERS.inc(); // [metrics] dec in disconnect()

            // Tell everyone else (not this new client) that someone joined.
            broadcast(username + " has joined the chat", this);

            String line;
            while ((line = in.readLine()) != null) {
                // [metrics] Start the clock as soon as the line is read. The
                // histogram measures OUR handling time (parsing + writing to every
                // recipient), not network time, which the server can't see.
                long started = System.nanoTime();
                if (LOG_MESSAGES) System.out.println("[" + username + "] " + line);

                String type = handleLine(line);
                Metrics.MESSAGES_PROCESSED.inc(type); // [metrics]
                Metrics.MESSAGE_LATENCY.observeSince(started); // [metrics]

                if (type.equals("quit")) {
                    break;
                }
            }
        } catch (IOException e) {
            System.out.println("Connection error: " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    // Dispatches one chat line and returns its type, used as the metric label.
    // (Same logic as v4c's if-chain, moved into a method so it can be timed.)
    private String handleLine(String line) {
        if (line.equalsIgnoreCase("quit")) {
            return "quit";
        }
        if (line.startsWith("/msg ")) {
            handlePrivateMessage(line);
            return "private";
        }
        if (line.startsWith("/join ")) {
            handleJoinRoom(line);
            return "join";
        }
        if (line.equalsIgnoreCase("/who")) {
            handleWho();
            return "who";
        }
        if (line.equalsIgnoreCase("/leave")) {
            handleLeaveRoom();
            return "leave";
        }
        // Exclude the sender so they don't see their own message echoed back.
        broadcast(username + ": " + line, this);
        return "broadcast";
    }

    // Handles login (existing account) or registration (new account) for
    // the already-reserved `username`. Returns false if login ultimately fails.
    private boolean authenticate(BufferedReader in) throws IOException {
        if (registeredUsers.containsKey(username)) {
            // --- Existing account: verify password ---
            for (int attempt = 1; attempt <= MAX_LOGIN_ATTEMPTS; attempt++) {
                out.println("Enter password:");
                String password = in.readLine();
                if (password == null) {
                    return false; // client disconnected mid-login
                }

                if (registeredUsers.get(username).equals(password)) {
                    out.println("Login successful. Welcome back, " + username + "!");
                    return true;
                }

                out.println("Incorrect password. Attempts remaining: "
                        + (MAX_LOGIN_ATTEMPTS - attempt));
            }
            return false; // ran out of attempts
        } else {
            // --- New account: register a password ---
            out.println("New username -- set a password to register:");
            String password = in.readLine();
            if (password == null || password.isBlank()) {
                out.println("Registration cancelled (empty password).");
                return false;
            }
            registeredUsers.put(username, password);
            out.println("Account created. Welcome, " + username + "!");
            return true;
        }
    }

    // Parses "/join <roomname>" and moves this client from their current room
    // to the new one, announcing the departure/arrival to each room's members.
    private void handleJoinRoom(String line) {
        String[] parts = line.split(" ", 2);

        if (parts.length < 2 || parts[1].isBlank()) {
            out.println("Usage: /join <roomname>");
            return;
        }

        String newRoom = parts[1].trim();

        if (newRoom.equalsIgnoreCase(currentRoom)) {
            out.println("You are already in room: " + currentRoom);
            return;
        }

        String oldRoom = currentRoom;
        broadcastToRoom(oldRoom, username + " has left the room", this);

        currentRoom = newRoom;

        broadcastToRoom(currentRoom, username + " has joined the room", this);
        out.println("You are now in room: " + currentRoom);
    }

    // Convenience shortcut for "/join general" -- returns the client to the
    // default room without them needing to remember/type the room name.
    private void handleLeaveRoom() {
        handleJoinRoom("/join general");
    }

    // Lists every connected user currently sharing this client's room.
    private void handleWho() {
        StringBuilder sb = new StringBuilder("Users in room '" + currentRoom + "': ");
        boolean first = true;
        for (ClientHandler client : allClients) {
            if (client.loggedIn && client.currentRoom.equals(currentRoom)) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(client.username);
                if (client == this) {
                    sb.append(" (you)");
                }
                first = false;
            }
        }
        out.println(sb.toString());
    }

    // Parses "/msg <username> <message>" and routes it to only that user.
    private void handlePrivateMessage(String line) {
        // line looks like: "/msg kiyo hey there"
        // Split into at most 3 parts: "/msg", "kiyo", "hey there"
        String[] parts = line.split(" ", 3);

        if (parts.length < 3) {
            out.println("Usage: /msg <username> <message>");
            return;
        }

        String targetName = parts[1];
        String messageText = parts[2];

        ClientHandler target = findClientByUsername(targetName);

        if (target == null || !target.loggedIn) { // [fix] not to users still at the password prompt
            out.println("User '" + targetName + "' not found or offline.");
            return;
        }

        target.out.println("[PM from " + username + "]: " + messageText);
        out.println("[PM to " + targetName + "]: " + messageText); // confirmation to self
    }

    // Linear search for a connected client by username.
    // Fine for small user counts -- we'd switch to a Map<String, ClientHandler>
    // if this list grew large, but clarity wins for now.
    private ClientHandler findClientByUsername(String targetName) {
        for (ClientHandler client : allClients) {
            if (client.username != null && client.username.equalsIgnoreCase(targetName)) {
                return client;
            }
        }
        return null;
    }

    // Checks whether a candidate username matches any ALREADY-CONNECTED client.
    // Must only be called while holding userLock (see the handshake loop above).
    private boolean isUsernameTaken(String candidate) {
        return findClientByUsername(candidate) != null;
    }

    // Sends a message to every client in `room` EXCEPT the given exclude target.
    // Pass null as exclude to include everyone in that room (e.g. announcements
    // where there's no single "sender" to skip).
    private void broadcastToRoom(String room, String message, ClientHandler exclude) {
        for (ClientHandler client : allClients) {
            // [fix] v4c also wrote to clients still at the username/password
            // prompt, and to clients whose thread had not set `out` yet (a
            // NullPointerException in the SENDER's thread). Skip both.
            if (client != exclude && client.loggedIn && client.currentRoom.equals(room)) {
                client.out.println(message);
            }
        }
    }

    // Convenience wrapper: broadcasts within the SENDER's current room.
    private void broadcast(String message, ClientHandler sender) {
        broadcastToRoom(sender.currentRoom, message, sender);
    }

    // Removes this client from the shared list, closes the socket,
    // and tells everyone remaining that this user left.
    private void disconnect() {
        allClients.remove(this);
        if (counted) Metrics.ACTIVE_CONNECTIONS.dec(); // [metrics]
        boolean wasLoggedIn = loggedIn;
        if (wasLoggedIn) {
            loggedIn = false;
            Metrics.LOGGED_IN_USERS.dec(); // [metrics]
        }
        try {
            socket.close();
        } catch (IOException e) {
            // ignore -- socket is going away regardless
        }
        if (wasLoggedIn) { // [fix] don't announce users who never finished logging in
            broadcast(username + " has left the chat", this);
        }
        System.out.println("Disconnected: " + username
                + " (active clients: " + allClients.size() + ")");
    }
}
