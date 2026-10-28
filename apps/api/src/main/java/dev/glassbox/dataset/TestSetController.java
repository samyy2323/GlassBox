package dev.glassbox.dataset;

import dev.glassbox.auth.Principal;
import dev.glassbox.dataset.TestSetModel.*;
import dev.glassbox.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Profile("api")
public class TestSetController {
    private static final String WS = "X-Workspace-Id";

    public record GenerateRequest(String name, Integer count, Map<String, Double> mix, List<UUID> documentIds, Long seed) {}

    public record PatchRequest(String question, String expectedAnswer, String reviewStatus, List<SpanInput> spans, String qtype) {}

    public record AddRequest(String question, String expectedAnswer, String qtype, List<SpanInput> spans) {}

    public record FinalizeRequest(Boolean allowPending) {}

    public record BulkResult(int accepted) {}

    private final TestSetService sets;

    public TestSetController(TestSetService sets) {
        this.sets = sets;
    }

    @PostMapping("/testsets/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GenerateResult generate(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestBody GenerateRequest r) {
        return sets.generate(p, WorkspaceService.resolve(p, ws), r.name(), r.count(), r.mix(), r.documentIds(), r.seed());
    }

    @GetMapping("/testsets")
    public List<TestSet> list(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws) {
        return sets.list(p, WorkspaceService.resolve(p, ws));
    }

    @GetMapping("/testsets/{id}")
    public TestSet get(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return sets.get(p, WorkspaceService.resolve(p, ws), id);
    }

    @GetMapping("/testsets/{id}/questions")
    public QuestionPage questions(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id,
                                  @RequestParam(required = false) String status, @RequestParam(required = false) String qtype,
                                  @RequestParam(required = false) String flag, @RequestParam(defaultValue = "100") int limit,
                                  @RequestParam(required = false) String cursor) {
        return sets.questions(p, WorkspaceService.resolve(p, ws), id, status, qtype, flag, limit, cursor);
    }

    @PostMapping("/testsets/{id}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public Question add(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id, @RequestBody AddRequest r) {
        return sets.addManual(p, WorkspaceService.resolve(p, ws), id, r.question(), r.expectedAnswer(), r.qtype(), r.spans());
    }

    @PatchMapping("/questions/{id}")
    public Question patch(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id, @RequestBody PatchRequest r) {
        return sets.patch(p, WorkspaceService.resolve(p, ws), id, r.question(), r.expectedAnswer(), r.reviewStatus(), r.spans(), r.qtype());
    }

    @PostMapping("/testsets/{id}/bulk-accept")
    public BulkResult bulkAccept(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return new BulkResult(sets.bulkAccept(p, WorkspaceService.resolve(p, ws), id));
    }

    @PostMapping("/testsets/{id}/finalize")
    public TestSet finalizeSet(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id,
                               @RequestBody(required = false) FinalizeRequest r) {
        return sets.finalizeSet(p, WorkspaceService.resolve(p, ws), id, r != null && Boolean.TRUE.equals(r.allowPending()));
    }

    @PostMapping("/testsets/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public TestSet newVersion(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return sets.newVersion(p, WorkspaceService.resolve(p, ws), id);
    }

    /** JSON lines: a header, then one accepted question per line. See {@link TestSetService#exportJsonl}. */
    @GetMapping(value = "/testsets/{id}/export", produces = "application/x-ndjson")
    public String export(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return sets.exportJsonl(p, WorkspaceService.resolve(p, ws), id);
    }

    /** The body is the exported file. {@code name} overrides the name in its header; {@code finalize=true} makes the new set final. */
    @PostMapping(value = "/testsets/import", consumes = {"application/x-ndjson", "text/plain"})
    @ResponseStatus(HttpStatus.CREATED)
    public TestSetService.ImportResult importSet(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestBody String body,
                                                 @RequestParam(required = false) String name, @RequestParam(defaultValue = "false") boolean finalize) {
        return sets.importJsonl(p, WorkspaceService.resolve(p, ws), name, body, finalize);
    }

    @GetMapping("/testsets/{id}/diff")
    public Diff diff(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id, @RequestParam UUID against) {
        return sets.diff(p, WorkspaceService.resolve(p, ws), id, against);
    }
}
