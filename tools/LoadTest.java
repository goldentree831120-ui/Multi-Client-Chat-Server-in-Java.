import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Load generator for the chat server, measuring END-TO-END latency.
 *
 * Opens N real client connections (each registers a fresh user and joins one of R rooms), then
 * sends chat messages at a fixed total rate. Every message carries the send time; every client
 * that receives it in the same room records (receive time - send time). Sender and receivers run
 * in this one JVM, so they share System.nanoTime() and the difference is real wall time through
 * the server and back, including the network stack (unlike the server's own histogram, which
 * only covers the server's work).
 *
 *   java -cp out LoadTest [host] [port] [clients] [rooms] [messagesPerSecond] [seconds]
 *   defaults:             localhost 5000  20       4       50                  60
 */
public class LoadTest {
    static final String TAG = "lt|";

    public static void main(String[] args) throws Exception {
        String host = arg(args, 0, "localhost");
        int port = Integer.parseInt(arg(args, 1, "5000"));
        int clients = Integer.parseInt(arg(args, 2, "20"));
        int rooms = Integer.parseInt(arg(args, 3, "4"));
        int rate = Integer.parseInt(arg(args, 4, "50"));
        int seconds = Integer.parseInt(arg(args, 5, "60"));
        if (clients < 2 || rooms < 1 || clients < 2 * rooms) {
            System.err.println("need clients >= 2 and at least 2 clients per room");
            System.exit(2);
        }
        String run = Long.toString(System.currentTimeMillis() % 1_000_000, 36);
        System.out.printf("load test: %d clients in %d rooms, %d msg/s for %d s against %s:%d%n",
                clients, rooms, rate, seconds, host, port);

        ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();
        AtomicLong sent = new AtomicLong(), received = new AtomicLong(), errors = new AtomicLong();
        List<Conn> conns = new ArrayList<>();
        long connectStart = System.nanoTime();
        for (int i = 0; i < clients; i++) {
            try {
                Conn c = new Conn(host, port, "lt-" + run + "-" + i, "room-" + (i % rooms), latenciesNanos, received);
                conns.add(c);
            } catch (IOException e) {
                errors.incrementAndGet();
                System.err.println("connect/login failed for client " + i + ": " + e.getMessage());
            }
        }
        System.out.printf("connected and logged in: %d/%d in %.1f s%n",
                conns.size(), clients, (System.nanoTime() - connectStart) / 1e9);
        if (conns.size() < 2) System.exit(1);

        ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor();
        long periodMicros = Math.max(1, 1_000_000L / Math.max(1, rate));
        AtomicLong next = new AtomicLong();
        CountDownLatch done = new CountDownLatch(1);
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        sender.scheduleAtFixedRate(() -> {
            if (System.nanoTime() >= deadline) {
                done.countDown();
                return;
            }
            Conn c = conns.get((int) (next.getAndIncrement() % conns.size()));
            if (c.send(TAG + System.nanoTime())) sent.incrementAndGet();
            else errors.incrementAndGet();
        }, 0, periodMicros, TimeUnit.MICROSECONDS);
        done.await();
        sender.shutdownNow();
        Thread.sleep(1000); // let the last messages arrive

        for (Conn c : conns) c.close();
        report(sent.get(), received.get(), errors.get(), seconds, latenciesNanos, conns.size(), rooms);
    }

    static void report(long sent, long received, long errors, int seconds, ConcurrentLinkedQueue<Long> lat, int clients, int rooms) {
        long[] v = lat.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(v);
        double expectedPerMsg = (double) clients / rooms - 1; // everyone else in the sender's room
        System.out.println();
        System.out.printf("messages sent:       %d (%.1f/s)%n", sent, sent / (double) seconds);
        System.out.printf("deliveries received: %d (expected about %.0f = sent x %.1f others per room)%n",
                received, sent * expectedPerMsg, expectedPerMsg);
        System.out.printf("errors:              %d%n", errors);
        if (v.length > 0) {
            System.out.printf("end-to-end latency:  p50 %.2f ms   p95 %.2f ms   p99 %.2f ms   max %.2f ms%n",
                    pct(v, 50), pct(v, 95), pct(v, 99), v[v.length - 1] / 1e6);
        }
    }

    static double pct(long[] sorted, double p) {
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))] / 1e6;
    }

    static String arg(String[] a, int i, String d) {
        return a.length > i ? a[i] : d;
    }

    /** One logged-in client: registers, joins its room, records latency of tagged messages. */
    static final class Conn {
        final Socket socket;
        final PrintWriter out;
        final BufferedReader in;

        Conn(String host, int port, String user, String room, ConcurrentLinkedQueue<Long> lat, AtomicLong received) throws IOException {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(10_000);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            expect("Enter your username:");
            out.println(user);
            expect("New username");
            out.println("load-test-password");
            expect("Account created");
            out.println("/join " + room);
            expect("You are now in room");
            socket.setSoTimeout(0);
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        int i = line.indexOf(TAG);
                        if (i >= 0) {
                            long now = System.nanoTime();
                            try {
                                lat.add(now - Long.parseLong(line.substring(i + TAG.length()).trim()));
                                received.incrementAndGet();
                            } catch (NumberFormatException ignored) {
                                // not one of ours
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // connection closed at the end of the test
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        private void expect(String prefix) throws IOException {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith(prefix)) return;
            }
            throw new IOException("connection closed while waiting for: " + prefix);
        }

        synchronized boolean send(String msg) {
            out.println(msg);
            return !out.checkError();
        }

        void close() {
            try {
                send("quit");
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }
}
