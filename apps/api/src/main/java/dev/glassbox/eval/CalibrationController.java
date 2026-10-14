package dev.glassbox.eval;

import dev.glassbox.auth.Principal;
import dev.glassbox.eval.CalibrationService.*;
import dev.glassbox.workspace.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/calibration")
@Profile("api")
public class CalibrationController {
    private static final String WS = "X-Workspace-Id";

    public record SampleRequest(String metric, Integer size, UUID experimentId) {}

    public record LabelsRequest(List<HumanLabel> labels) {}

    private final CalibrationService calibration;

    public CalibrationController(CalibrationService calibration) {
        this.calibration = calibration;
    }

    @PostMapping("/sample")
    public Sample sample(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestBody SampleRequest r) {
        return calibration.sample(p, WorkspaceService.resolve(p, ws), r.metric(), r.size(), r.experimentId());
    }

    @PostMapping("/labels")
    public List<MetricSummary> labels(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestBody LabelsRequest r) {
        return calibration.label(p, WorkspaceService.resolve(p, ws), r.labels());
    }

    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws) {
        return calibration.summary(p, WorkspaceService.resolve(p, ws));
    }
}
