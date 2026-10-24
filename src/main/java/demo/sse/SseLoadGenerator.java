package demo.sse;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Separate-process load client: each virtual thread owns one real HTTP/SSE socket. */
public final class SseLoadGenerator {
    private SseLoadGenerator() {}

    public static void run(String[] args) {
        if (args.length < 4) return;
        String url = args[1];
        int target = Integer.parseInt(args[2]);
        Path metricsFile = Path.of(args[3]);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger connected = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger events = new AtomicInteger();
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor();
        reporter.scheduleAtFixedRate(() -> writeMetrics(metricsFile, target, started.get(), connected.get(), failures.get(), events.get()),
                0, 500, TimeUnit.MILLISECONDS);

        try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < target; i++) {
                clients.submit(() -> holdConnection(url, started, connected, failures, events));
                started.incrementAndGet();
                // Ramp instead of creating an abrupt connection spike: about 1,000 attempts/sec.
                if (i % 100 == 99) Thread.sleep(100);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            reporter.shutdownNow();
        }
    }

    private static void holdConnection(String url, AtomicInteger started, AtomicInteger connected,
                                      AtomicInteger failures, AtomicInteger events) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("X-SSE-Load-Client", "true");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(0);
            if (connection.getResponseCode() != 200) throw new IllegalStateException("SSE returned HTTP " + connection.getResponseCode());
            connected.incrementAndGet();
            try (BufferedReader stream = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stream.readLine()) != null) {
                    if (line.startsWith("event: order_update")) events.incrementAndGet();
                }
            }
        } catch (Exception failure) {
            failures.incrementAndGet();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void writeMetrics(Path file, int target, int started, int connected, int failures, int events) {
        try {
            Files.createDirectories(file.getParent());
            String json = "{\"target\":" + target + ",\"started\":" + started + ",\"connected\":" + connected
                    + ",\"failures\":" + failures + ",\"events\":" + events + "}";
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // The live connection count remains available from the server if progress reporting fails.
        }
    }
}
