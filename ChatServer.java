import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * v2: Multi-client chat server.
 *
 * Key change from v1: after accept()ing a client, we immediately hand it
 * off to a new Thread and go straight back to accept() for the next one.
 * This means the main thread is NEVER stuck serving just one client.
 */
public class ChatServer {

    // Shared list of all connected clients' handlers.
    // CopyOnWriteArrayList is thread-safe for this "many threads read/write
    // occasionally" pattern -- safe to iterate even while another thread
    // adds/removes an entry, without needing manual synchronized blocks.
    private static final List<ClientHandler> clients = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws IOException {
        int port = 5000;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Chat server listening on port " + port + " ...");

            while (true) {
                // Main thread's ONLY job: accept connections and hand them off.
                Socket clientSocket = serverSocket.accept();
                System.out.println("New connection: " + clientSocket.getInetAddress());

                ClientHandler handler = new ClientHandler(clientSocket, clients);
                clients.add(handler);

                Thread thread = new Thread(handler);
                thread.start();
                // Main thread immediately loops back to accept() -- it does NOT
                // wait for this client's thread to finish.
            }
        }
    }
}