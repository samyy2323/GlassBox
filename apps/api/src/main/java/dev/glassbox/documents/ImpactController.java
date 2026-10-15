package dev.glassbox.documents;

import dev.glassbox.auth.Principal;
import dev.glassbox.workspace.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/documents/{id}/versions")
@Profile("api")
public class ImpactController {
    private static final String WS = "X-Workspace-Id";

    private final ImpactService impact;

    public ImpactController(ImpactService impact) {
        this.impact = impact;
    }

    @GetMapping
    public List<ImpactService.Version> versions(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return impact.versions(p, WorkspaceService.resolve(p, ws), id);
    }

    @GetMapping("/{version}/diff")
    public ImpactService.DiffView diff(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id, @PathVariable int version) {
        return impact.diff(p, WorkspaceService.resolve(p, ws), id, version);
    }

    @GetMapping("/{version}/impact")
    public ImpactService.Report report(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id, @PathVariable int version) {
        return impact.impact(p, WorkspaceService.resolve(p, ws), id, version);
    }
}
