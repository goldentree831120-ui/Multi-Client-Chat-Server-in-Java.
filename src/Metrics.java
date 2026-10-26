import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * The chat server's metrics, rendered in the Prometheus text exposition format
 * (https://prometheus.io/docs/instrumenting/exposition_formats/).
 *
 * Written by hand instead of using the Prometheus Java client so the server
 * still builds with plain `javac` and no dependencies. Every metric is
 * thread-safe: many ClientHandler threads update them at once while the
 * /metrics HTTP thread reads them.
 *
 * Metric types, in one line each:
 *   counter   - only goes up (resets on restart); Prometheus computes rates from it
 *   gauge     - goes up and down; a current value
 *   histogram - counts observations into cumulative buckets so Prometheus can
 *               estimate percentiles (p50/p95) with histogram_quantile()
 */
public final class Metrics {

    // ---- the metrics this server exposes ----

    /** TCP connections currently open (including ones still logging in). */
    public static final Gauge ACTIVE_CONNECTIONS = new Gauge(
            "chat_active_connections", "TCP connections currently open, including clients still logging in.");

    /** Users who have finished login and can chat. */
    public static final Gauge LOGGED_IN_USERS = new Gauge(
            "chat_logged_in_users", "Clients that have completed login and can send messages.");

    public static final Counter CONNECTIONS_ACCEPTED = new Counter(
            "chat_connections_accepted_total", "TCP connections accepted since start.");

    /** Messages handled, split by what kind of line it was. */
    public static final LabeledCounter MESSAGES_PROCESSED = new LabeledCounter(
            "chat_messages_processed_total", "Chat lines handled after login, by type.",
            "type", "broadcast", "private", "join", "leave", "who", "quit");

    /** Time from reading a line to finishing handling it (including writing to every recipient). */
    public static final Histogram MESSAGE_LATENCY = new Histogram(
            "chat_message_handling_seconds", "Time to handle one chat line, from read to last write.",
            0.00005, 0.0001, 0.00025, 0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0);

    public static final Counter USERNAME_REJECTIONS = new Counter(
            "chat_username_rejections_total", "Usernames refused because a connected client already uses them.");

    public static final Counter LOGIN_FAILURES = new Counter(
            "chat_login_failures_total", "Clients disconnected after too many wrong passwords or an empty registration.");

    private static final long START_MILLIS = System.currentTimeMillis();

    private Metrics() {
    }

    /** Everything above, in exposition format. Called by the /metrics endpoint. */
    public static String scrape() {
        StringBuilder sb = new StringBuilder(2048);
        ACTIVE_CONNECTIONS.render(sb);
        LOGGED_IN_USERS.render(sb);
        CONNECTIONS_ACCEPTED.render(sb);
        MESSAGES_PROCESSED.render(sb);
        MESSAGE_LATENCY.render(sb);
        USERNAME_REJECTIONS.render(sb);
        LOGIN_FAILURES.render(sb);
        header(sb, "process_start_time_seconds", "gauge", "Start time of the process since unix epoch in seconds.");
        sb.append("process_start_time_seconds ").append(fmt(START_MILLIS / 1000.0)).append('\n');
        return sb.toString();
    }

    // ---- metric types ----

    public static final class Counter {
        private final String name;
        private final String help;
        private final LongAdder value = new LongAdder(); // LongAdder: low contention under many writer threads

        Counter(String name, String help) {
            this.name = name;
            this.help = help;
        }

        public void inc() {
            value.increment();
        }

        public long get() {
            return value.sum();
        }

        void render(StringBuilder sb) {
            header(sb, name, "counter", help);
            sb.append(name).append(' ').append(value.sum()).append('\n');
        }
    }

    /** A counter with one label whose allowed values are fixed up front, so cardinality can't explode. */
    public static final class LabeledCounter {
        private final String name;
        private final String help;
        private final String label;
        private final String[] values;
        private final LongAdder[] counts;

        LabeledCounter(String name, String help, String label, String... values) {
            this.name = name;
            this.help = help;
            this.label = label;
            this.values = values;
            this.counts = new LongAdder[values.length];
            for (int i = 0; i < values.length; i++) counts[i] = new LongAdder();
        }

        public void inc(String labelValue) {
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(labelValue)) {
                    counts[i].increment();
                    return;
                }
            }
            throw new IllegalArgumentException("unknown " + label + ": " + labelValue);
        }

        public long get(String labelValue) {
            int i = Arrays.asList(values).indexOf(labelValue);
            return i < 0 ? 0 : counts[i].sum();
        }

        void render(StringBuilder sb) {
            header(sb, name, "counter", help);
            for (int i = 0; i < values.length; i++) {
                sb.append(name).append('{').append(label).append("=\"").append(values[i]).append("\"} ")
                        .append(counts[i].sum()).append('\n');
            }
        }
    }

    public static final class Gauge {
        private final String name;
        private final String help;
        private final AtomicLong value = new AtomicLong();

        Gauge(String name, String help) {
            this.name = name;
            this.help = help;
        }

        public void inc() {
            value.incrementAndGet();
        }

        public void dec() {
            value.decrementAndGet();
        }

        public long get() {
            return value.get();
        }

        void render(StringBuilder sb) {
            header(sb, name, "gauge", help);
            sb.append(name).append(' ').append(value.get()).append('\n');
        }
    }

    public static final class Histogram {
        private final String name;
        private final String help;
        private final double[] bounds; // upper bounds, ascending; +Inf is implicit
        private final LongAdder[] buckets; // NON-cumulative counts; made cumulative when rendering
        private final LongAdder count = new LongAdder();
        private final DoubleAdder sum = new DoubleAdder();

        Histogram(String name, String help, double... bounds) {
            this.name = name;
            this.help = help;
            this.bounds = bounds.clone();
            Arrays.sort(this.bounds);
            this.buckets = new LongAdder[bounds.length + 1]; // last one is +Inf
            for (int i = 0; i < buckets.length; i++) buckets[i] = new LongAdder();
        }

        public void observe(double seconds) {
            int i = Arrays.binarySearch(bounds, seconds);
            if (i < 0) i = -i - 1; // insertion point = first bound >= value ("le" is inclusive)
            buckets[i].increment();
            count.increment();
            sum.add(seconds);
        }

        /** Convenience: observe the time since a System.nanoTime() reading. */
        public void observeSince(long startNanos) {
            observe((System.nanoTime() - startNanos) / 1e9);
        }

        public long count() {
            return count.sum();
        }

        void render(StringBuilder sb) {
            header(sb, name, "histogram", help);
            // Read each bucket once. Under concurrent writes _count may be a
            // few observations ahead of the buckets; Prometheus tolerates that.
            long cumulative = 0;
            for (int i = 0; i < bounds.length; i++) {
                cumulative += buckets[i].sum();
                sb.append(name).append("_bucket{le=\"").append(fmt(bounds[i])).append("\"} ").append(cumulative).append('\n');
            }
            cumulative += buckets[bounds.length].sum();
            sb.append(name).append("_bucket{le=\"+Inf\"} ").append(cumulative).append('\n');
            sb.append(name).append("_sum ").append(fmt(sum.sum())).append('\n');
            sb.append(name).append("_count ").append(cumulative).append('\n');
        }
    }

    private static void header(StringBuilder sb, String name, String type, String help) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static String fmt(double d) {
        // Locale.ROOT: never print "0,001" on a machine with a European locale.
        String s = String.format(Locale.ROOT, "%.9f", d);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s + "0" : s;
    }
}
