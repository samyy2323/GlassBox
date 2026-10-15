package dev.glassbox.generate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.ratelimit.RateLimiter;
import dev.glassbox.rerank.Reranker;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.WorkspaceDefaults;
import dev.glassbox.tenancy.Role;
import dev.glassbox.workspace.WorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * POST /api/chat streams server-sent events: trace (stage spans as they finish), token, citations, done, error.
 * Everything that can be refused (auth, role, rate limit, busy) is refused with a normal HTTP error BEFORE the stream
 * starts, so clients only have to handle SSE for accepted requests.
 */
@RestController
@RequestMapping("/api/chat")
@Profile("api")
public class ChatController {
    public record ChatRequest(@NotBlank @Size(max = 2000) String question, Reranker.Type reranker,
                              @Min(1) @Max(20) Integer topN) {}

    private static final long STREAM_TIMEOUT_MS = 5 * 60_000L;

    private final WorkspaceService workspaces;
    private final WorkspaceDefaults workspaceDefaults;
    private final LlmGate gate;
    private final RateLimiter rateLimiter;
    private final ChatService chat;
    private final ExecutorService executor;
    private final ObjectMapper json;
    private final Reranker.Type defaultReranker;
    private final int defaultTopN;
    private final int tokenBudget;

    public ChatController(WorkspaceService workspaces, LlmGate gate, RateLimiter chatRateLimiter, ChatService chat,
                          ExecutorService chatExecutor, ObjectMapper json,
                          @Value("${glassbox.chat.reranker}") Reranker.Type defaultReranker,
                          @Value("${glassbox.chat.top-n}") int defaultTopN,
                          @Value("${glassbox.chat.token-budget}") int tokenBudget, WorkspaceDefaults workspaceDefaults) {
        this.workspaceDefaults = workspaceDefaults;
        this.workspaces = workspaces;
        this.gate = gate;
        this.rateLimiter = chatRateLimiter;
        this.chat = chat;
        this.executor = chatExecutor;
        this.json = json;
        this.defaultReranker = defaultReranker;
        this.defaultTopN = defaultTopN;
        this.tokenBudget = tokenBudget;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@AuthenticationPrincipal Principal p,
                           @RequestHeader(value = "X-Workspace-Id", required = false) UUID header,
                           @Valid @RequestBody ChatRequest body) {
        UUID ws = WorkspaceService.resolve(p, header);
        workspaces.authorize(p, ws, Role.VIEWER);
        if (!rateLimiter.tryAcquire("ws:" + ws) || !rateLimiter.tryAcquire("who:" + p.actor())) {
            throw ApiException.tooManyRequests("Too many questions. Please slow down.", rateLimiter.secondsUntilToken());
        }
        LlmGate.Permit permit = gate.acquire();

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean cancelled = new AtomicBoolean();
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(e -> cancelled.set(true));

        ChatService.Sink sink = new ChatService.Sink() {
            @Override
            public void send(String event, Object data) {
                try {
                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().name(event).data(json.writeValueAsString(data)));
                    }
                } catch (IOException | IllegalStateException e) { // the client disconnected
                    cancelled.set(true);
                    throw new LlmProvider.Cancelled();
                }
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };
        var promoted = workspaceDefaults.promoted(ws);
        ChatService.Options options = new ChatService.Options(body.reranker() != null ? body.reranker() : promoted.map(PipelineConfig::reranker).orElse(defaultReranker),
                body.topN() != null ? body.topN() : promoted.map(PipelineConfig::topN).orElse(defaultTopN), promoted.map(PipelineConfig::tokenBudget).orElse(tokenBudget));
        try {
            executor.submit(() -> {
                try {
                    chat.answer(ws, p.userId(), body.question(), options, sink);
                } finally {
                    permit.close();
                    emitter.complete();
                }
            });
        } catch (RuntimeException e) { // executor refused: do not leak the permit
            permit.close();
            throw e;
        }
        return emitter;
    }
}
