package dev.glassbox.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Receives document status changes the worker publishes with NOTIFY and fans them out to subscribers of that workspace.
 * It holds ONE dedicated connection (not from the pool) that stays in LISTEN, and reconnects with backoff if it drops.
 * Payloads carry ids and a status only. Workspace routing happens here, so a subscriber never receives another
 * workspace's events.
 */
@Component
@Profile("api")
public class DocumentEventsHub {
    private static final Logger log = LoggerFactory.getLogger(DocumentEventsHub.class);
    static final int MAX_SUBSCRIBERS_PER_WORKSPACE = 50;

    private final ObjectMapper json;
    private final String url;
    private final String user;
    private final String password;
    private final Map<UUID, CopyOnWriteArraySet<Consumer<String>>> listeners = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "doc-events-keepalive");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;
    private Thread listenThread;

    public DocumentEventsHub(ObjectMapper json, @Value("${spring.datasource.url}") String url,
                             @Value("${spring.datasource.username}") String user,
                             @Value("${spring.datasource.password}") String password) {
        this.json = json;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** @return a handle that unsubscribes, or null if the workspace already has too many subscribers. A null payload is a keep-alive tick. */
    public Runnable subscribe(UUID workspaceId, Consumer<String> listener) {
        CopyOnWriteArraySet<Consumer<String>> set = listeners.computeIfAbsent(workspaceId, k -> new CopyOnWriteArraySet<>());
        synchronized (set) {
            if (set.size() >= MAX_SUBSCRIBERS_PER_WORKSPACE) return null;
            set.add(listener);
        }
        return () -> {
            set.remove(listener);
            if (set.isEmpty()) listeners.remove(workspaceId, set);
        };
    }

    @PostConstruct
    void start() {
        listenThread = new Thread(this::listenLoop, "doc-events-listener");
        listenThread.setDaemon(true);
        listenThread.start();
        keepAlive.scheduleAtFixedRate(() -> listeners.values().forEach(set -> set.forEach(l -> safely(l, null))), 20, 20, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        running = false;
        keepAlive.shutdownNow();
        if (listenThread != null) listenThread.interrupt();
    }

    private void listenLoop() {
        long delay = 1000;
        while (running) {
            try (Connection c = DriverManager.getConnection(url, user, password)) {
                try (Statement st = c.createStatement()) {
                    st.execute("LISTEN " + DocumentEvents.CHANNEL);
                }
                PGConnection pg = c.unwrap(PGConnection.class);
                delay = 1000;
                log.info("listening for document status changes");
                while (running) {
                    PGNotification[] batch = pg.getNotifications(1000);
                    if (batch != null) for (PGNotification n : batch) dispatch(n.getParameter());
                }
            } catch (SQLException e) {
                if (!running) return;
                log.warn("document event listener lost its connection ({}); retrying in {} ms", e.getMessage(), delay);
                sleep(delay);
                delay = Math.min(delay * 2, 30_000);
            }
        }
    }

    void dispatch(String payload) {
        try {
            JsonNode n = json.readTree(payload);
            UUID ws = UUID.fromString(n.get("workspaceId").asText());
            var set = listeners.get(ws);
            if (set == null) return;
            String out = json.writeValueAsString(Map.of("documentId", n.get("documentId").asText(), "status", n.get("status").asText()));
            set.forEach(l -> safely(l, out));
        } catch (Exception e) {
            log.warn("ignoring malformed document event: {}", e.toString());
        }
    }

    private static void safely(Consumer<String> l, String payload) {
        try {
            l.accept(payload);
        } catch (RuntimeException ignored) {
            // a dead subscriber must not break delivery to the others; it unsubscribes itself
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    int subscriberCount(UUID ws) {
        var s = listeners.get(ws);
        return s == null ? 0 : s.size();
    }

    List<UUID> workspaces() {
        return List.copyOf(listeners.keySet());
    }
}
