import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * v3: Multi-client chat server + Prometheus metrics endpoint.
 *
 * Same thread-per-client design as v2: after accept()ing a client, hand it
 * off to a new Thread and go straight back to accept() for the next one.
 *
 * New in v3:
 * - a second, HTTP port serving /metrics, /healthz and /readyz (MetricsServer)
 * - ports come from environment variables, so Docker / Kubernetes can set them:
 *     CHAT_PORT     (default 5000)  the chat protocol
 *     METRICS_PORT  (default 9100)  Prometheus scrapes this
 */
public class ChatServer {

    // Shared list of all connected clients' handlers.
    // CopyOnWriteArrayList is thread-safe for this "many threads read/write
    // occasionally" pattern -- safe to iterate even while another thread
    // adds/removes an entry, without needing manual synchronized blocks.
    private static final List<ClientHandler> clients = new CopyOnWriteArrayList<>();

    private static volatile boolean accepting = false;

    // CHAT_LOG_MESSAGES=false silences per-connection and per-message logging (used under load).
    private static final boolean LOG_CONNECTIONS =
            !"false".equalsIgnoreCase(System.getenv().getOrDefault("CHAT_LOG_MESSAGES", "true"));

    public static void main(String[] args) throws IOException {
        run(envInt("CHAT_PORT", 5000), envInt("METRICS_PORT", 9100));
    }

    /** Runs the server until the process exits. Separate from main() so tests can pick ports. */
    public static void run(int port, int metricsPort) throws IOException {
        MetricsServer metrics = new MetricsServer(metricsPort, () -> accepting);
        metrics.start();
        System.out.println("Metrics on http://0.0.0.0:" + metricsPort + "/metrics");

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Chat server listening on port " + port + " ...");
            accepting = true; // /readyz now returns 200

            while (true) {
                // Main thread's ONLY job: accept connections and hand them off.
                Socket clientSocket = serverSocket.accept();
                Metrics.CONNECTIONS_ACCEPTED.inc(); // [metrics]
                if (LOG_CONNECTIONS) {
                    System.out.println("New connection: " + clientSocket.getInetAddress());
                }

                ClientHandler handler = new ClientHandler(clientSocket, clients);
                clients.add(handler);

                Thread thread = new Thread(handler);
                thread.start();
                // Main thread immediately loops back to accept() -- it does NOT
                // wait for this client's thread to finish.
            }
        } finally {
            accepting = false;
        }
    }

    private static int envInt(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number, got: " + v);
        }
    }
}

