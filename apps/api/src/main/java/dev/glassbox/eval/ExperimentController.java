package dev.glassbox.eval;

import dev.glassbox.auth.Principal;
import dev.glassbox.eval.ExperimentModel.*;
import dev.glassbox.workspace.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/experiments")
@Profile("api")
public class ExperimentController {
    private static final String WS = "X-Workspace-Id";

    public record EstimateRequest(UUID testsetId, String mode, List<ConfigInput> configs, Integer questionLimit) {}

    public record CreateRequest(UUID testsetId, String name, String mode, List<ConfigInput> configs, Integer questionLimit) {}

    private final ExperimentService experiments;

    public ExperimentController(ExperimentService experiments) {
        this.experiments = experiments;
    }

    @PostMapping("/estimate")
    public Estimate estimate(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestBody EstimateRequest r) {
        return experiments.estimate(p, WorkspaceService.resolve(p, ws), r.testsetId(), r.configs(), r.mode(), r.questionLimit());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExperimentView create(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestBody CreateRequest r) {
        return experiments.create(p, WorkspaceService.resolve(p, ws), r.testsetId(), r.name(), r.mode(), r.configs(), r.questionLimit());
    }

    @GetMapping
    public List<ExperimentView> list(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws) {
        return experiments.list(p, WorkspaceService.resolve(p, ws));
    }

    @GetMapping("/compare")
    public ComparisonView compare(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestParam UUID a, @RequestParam UUID b) {
        return experiments.compare(p, WorkspaceService.resolve(p, ws), a, b);
    }

    @GetMapping("/{id}")
    public ExperimentView get(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return experiments.get(p, WorkspaceService.resolve(p, ws), id);
    }

    @GetMapping("/{id}/results")
    public List<ResultView> results(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id, @RequestParam UUID run) {
        return experiments.results(p, WorkspaceService.resolve(p, ws), id, run);
    }

    @PostMapping("/{id}/cancel")
    public void cancel(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        experiments.cancel(p, WorkspaceService.resolve(p, ws), id);
    }
}
