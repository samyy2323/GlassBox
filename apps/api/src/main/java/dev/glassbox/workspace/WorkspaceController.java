package dev.glassbox.workspace;

import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.tenancy.Role;
import dev.glassbox.workspace.WorkspaceService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces")
@Profile("api")
public class WorkspaceController {
    public record CreateWorkspace(@NotBlank @Size(max = 100) String name) {}
    public record AddMember(@NotBlank @Email String email, @NotNull Role role) {}
    public record CreateApiKey(@NotBlank @Size(max = 100) String label, @NotNull List<String> scopes) {}

    private final WorkspaceService service;

    public WorkspaceController(WorkspaceService service) {
        this.service = service;
    }

    @GetMapping
    public List<Workspace> list(@AuthenticationPrincipal Principal p) {
        return service.list(user(p));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Workspace create(@AuthenticationPrincipal Principal p, @Valid @RequestBody CreateWorkspace body) {
        return service.create(user(p), body.name());
    }

    @GetMapping("/{id}/members")
    public List<Member> members(@AuthenticationPrincipal Principal p, @PathVariable UUID id) {
        return service.members(p, id);
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public Member addMember(@AuthenticationPrincipal Principal p, @PathVariable UUID id, @Valid @RequestBody AddMember body) {
        return service.addMember(p, id, body.email(), body.role());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Principal p, @PathVariable UUID id, @PathVariable UUID userId) {
        service.removeMember(p, id, userId);
    }

    @GetMapping("/{id}/api-keys")
    public List<ApiKeyInfo> apiKeys(@AuthenticationPrincipal Principal p, @PathVariable UUID id) {
        return service.listApiKeys(p, id);
    }

    @PostMapping("/{id}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKey createApiKey(@AuthenticationPrincipal Principal p, @PathVariable UUID id,
                                      @Valid @RequestBody CreateApiKey body) {
        return service.createApiKey(p, id, body.label(), body.scopes());
    }

    @DeleteMapping("/{id}/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApiKey(@AuthenticationPrincipal Principal p, @PathVariable UUID id, @PathVariable UUID keyId) {
        service.deleteApiKey(p, id, keyId);
    }

    /** Workspace lists and creation belong to a person, not to a workspace-bound API key. */
    private static UUID user(Principal p) {
        if (p.isApiKey()) throw ApiException.forbidden("Not available to API keys");
        return p.userId();
    }
}
