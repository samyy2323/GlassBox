package dev.glassbox.ingest;

import dev.glassbox.auth.Principal;
import dev.glassbox.ingest.DocumentService.*;
import dev.glassbox.workspace.WorkspaceService;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Workspace is chosen with the X-Workspace-Id header (users) or implied by the API key. */
@RestController
@RequestMapping("/api/documents")
@Profile("api")
public class DocumentController {
    private static final String WS = "X-Workspace-Id";

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<UploadResult> upload(@AuthenticationPrincipal Principal p,
                                               @RequestHeader(value = WS, required = false) UUID ws,
                                               @RequestParam("file") MultipartFile file) throws IOException {
        UploadResult r = documents.upload(p, WorkspaceService.resolve(p, ws), file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.status(r.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }

    @GetMapping
    public DocumentPage list(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws,
                             @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String cursor) {
        return documents.list(p, WorkspaceService.resolve(p, ws), limit, cursor);
    }

    @PutMapping(value = "/{id}", consumes = "multipart/form-data")
    public DocumentDto replace(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws,
                               @PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        return documents.replace(p, WorkspaceService.resolve(p, ws), id, file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping("/{id}/content")
    public DocumentContent content(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws,
                                   @PathVariable UUID id) {
        return documents.content(p, WorkspaceService.resolve(p, ws), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws,
                       @PathVariable UUID id) {
        documents.delete(p, WorkspaceService.resolve(p, ws), id);
    }
}
