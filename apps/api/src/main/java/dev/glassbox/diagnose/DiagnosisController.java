package dev.glassbox.diagnose;

import dev.glassbox.auth.Principal;
import dev.glassbox.workspace.WorkspaceService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/experiments")
@Profile("api")
public class DiagnosisController {
    private final DiagnosisService diagnosis;

    public DiagnosisController(DiagnosisService diagnosis) {
        this.diagnosis = diagnosis;
    }

    @GetMapping("/{id}/diagnosis")
    public DiagnosisService.Diagnosis diagnosis(@AuthenticationPrincipal Principal p, @RequestHeader(value = "X-Workspace-Id", required = false) UUID ws,
                                                @PathVariable UUID id, @RequestParam UUID run) {
        return diagnosis.diagnose(p, WorkspaceService.resolve(p, ws), id, run);
    }
}
