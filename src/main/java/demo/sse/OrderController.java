package demo.sse;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.management.ManagementFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class OrderController {
    private static final String[] STATUSES = {"Confirmed", "Preparing", "Out for delivery", "Delivered"};
    private final Map<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final java.util.Set<String> loadClients = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> order = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;
    private final OrderWebSocketHandler webSockets;
    private final AtomicLong pollRequests = new AtomicLong();
    @Value("${server.port:8000}")
    private int serverPort;
    private volatile Process loadProcess;
    private volatile Path loadMetricsFile;
    private volatile int loadTarget;

    public OrderController(ObjectMapper objectMapper, OrderWebSocketHandler webSockets) {
        this.objectMapper = objectMapper;
        this.webSockets = webSockets;
        order.put("id", "ORD-2048");
        order.put("status", STATUSES[0]);
        order.put("updated_at", System.currentTimeMillis() / 1000.0);
    }

    @GetMapping("/api/order")
    public synchronized Map<String, Object> currentOrder() {
        pollRequests.incrementAndGet();
        return orderSnapshot();
    }

    private synchronized Map<String, Object> orderSnapshot() {
        return new LinkedHashMap<>(order);
    }

    public String orderMessage(String type) {
        try {
            return objectMapper.writeValueAsString(Map.of("type", type, "order", orderSnapshot(), "event_time_ms", System.currentTimeMillis()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize WebSocket order message", error);
        }
    }

    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@org.springframework.web.bind.annotation.RequestHeader(value = "X-SSE-Load-Client", required = false) String loadClient) {
        String clientId = UUID.randomUUID().toString().substring(0, 8);
        SseEmitter emitter = new SseEmitter(0L);
        clients.put(clientId, emitter);
        if (loadClient != null) loadClients.add(clientId);
        Runnable disconnect = () -> removeClient(clientId);
        emitter.onCompletion(disconnect);
        emitter.onTimeout(disconnect);
        emitter.onError(error -> disconnect.run());

        try {
            emitter.send(SseEmitter.event().name("connected").id(clientId)
                    .data(Map.of("client", clientId, "order", orderSnapshot(), "event_time_ms", System.currentTimeMillis())));
            logConnection("connected", clientId, clients.size());
        } catch (IOException error) {
            emitter.completeWithError(error);
        }
        return emitter;
    }

    @PostMapping("/api/advance")
    public ResponseEntity<Map<String, Object>> advance(@RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (status == null || !java.util.Arrays.asList(STATUSES).contains(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown status"));
        }
        Map<String, Object> updated;
        synchronized (this) {
            order.put("status", status);
            order.put("updated_at", System.currentTimeMillis() / 1000.0);
            updated = new LinkedHashMap<>(order);
        }
        String eventId = UUID.randomUUID().toString().substring(0, 8);
        System.out.printf("Order changed -> %s; broadcasting event %s to %d client(s)%n", status, eventId, clients.size());
        long eventTime = System.currentTimeMillis();
        broadcast("order_update", Map.of("id", eventId, "order", updated, "event_time_ms", eventTime));
        try {
            webSockets.broadcast(objectMapper.writeValueAsString(Map.of("type", "order_update", "id", eventId,
                    "order", updated, "event_time_ms", eventTime)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize WebSocket broadcast", error);
        }
        return ResponseEntity.ok(Map.of("ok", true, "order", updated, "event_id", eventId));
    }

    private void broadcast(String eventName, Object payload) {
        clients.forEach((clientId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException error) {
                removeClient(clientId);
                emitter.completeWithError(error);
            }
        });
    }

    private void removeClient(String clientId) {
        loadClients.remove(clientId);
        if (clients.remove(clientId) != null) {
            logConnection("disconnected", clientId, clients.size());
        }
    }

    private void logConnection(String action, String clientId, int count) {
        if (count <= 10 || count == 0 || count % 1_000 == 0) {
            System.out.printf("SSE client %s: %s (%d connected)%n", action, clientId, count);
        }
    }

    @GetMapping("/api/metrics")
    public Map<String, Object> metrics() {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        double cpuLoad = os.getProcessCpuLoad();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sse_connections", clients.size());
        result.put("websocket_connections", webSockets.connectionCount());
        result.put("poll_requests_total", pollRequests.get());
        result.put("cpu_percent", cpuLoad < 0 ? null : Math.round(cpuLoad * 1000.0) / 10.0);
        result.put("heap_used_bytes", heap.getUsed());
        result.put("heap_max_bytes", heap.getMax());
        result.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        return result;
    }

    @PostMapping("/api/load/start")
    public synchronized ResponseEntity<Map<String, Object>> startLoad(@RequestBody Map<String, Integer> body) {
        if (loadProcess != null && loadProcess.isAlive()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "A load run is already active"));
        }
        int target = body.getOrDefault("connections", 0);
        if (target != 1_000 && target != 10_000 && target != 100_000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Choose 1,000, 10,000 or 100,000 connections"));
        }
        long heapLimit = Runtime.getRuntime().maxMemory();
        long heapRequired = 16L * 1024 * 1024 * 1024;
        if (target == 100_000 && heapLimit < heapRequired) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                    "error", "100,000 connections are disabled: this JVM has a " + (heapLimit / (1024 * 1024 * 1024))
                            + " GiB heap limit. The 10,000-connection run used about 1.1 GiB; a larger JVM/server is needed for the next scale."
            ));
        }
        try {
            String classPath = System.getProperty("java.class.path");
            Path codeLocation = Path.of(classPath.split(java.io.File.pathSeparator)[0]);
            String javaExecutable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "java.exe" : "java";
            String java = Path.of(System.getProperty("java.home"), "bin", javaExecutable).toString();
            String url = "http://127.0.0.1:" + serverPort + "/events";
            loadMetricsFile = Path.of(System.getProperty("java.io.tmpdir"), "sse-load-" + UUID.randomUUID() + ".json");
            String targetText = Integer.toString(target);
            String metricsPath = loadMetricsFile.toString();
            ProcessBuilder builder;
            if (codeLocation.toString().endsWith(".jar")) {
                builder = new ProcessBuilder(java, "-jar", codeLocation.toString(), "--sse-load-generator", url, targetText, metricsPath);
            } else {
                builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        OrderSseApplication.class.getName(), "--sse-load-generator", url, targetText, metricsPath);
            }
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            loadProcess = builder.start();
            loadTarget = target;
            return ResponseEntity.accepted().body(Map.of("ok", true, "target", target, "pid", loadProcess.pid()));
        } catch (Exception error) {
            error.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not start load generator: " + error));
        }
    }

    @GetMapping("/api/load/status")
    public Map<String, Object> loadStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", loadProcess != null && loadProcess.isAlive());
        result.put("target", loadTarget);
        result.put("pid", loadProcess == null ? null : loadProcess.pid());
        result.put("connections", 0);
        result.put("started", 0);
        result.put("failures", 0);
        result.put("events", 0);
        if (loadMetricsFile != null && Files.exists(loadMetricsFile)) {
            try {
                result.putAll(objectMapper.readValue(Files.readString(loadMetricsFile), new TypeReference<Map<String, Object>>() {}));
            } catch (IOException ignored) {
                // The file is replaced atomically; the next poll will read a complete snapshot.
            }
        }
        return result;
    }

    @PostMapping("/api/load/stop")
    public synchronized Map<String, Object> stopLoad() throws InterruptedException {
        // Quiet SSE clients may not be noticed as disconnected until the server writes again.
        // Complete simulator emitters explicitly so the live count falls as soon as Stop is used.
        for (String clientId : java.util.Set.copyOf(loadClients)) {
            SseEmitter emitter = clients.get(clientId);
            if (emitter != null) emitter.complete();
        }
        if (loadProcess != null && loadProcess.isAlive()) {
            loadProcess.destroy();
            if (!loadProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) loadProcess.destroyForcibly();
        }
        return Map.of("ok", true, "stopped", true);
    }
}
