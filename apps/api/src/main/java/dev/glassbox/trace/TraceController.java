package dev.glassbox.trace;

import dev.glassbox.auth.Principal;
import dev.glassbox.trace.TraceModel.Page;
import dev.glassbox.trace.TraceModel.Trace;
import dev.glassbox.workspace.WorkspaceService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/traces")
@Profile("api")
public class TraceController {
    private final TraceService traces;

    public TraceController(TraceService traces) {
        this.traces = traces;
    }

    @GetMapping("/{id}")
    public Trace get(@AuthenticationPrincipal Principal p, @RequestHeader(value = "X-Workspace-Id", required = false) UUID ws,
                     @PathVariable UUID id) {
        return traces.get(p, WorkspaceService.resolve(p, ws), id);
    }

    @GetMapping
    public Page list(@AuthenticationPrincipal Principal p, @RequestHeader(value = "X-Workspace-Id", required = false) UUID ws,
                     @RequestParam(defaultValue = "30") int limit, @RequestParam(required = false) String cursor,
                     @RequestParam(required = false) String kind) {
        return traces.list(p, WorkspaceService.resolve(p, ws), limit, cursor, kind);
    }
}
