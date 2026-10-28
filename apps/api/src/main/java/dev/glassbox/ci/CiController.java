package dev.glassbox.ci;

import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.workspace.WorkspaceService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** The quality gate for pull requests (SPEC 14). An API key with the {@code run} scope may start a gate; {@code read} is enough to ask how it went. */
@RestController
@Profile("api")
public class CiController {
    private static final String WS = "X-Workspace-Id";

    private final CiGateService gates;

    public CiController(CiGateService gates) {
        this.gates = gates;
    }

    @PostMapping("/api/ci/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CiGateService.Started run(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestBody CiGateService.Request body) {
        return gates.start(p, WorkspaceService.resolve(p, ws), body);
    }

    /** {@code format}: json (default), md (Markdown for a pull request comment) or junit (XML for the CI test report). */
    @GetMapping("/api/ci/runs/{id}")
    public ResponseEntity<?> result(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id,
                                    @RequestParam(defaultValue = "json") String format) {
        CiGateService.Result r = gates.result(p, WorkspaceService.resolve(p, ws), id);
        return switch (format) {
            case "json" -> ResponseEntity.ok(r);
            case "md" -> ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8")).body(GateReport.markdown(r.name() + " on " + r.testset(), r.state(), r.checks(), r.note()));
            case "junit" -> ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(GateReport.junit("glassbox: " + r.name(), r.state(), r.checks(), r.note()));
            default -> throw ApiException.badRequest("format must be json, md or junit");
        };
    }
}
