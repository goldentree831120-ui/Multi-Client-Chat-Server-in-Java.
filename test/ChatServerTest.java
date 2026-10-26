import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests with no framework (the project has no build tool or dependencies yet):
 *
 *   javac -d out src/*.java test/*.java && java -ea -cp out ChatServerTest
 *
 * Part 1 checks the Metrics classes directly. Part 2 starts the real server on
 * free ports, drives it with real sockets, and checks /metrics says what happened.
 * Exit code is non-zero if any check fails, so CI can use it.
 */
public class ChatServerTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        histogramBuckets();
        exposition();
        endToEnd();

        System.out.println();
        System.out.println(passed + " checks passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("  FAIL " + f));
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // Part 1: metric types
    // ------------------------------------------------------------------

    static void histogramBuckets() {
        Metrics.Histogram h = new Metrics.Histogram("t_seconds", "test", 0.001, 0.01, 0.1);
        h.observe(0.0005); // le 0.001
        h.observe(0.001); // exactly on a bound: "le" is inclusive, so also le 0.001
        h.observe(0.05); // le 0.1
        h.observe(5); // only +Inf
        StringBuilder sb = new StringBuilder();
        h.render(sb);
        String out = sb.toString();
        check("bucket le=0.001 counts values <= bound", out.contains("t_seconds_bucket{le=\"0.001\"} 2\n"));
        check("buckets are cumulative", out.contains("t_seconds_bucket{le=\"0.01\"} 2\n")
                && out.contains("t_seconds_bucket{le=\"0.1\"} 3\n"));
        check("+Inf bucket equals count", out.contains("t_seconds_bucket{le=\"+Inf\"} 4\n")
                && out.contains("t_seconds_count 4\n"));
        check("sum is the total of observations", out.contains("t_seconds_sum 5.0515\n"));
    }

    static void exposition() {
        String s = Metrics.scrape();
        check("HELP and TYPE precede every metric", s.contains("# TYPE chat_active_connections gauge")
                && s.contains("# TYPE chat_messages_processed_total counter")
                && s.contains("# TYPE chat_message_handling_seconds histogram"));
        check("no locale decimal commas", !Pattern.compile("\\d,\\d").matcher(s).find());
        check("ends with newline", s.endsWith("\n"));
        boolean threw = false;
        try {
            Metrics.MESSAGES_PROCESSED.inc("not-a-type");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("unknown label values are rejected (bounded cardinality)", threw);
    }

    // ------------------------------------------------------------------
    // Part 2: the real server
    // ------------------------------------------------------------------

    static void endToEnd() throws Exception {
        int chatPort = freePort();
        int metricsPort = freePort();
        Thread server = new Thread(() -> {
            try {
                ChatServer.run(chatPort, metricsPort);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, "server");
        server.setDaemon(true);
        server.start();
        waitFor(() -> http(metricsPort, "/readyz").startsWith("200"), 5000);

        check("/healthz returns 200", http(metricsPort, "/healthz").startsWith("200"));
        check("/readyz returns 200 once the chat port is bound", http(metricsPort, "/readyz").startsWith("200"));
        check("/metrics returns 200", http(metricsPort, "/metrics").startsWith("200"));

        Client alice = Client.login(chatPort, "alice", "pw-a");
        Client bob = Client.login(chatPort, "bob", "pw-b");
        waitFor(() -> metric(metricsPort, "chat_logged_in_users") == 2, 2000);
        check("2 users logged in", metric(metricsPort, "chat_logged_in_users") == 2);
        check("2 connections open", metric(metricsPort, "chat_active_connections") == 2);

        // Username collision: "alice" is connected, so it is refused.
        Client dup = Client.connect(chatPort);
        dup.expect("Enter your username:");
        dup.send("alice");
        dup.expect("already taken");
        dup.expect("Enter your username:"); // server re-prompts
        check("username collision counted", metric(metricsPort, "chat_username_rejections_total") == 1);

        // dup is now stuck at the username prompt, NOT logged in. In v4c it would
        // still receive room broadcasts (and a client whose thread hadn't set `out`
        // yet caused a NullPointerException). Check it receives nothing.
        alice.send("hello everyone");
        bob.expect("alice: hello everyone");
        check("client at the login prompt does not receive chat", dup.silentFor(300));

        alice.send("/msg bob psst");
        bob.expect("[PM from alice]: psst");
        alice.send("/who");
        alice.expect("Users in room 'general'");
        alice.send("/join dev");
        alice.expect("You are now in room: dev");
        alice.send("/leave");
        alice.expect("You are now in room: general");

        waitFor(() -> metric(metricsPort, "chat_message_handling_seconds_count") == 5, 2000);
        check("broadcast counted", metric(metricsPort, "chat_messages_processed_total{type=\"broadcast\"}") == 1);
        check("private counted", metric(metricsPort, "chat_messages_processed_total{type=\"private\"}") == 1);
        check("who counted", metric(metricsPort, "chat_messages_processed_total{type=\"who\"}") == 1);
        check("join counted", metric(metricsPort, "chat_messages_processed_total{type=\"join\"}") == 1);
        check("leave counted", metric(metricsPort, "chat_messages_processed_total{type=\"leave\"}") == 1);
        check("latency histogram has one observation per line",
                metric(metricsPort, "chat_message_handling_seconds_count") == 5);
        check("latency sum is positive and small",
                metric(metricsPort, "chat_message_handling_seconds_sum") > 0
                        && metric(metricsPort, "chat_message_handling_seconds_sum") < 1);
        check("3 connections open (alice, bob, dup)", metric(metricsPort, "chat_active_connections") == 3);

        // Failed login: wrong password three times.
        bob.send("quit");
        waitFor(() -> metric(metricsPort, "chat_logged_in_users") == 1, 2000);
        Client imposter = Client.connect(chatPort);
        imposter.expect("Enter your username:");
        imposter.send("bob");
        for (int i = 0; i < 3; i++) {
            imposter.expect("Enter password:");
            imposter.send("wrong");
        }
        imposter.expect("Too many failed attempts");
        waitFor(() -> metric(metricsPort, "chat_login_failures_total") == 1, 2000);
        check("login failure counted", metric(metricsPort, "chat_login_failures_total") == 1);
        check("quit counted", metric(metricsPort, "chat_messages_processed_total{type=\"quit\"}") == 1);

        // Gauges come back down when clients leave.
        alice.close();
        dup.close();
        waitFor(() -> metric(metricsPort, "chat_active_connections") == 0, 3000);
        check("connections gauge returns to 0", metric(metricsPort, "chat_active_connections") == 0);
        check("logged-in gauge returns to 0", metric(metricsPort, "chat_logged_in_users") == 0);
        check("accepted counter = 4", metric(metricsPort, "chat_connections_accepted_total") == 4);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static final class Client {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;

        private Client(Socket s) throws IOException {
            socket = s;
            socket.setSoTimeout(3000);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(s.getOutputStream(), true);
        }

        static Client connect(int port) throws IOException {
            return new Client(new Socket("127.0.0.1", port));
        }

        static Client login(int port, String user, String password) throws IOException {
            Client c = connect(port);
            c.expect("Enter your username:");
            c.send(user);
            c.expect("set a password");
            c.send(password);
            c.expect("Account created");
            return c;
        }

        void send(String line) {
            out.println(line);
        }

        /** Reads lines until one contains `fragment`, or fails after the socket timeout. */
        void expect(String fragment) throws IOException {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.contains(fragment)) return;
                }
                check("expected \"" + fragment + "\" but connection closed", false);
            } catch (SocketTimeoutException e) {
                check("expected \"" + fragment + "\" within 3s", false);
            }
        }

        /** True if nothing arrives within `ms`. */
        boolean silentFor(int ms) throws IOException {
            socket.setSoTimeout(ms);
            try {
                String line = in.readLine();
                System.out.println("    (unexpected line: " + line + ")");
                return false;
            } catch (SocketTimeoutException e) {
                return true;
            } finally {
                socket.setSoTimeout(3000);
            }
        }

        void close() throws IOException {
            socket.close();
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** "200 body" or "503 body". */
    static String http(int port, String path) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
            c.setConnectTimeout(1000);
            c.setReadTimeout(1000);
            int code = c.getResponseCode();
            var stream = code < 400 ? c.getInputStream() : c.getErrorStream();
            String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return code + " " + body;
        } catch (IOException e) {
            return "ERR " + e.getMessage();
        }
    }

    /** Value of one series from /metrics, e.g. `chat_messages_processed_total{type="who"}`. NaN if absent. */
    static double metric(int port, String series) {
        String body = http(port, "/metrics");
        Matcher m = Pattern.compile("^" + Pattern.quote(series) + " (\\S+)$", Pattern.MULTILINE).matcher(body);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    interface Cond {
        boolean ok() throws Exception;
    }

    static void waitFor(Cond c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return;
            Thread.sleep(20);
        }
    }

    static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failures.add(what);
            System.out.println("  FAIL " + what);
        }
    }
}
