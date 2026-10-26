import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Small HTTP server (JDK built-in, no dependencies) on a separate port from chat:
 *
 *   GET /metrics  Prometheus scrapes this
 *   GET /healthz  liveness: the process is up and the HTTP thread answers
 *   GET /readyz   readiness: the chat socket is bound and accepting
 *
 * Kubernetes will use /healthz and /readyz as probes in a later step.
 * Metrics live on their own port so they are never exposed on the public chat port.
 */
public final class MetricsServer {

    private final HttpServer http;

    public MetricsServer(int port, BooleanSupplier ready) throws IOException {
        http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "metrics-http");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/metrics", ex -> respond(ex, 200,
                "text/plain; version=0.0.4; charset=utf-8", Metrics.scrape()));
        http.createContext("/healthz", ex -> respond(ex, 200, "text/plain", "ok\n"));
        http.createContext("/readyz", ex -> {
            boolean ok = ready.getAsBoolean();
            respond(ex, ok ? 200 : 503, "text/plain", ok ? "ready\n" : "not ready\n");
        });
    }

    public void start() {
        http.start();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public void stop() {
        http.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String type, String body) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
            status = 405;
            body = "method not allowed\n";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, "HEAD".equals(ex.getRequestMethod()) ? -1 : bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            if (!"HEAD".equals(ex.getRequestMethod())) os.write(bytes);
        }
    }
}
