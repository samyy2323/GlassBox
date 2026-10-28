package dev.glassbox.audit;

import static dev.glassbox.workspace.WorkspaceService.require;

import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import dev.glassbox.workspace.WorkspaceService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SPEC 12: the audit log of sensitive actions, newest first. Owners only; the table is append-only for the application role. */
@RestController
@Profile("api")
public class AuditController {
    private static final String WS = "X-Workspace-Id";

    public record Entry(UUID id, String actor, String action, String entity, OffsetDateTime at) {}

    /** {@code next} is the value to pass as {@code before} for the following page, or null at the end. */
    public record Page(List<Entry> items, OffsetDateTime next) {}

    private final Tx tx;

    public AuditController(Tx tx) {
        this.tx = tx;
    }

    @GetMapping("/api/audit")
    public Page list(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestParam(required = false) String action,
                     @RequestParam(required = false) OffsetDateTime before, @RequestParam(defaultValue = "50") int limit) {
        UUID workspace = WorkspaceService.resolve(p, ws);
        if (limit < 1 || limit > 200) throw ApiException.badRequest("limit must be between 1 and 200");
        return tx.workspace(workspace, p.userId(), j -> {
            require(j, p, workspace, Role.OWNER);
            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT id, actor, action, entity, at FROM audit_log WHERE true");
            if (action != null && !action.isBlank()) {
                sql.append(" AND action LIKE ? ESCAPE '!'");
                args.add(action.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            }
            if (before != null) {
                sql.append(" AND at < ?");
                args.add(before);
            }
            sql.append(" ORDER BY at DESC, id DESC LIMIT ?");
            args.add(limit + 1);
            List<Entry> rows = j.query(sql.toString(), (rs, i) -> new Entry(rs.getObject("id", UUID.class), rs.getString("actor"), rs.getString("action"),
                    rs.getString("entity"), rs.getObject("at", OffsetDateTime.class)), args.toArray());
            OffsetDateTime next = rows.size() > limit ? rows.get(limit - 1).at() : null;
            return new Page(rows.size() > limit ? rows.subList(0, limit) : rows, next);
        });
    }
}
