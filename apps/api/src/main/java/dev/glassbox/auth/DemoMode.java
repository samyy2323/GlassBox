package dev.glassbox.auth;

import dev.glassbox.tenancy.Tx;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A public demo: one configured account that anybody can sign in to without a password, and that can only read. It is off unless
 * {@code glassbox.demo.enabled} is set, and the account must already exist (the demo seed creates it as a viewer of the demo workspace).
 */
@Component
public class DemoMode {
    private final boolean enabled;
    private final String viewerEmail;
    private final Tx tx;
    private final AtomicReference<UUID> viewerId = new AtomicReference<>();

    public DemoMode(@Value("${glassbox.demo.enabled:false}") boolean enabled, @Value("${glassbox.demo.viewer-email:demo@glassbox.local}") String viewerEmail, Tx tx) {
        this.enabled = enabled;
        this.viewerEmail = viewerEmail;
        this.tx = tx;
    }

    public boolean enabled() {
        return enabled;
    }

    public String viewerEmail() {
        return viewerEmail;
    }

    /** Whether this user is the demo viewer. The account may be created after the server starts, so the id is looked up until it is found. */
    public boolean isViewer(UUID userId) {
        if (!enabled || userId == null) return false;
        UUID known = viewerId.get();
        if (known == null) {
            known = tx.anonymous(j -> j.queryForList("SELECT id FROM auth_find_user(?)", UUID.class, viewerEmail).stream().findFirst().orElse(null));
            if (known != null) viewerId.set(known);
        }
        return userId.equals(known);
    }
}
