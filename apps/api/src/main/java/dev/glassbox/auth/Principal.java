package dev.glassbox.auth;

import java.util.Set;
import java.util.UUID;

/** Authenticated caller: a user (JWT) or a CI bot (API key, bound to one workspace). */
public record Principal(UUID userId, UUID workspaceId, Set<String> scopes) {
    public static Principal user(UUID userId) {
        return new Principal(userId, null, Set.of());
    }

    public static Principal apiKey(UUID workspaceId, Set<String> scopes) {
        return new Principal(null, workspaceId, scopes);
    }

    public boolean isApiKey() {
        return userId == null;
    }

    /** Name recorded in the audit log. */
    public String actor() {
        return isApiKey() ? "apikey@" + workspaceId : "user:" + userId;
    }
}
