package dev.glassbox.workspace;

import dev.glassbox.auth.Hashing;
import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceService {
    public record Workspace(UUID id, String name, Role role) {}
    public record Member(UUID userId, String email, Role role) {}
    public record ApiKeyInfo(UUID id, String label, List<String> scopes, OffsetDateTime lastUsedAt) {}
    public record CreatedApiKey(UUID id, String label, List<String> scopes, String key) {}

    static final Set<String> SCOPES = Set.of("read", "run", "admin");

    private final Tx tx;

    public WorkspaceService(Tx tx) {
        this.tx = tx;
    }

    // ------------------------------------------------------------ workspaces

    public Workspace create(UUID userId, String name) {
        UUID id = UUID.randomUUID();
        return tx.workspace(id, userId, j -> {
            j.update("INSERT INTO workspaces (id, name) VALUES (?, ?)", id, name);
            j.update("INSERT INTO memberships (workspace_id, user_id, role) VALUES (?, ?, 'OWNER')", id, userId);
            audit(j, id, "user:" + userId, "workspace.create", "workspace:" + id);
            return new Workspace(id, name, Role.OWNER);
        });
    }

    public List<Workspace> list(UUID userId) {
        return tx.user(userId, j -> j.query(
                "SELECT w.id, w.name, m.role FROM workspaces w "
                        + "JOIN memberships m ON m.workspace_id = w.id AND m.user_id = ? ORDER BY w.created_at",
                (rs, n) -> new Workspace(rs.getObject("id", UUID.class), rs.getString("name"),
                        Role.valueOf(rs.getString("role"))),
                userId));
    }

    // ------------------------------------------------------------ members

    public List<Member> members(Principal p, UUID ws) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            return j.query("SELECT m.user_id, u.email, m.role FROM memberships m JOIN users u ON u.id = m.user_id "
                            + "WHERE m.workspace_id = ? ORDER BY m.created_at",
                    (rs, n) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("email"),
                            Role.valueOf(rs.getString("role"))), ws);
        });
    }

    public Member addMember(Principal p, UUID ws, String email, Role role) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.OWNER);
            var found = j.queryForList("SELECT id, email FROM auth_find_user(?)", email.trim());
            if (found.isEmpty()) throw ApiException.notFound("No user with that email");
            UUID userId = (UUID) found.get(0).get("id");
            try {
                j.update("INSERT INTO memberships (workspace_id, user_id, role) VALUES (?, ?, ?)", ws, userId, role.name());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("Already a member");
            }
            audit(j, ws, p.actor(), "member.add", "user:" + userId);
            return new Member(userId, (String) found.get(0).get("email"), role);
        });
    }

    public void removeMember(Principal p, UUID ws, UUID userId) {
        tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.OWNER);
            Integer owners = j.queryForObject(
                    "SELECT count(*) FROM memberships WHERE workspace_id = ? AND role = 'OWNER' AND user_id <> ?",
                    Integer.class, ws, userId);
            if (owners != null && owners == 0) throw ApiException.conflict("A workspace needs at least one owner");
            if (j.update("DELETE FROM memberships WHERE workspace_id = ? AND user_id = ?", ws, userId) == 0) {
                throw ApiException.notFound("Not a member");
            }
            audit(j, ws, p.actor(), "member.remove", "user:" + userId);
            return null;
        });
    }

    // ------------------------------------------------------------ api keys

    /** The key is "gb_{workspaceId}_{secret}"; only its SHA-256 is stored and it is shown once. */
    public CreatedApiKey createApiKey(Principal p, UUID ws, String label, List<String> scopes) {
        if (scopes.isEmpty() || !SCOPES.containsAll(scopes)) {
            throw ApiException.badRequest("scopes must be a non-empty subset of " + SCOPES);
        }
        String key = "gb_" + ws + "_" + Hashing.randomSecret();
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.OWNER);
            UUID id = j.queryForObject(
                    "INSERT INTO api_keys (workspace_id, key_hash, label, scopes) VALUES (?, ?, ?, ?) RETURNING id",
                    UUID.class, ws, Hashing.sha256Hex(key), label, scopes.toArray(String[]::new));
            audit(j, ws, p.actor(), "apikey.create", "apikey:" + id);
            return new CreatedApiKey(id, label, scopes, key);
        });
    }

    public List<ApiKeyInfo> listApiKeys(Principal p, UUID ws) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.OWNER);
            return j.query("SELECT id, label, scopes, last_used_at FROM api_keys WHERE workspace_id = ? ORDER BY created_at",
                    (rs, n) -> new ApiKeyInfo(rs.getObject("id", UUID.class), rs.getString("label"),
                            List.of((String[]) rs.getArray("scopes").getArray()),
                            rs.getObject("last_used_at", OffsetDateTime.class)), ws);
        });
    }

    public void deleteApiKey(Principal p, UUID ws, UUID keyId) {
        tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.OWNER);
            if (j.update("DELETE FROM api_keys WHERE id = ? AND workspace_id = ?", keyId, ws) == 0) {
                throw ApiException.notFound("No such key");
            }
            audit(j, ws, p.actor(), "apikey.delete", "apikey:" + keyId);
            return null;
        });
    }

    // ------------------------------------------------------------ access control

    /** Which workspace a request targets: bound to the key for API keys, the X-Workspace-Id header for users. */
    public static UUID resolve(Principal p, UUID header) {
        if (p.isApiKey()) {
            if (header != null && !header.equals(p.workspaceId())) throw ApiException.notFound("No such workspace");
            return p.workspaceId();
        }
        if (header == null) throw ApiException.badRequest("X-Workspace-Id header is required");
        return header;
    }

    /**
     * Service-layer role check (RLS enforces tenancy again underneath). Non-members get 404 so that
     * workspace ids cannot be probed.
     */
    public static Role require(JdbcTemplate j, Principal p, UUID ws, Role min) {
        Role role;
        if (p.isApiKey()) {
            if (!ws.equals(p.workspaceId())) throw ApiException.notFound("No such workspace");
            role = p.scopes().contains("admin") ? Role.OWNER : p.scopes().contains("run") ? Role.EDITOR : Role.VIEWER;
        } else {
            var rows = j.queryForList("SELECT role FROM memberships WHERE workspace_id = ? AND user_id = ?", ws, p.userId());
            if (rows.isEmpty()) throw ApiException.notFound("No such workspace");
            role = Role.valueOf((String) rows.get(0).get("role"));
        }
        if (!role.atLeast(min)) throw ApiException.forbidden("Requires role " + min);
        return role;
    }

    public static void audit(JdbcTemplate j, UUID ws, String actor, String action, String entity) {
        j.update("INSERT INTO audit_log (workspace_id, actor, action, entity) VALUES (?, ?, ?, ?)", ws, actor, action, entity);
    }
}
