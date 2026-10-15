package dev.glassbox.feedback;

import dev.glassbox.auth.Principal;
import dev.glassbox.dataset.TestSetModel.SpanInput;
import dev.glassbox.feedback.FeedbackService.*;
import dev.glassbox.workspace.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("api")
public class FeedbackController {
    private static final String WS = "X-Workspace-Id";

    public record RateRequest(String rating, String comment) {}

    public record DiagnoseRequest(List<SpanInput> spans) {}

    public record ConvertRequest(UUID testsetId, String question, String expectedAnswer, String qtype, List<SpanInput> spans) {}

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @PostMapping("/api/traces/{id}/feedback")
    public Item rate(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id, @RequestBody RateRequest r) {
        return feedback.give(p, WorkspaceService.resolve(p, ws), id, r.rating(), r.comment());
    }

    @GetMapping("/api/feedback")
    public List<Item> list(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestParam(required = false) String status,
                           @RequestParam(required = false) String rating) {
        return feedback.list(p, WorkspaceService.resolve(p, ws), status, rating);
    }

    @GetMapping("/api/feedback/summary")
    public Summary summary(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws) {
        return feedback.summary(p, WorkspaceService.resolve(p, ws));
    }

    @GetMapping("/api/feedback/{id}")
    public Detail detail(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return feedback.detail(p, WorkspaceService.resolve(p, ws), id);
    }

    @PostMapping("/api/feedback/{id}/diagnose")
    public Diagnosed diagnose(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id, @RequestBody DiagnoseRequest r) {
        return feedback.diagnose(p, WorkspaceService.resolve(p, ws), id, r.spans());
    }

    @PostMapping("/api/feedback/{id}/convert")
    public Converted convert(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id, @RequestBody ConvertRequest r) {
        return feedback.convert(p, WorkspaceService.resolve(p, ws), id, r.testsetId(), r.expectedAnswer(), r.qtype(), r.spans(), r.question());
    }

    @PostMapping("/api/feedback/{id}/dismiss")
    public Item dismiss(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return feedback.dismiss(p, WorkspaceService.resolve(p, ws), id);
    }
}
