package dev.glassbox.ingest;

import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.tenancy.Role;
import dev.glassbox.workspace.WorkspaceService;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** GET /api/events: live document status for the caller's workspace. Events: "ready" once, then "document" per change. */
@RestController
@RequestMapping("/api/events")
@Profile("api")
public class EventsController {
    private static final long TIMEOUT_MS = 30 * 60_000L; // clients reconnect

    private final DocumentEventsHub hub;
    private final WorkspaceService workspaces;

    public EventsController(DocumentEventsHub hub, WorkspaceService workspaces) {
        this.hub = hub;
        this.workspaces = workspaces;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal Principal p,
                             @RequestHeader(value = "X-Workspace-Id", required = false) UUID header) throws IOException {
        UUID ws = WorkspaceService.resolve(p, header);
        workspaces.authorize(p, ws, Role.VIEWER);

        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Runnable unsubscribe = hub.subscribe(ws, payload -> {
            try {
                synchronized (emitter) {
                    if (payload == null) emitter.send(SseEmitter.event().comment("keepalive"));
                    else emitter.send(SseEmitter.event().name("document").data(payload));
                }
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        });
        if (unsubscribe == null) throw ApiException.tooManyRequests("Too many open event streams for this workspace", 10);
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(e -> unsubscribe.run());
        emitter.send(SseEmitter.event().name("ready").data("{}"));
        return emitter;
    }
}
