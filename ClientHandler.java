import java.io.*;
import java.net.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v4c: Handles ONE client's connection on its own dedicated thread.
 * Adds a basic register/login step (PLAINTEXT, in-memory only -- teaching
 * purposes, not secure) on top of room-scoped broadcast chat, private
 * messaging, room switching, and "/who".
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final List<ClientHandler> allClients;
    private PrintWriter out;
    private String username;
    private String currentRoom = "general"; // everyone starts in the same default room

    // Shared across ALL ClientHandler instances/threads -- used to make
    // "check if username taken" + "reserve it" a single atomic operation.
    private static final Object userLock = new Object();

    // Registered accounts: username -> password (PLAINTEXT -- teaching only,
    // never do this in a real system). Lives only in memory: resets on server
    // restart since we have no persistent storage yet.
    private static final Map<String, String> registeredUsers = new ConcurrentHashMap<>();
    private static final int MAX_LOGIN_ATTEMPTS = 3;

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
                out.println("Too many failed attempts. Disconnecting.");
                return; // finally block still runs disconnect() cleanup
            }

            // Tell everyone else (not this new client) that someone joined.
            broadcast(username + " has joined the chat", this);

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("[" + username + "] " + line);

                if (line.equalsIgnoreCase("quit")) {
                    break;
                }

                if (line.startsWith("/msg ")) {
                    handlePrivateMessage(line);
                    continue;
                }

                if (line.startsWith("/join ")) {
                    handleJoinRoom(line);
                    continue;
                }

                if (line.equalsIgnoreCase("/who")) {
                    handleWho();
                    continue;
                }

                if (line.equalsIgnoreCase("/leave")) {
                    handleLeaveRoom();
                    continue;
                }

                // Exclude the sender so they don't see their own message echoed back.
                broadcast(username + ": " + line, this);
            }
        } catch (IOException e) {
            System.out.println("Connection error: " + e.getMessage());
        } finally {
            disconnect();
        }
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
            if (client.username != null && client.currentRoom.equals(currentRoom)) {
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

        if (target == null) {
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
            if (client != exclude && client.currentRoom.equals(room)) {
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
        try {
            socket.close();
        } catch (IOException e) {
            // ignore -- socket is going away regardless
        }
        if (username != null) {
            broadcast(username + " has left the chat", this);
        }
        System.out.println("Disconnected: " + username
                + " (active clients: " + allClients.size() + ")");
    }
}
