package dev.glassbox.auth;

import dev.glassbox.common.ApiException;
import dev.glassbox.tenancy.Tx;
import dev.glassbox.workspace.WorkspaceService;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    public record User(UUID id, String email) {}
    public record Session(User user, String accessToken, long expiresInSeconds, String refreshToken) {}

    private final Tx tx;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final WorkspaceService workspaces;
    private final Duration refreshTtl;

    public AuthService(Tx tx, PasswordEncoder encoder, JwtService jwt, WorkspaceService workspaces,
                       @Value("${glassbox.refresh.ttl}") Duration refreshTtl) {
        this.tx = tx;
        this.encoder = encoder;
        this.jwt = jwt;
        this.workspaces = workspaces;
        this.refreshTtl = refreshTtl;
    }

    public Session register(String email, String password) {
        String hash = encoder.encode(password);
        UUID id;
        try {
            id = tx.anonymous(j -> j.queryForObject("SELECT auth_create_user(?, ?)", UUID.class, email.trim(), hash));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("An account with this email already exists");
        }
        workspaces.create(id, "My workspace");
        return startSession(new User(id, email.trim()));
    }

    public Session login(String email, String password) {
        Map<String, Object> row = tx.anonymous(j ->
                j.queryForList("SELECT id, email, password_hash FROM auth_find_user(?)", email.trim())
                        .stream().findFirst().orElse(null));
        // Same error for unknown email and wrong password.
        if (row == null || !encoder.matches(password, (String) row.get("password_hash"))) {
            throw ApiException.unauthorized("Invalid email or password");
        }
        return startSession(new User((UUID) row.get("id"), (String) row.get("email")));
    }

    /** Signs in the demo viewer without a password. Only {@link DemoMode} decides whether this may be called. */
    public Session demo(String email) {
        Map<String, Object> row = tx.anonymous(j -> j.queryForList("SELECT id, email, password_hash FROM auth_find_user(?)", email).stream().findFirst().orElse(null));
        if (row == null) throw ApiException.notFound("The demo has not been set up on this server");
        return startSession(new User((UUID) row.get("id"), (String) row.get("email")));
    }

    /** Rotates the refresh token. Reuse of a revoked token revokes every token of that user. */
    public Session refresh(String cookieValue) {
        UUID userId = parseUser(cookieValue);
        String hash = Hashing.sha256Hex(cookieValue);
        Boolean ok = tx.user(userId, j -> {
            var rows = j.queryForList(
                    "SELECT id, revoked_at IS NOT NULL AS revoked, expires_at < now() AS expired "
                            + "FROM refresh_tokens WHERE token_hash = ?", hash);
            if (rows.isEmpty()) return false;
            var r = rows.get(0);
            if ((Boolean) r.get("revoked")) {
                j.update("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId);
                return false;
            }
            if ((Boolean) r.get("expired")) return false;
            j.update("UPDATE refresh_tokens SET revoked_at = now() WHERE id = ?", r.get("id"));
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) throw ApiException.unauthorized("Invalid refresh token");
        User user = tx.user(userId, j -> j.queryForObject(
                "SELECT id, email FROM users WHERE id = ?",
                (rs, n) -> new User(rs.getObject("id", UUID.class), rs.getString("email")), userId));
        return startSession(user);
    }

    public void logout(String cookieValue) {
        if (cookieValue == null) return;
        try {
            UUID userId = parseUser(cookieValue);
            tx.user(userId, j -> j.update(
                    "UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL",
                    Hashing.sha256Hex(cookieValue)));
        } catch (ApiException ignored) {
            // nothing to revoke
        }
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }

    private Session startSession(User user) {
        String refresh = user.id() + "." + Hashing.randomSecret();
        tx.user(user.id(), j -> j.update(
                "INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES (?, ?, now() + (? * interval '1 second'))",
                user.id(), Hashing.sha256Hex(refresh), refreshTtl.toSeconds()));
        return new Session(user, jwt.issue(user.id()), jwt.ttl().toSeconds(), refresh);
    }

    private static UUID parseUser(String cookieValue) {
        try {
            return UUID.fromString(cookieValue.substring(0, cookieValue.indexOf('.')));
        } catch (RuntimeException e) {
            throw ApiException.unauthorized("Invalid refresh token");
        }
    }
}
