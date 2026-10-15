package dev.glassbox.autotune;

import dev.glassbox.auth.Principal;
import dev.glassbox.autotune.AutotuneModel.*;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.workspace.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/autotune")
@Profile("api")
public class AutotuneController {
    private static final String WS = "X-Workspace-Id";

    private final AutotuneService autotune;

    public AutotuneController(AutotuneService autotune) {
        this.autotune = autotune;
    }

    @PostMapping("/estimate")
    public Estimate estimate(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestBody Request r) {
        return autotune.estimate(p, WorkspaceService.resolve(p, ws), r);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public View create(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestBody Request r) {
        return autotune.create(p, WorkspaceService.resolve(p, ws), r);
    }

    @GetMapping
    public List<Summary> list(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws) {
        return autotune.list(p, WorkspaceService.resolve(p, ws));
    }

    @GetMapping("/{id}")
    public View get(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return autotune.get(p, WorkspaceService.resolve(p, ws), id);
    }

    @PostMapping("/{id}/cancel")
    public void cancel(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        autotune.cancel(p, WorkspaceService.resolve(p, ws), id);
    }

    @PostMapping("/{id}/promote")
    public PipelineConfig promote(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return autotune.promote(p, WorkspaceService.resolve(p, ws), id);
    }
}
