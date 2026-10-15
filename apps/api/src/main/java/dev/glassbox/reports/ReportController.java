package dev.glassbox.reports;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.workspace.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("api")
public class ReportController {
    private static final String WS = "X-Workspace-Id";

    public record CreateRequest(String kind, UUID refId, Boolean redact) {}

    public record LinkRequest(Integer days) {}

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping("/api/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportService.Meta create(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestBody CreateRequest r) {
        return reports.create(p, WorkspaceService.resolve(p, ws), r.kind(), r.refId(), Boolean.TRUE.equals(r.redact()));
    }

    @GetMapping("/api/reports")
    public List<ReportService.Meta> list(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws) {
        return reports.list(p, WorkspaceService.resolve(p, ws));
    }

    @GetMapping("/api/reports/{id}")
    public JsonNode get(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return reports.snapshot(p, WorkspaceService.resolve(p, ws), id);
    }

    @GetMapping("/api/reports/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return pdfResponse(reports.pdf(p, WorkspaceService.resolve(p, ws), id), "report.pdf");
    }

    @DeleteMapping("/api/reports/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        reports.delete(p, WorkspaceService.resolve(p, ws), id);
    }

    @PostMapping("/api/reports/{id}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportService.Created createLink(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id,
                                            @RequestBody(required = false) LinkRequest r) {
        return reports.createLink(p, WorkspaceService.resolve(p, ws), id, r == null ? null : r.days());
    }

    @GetMapping("/api/reports/{id}/links")
    public List<ReportService.Link> links(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id) {
        return reports.links(p, WorkspaceService.resolve(p, ws), id);
    }

    @DeleteMapping("/api/reports/{id}/links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @PathVariable UUID id, @PathVariable UUID linkId) {
        reports.revoke(p, WorkspaceService.resolve(p, ws), id, linkId);
    }

    // ------------------------------------------------------------------ no sign-in: the token is the only credential, and it opens one snapshot

    @GetMapping("/api/public/reports/{token}")
    public ResponseEntity<ReportService.Shared> shared(@PathVariable String token) {
        var shared = reports.shared(token).orElseThrow(() -> ApiException.notFound("This link is not valid"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Robots-Tag", "noindex").body(shared);
    }

    @GetMapping("/api/public/reports/{token}/pdf")
    public ResponseEntity<byte[]> sharedPdf(@PathVariable String token) {
        return pdfResponse(reports.sharedPdf(token), "report.pdf");
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] bytes, String name) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore()).header("X-Robots-Tag", "noindex")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").body(bytes);
    }
}
