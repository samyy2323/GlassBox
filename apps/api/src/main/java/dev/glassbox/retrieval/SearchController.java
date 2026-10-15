package dev.glassbox.retrieval;

import dev.glassbox.auth.Principal;
import dev.glassbox.rerank.Reranker;
import dev.glassbox.tenancy.Role;
import dev.glassbox.workspace.WorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Retrieval without generation: what X-Ray, the CLI gate and debugging use. */
@RestController
@RequestMapping("/api/search")
@Profile("api")
public class SearchController {
    public record SearchRequest(@NotBlank @Size(max = 2000) String query, RetrievalParams.Mode mode, Reranker.Type reranker,
                                @Min(1) @Max(50) Integer topN, @Min(100) @Max(32000) Integer tokenBudget) {}

    private final RetrievalPipeline pipeline;
    private final WorkspaceService workspaces;
    private final WorkspaceDefaults workspaceDefaults;
    private final String defaultModel;

    public SearchController(RetrievalPipeline pipeline, WorkspaceService workspaces, WorkspaceDefaults workspaceDefaults,
                            @Value("${glassbox.embed.default-model}") String defaultModel) {
        this.workspaceDefaults = workspaceDefaults;
        this.pipeline = pipeline;
        this.workspaces = workspaces;
        this.defaultModel = defaultModel;
    }

    @PostMapping
    public RetrievalPipeline.Result search(@AuthenticationPrincipal Principal p,
                                           @RequestHeader(value = "X-Workspace-Id", required = false) UUID header,
                                           @Valid @RequestBody SearchRequest body) {
        UUID ws = WorkspaceService.resolve(p, header);
        workspaces.authorize(p, ws, Role.VIEWER);
        PipelineConfig config = workspaceDefaults.promoted(ws).orElseGet(() -> PipelineConfig.defaults(defaultModel));
        if (body.mode() != null) config = config.withMode(body.mode());
        if (body.reranker() != null) config = config.withReranker(body.reranker());
        if (body.topN() != null) config = config.withTopN(body.topN());
        if (body.tokenBudget() != null) config = config.withTokenBudget(body.tokenBudget());
        return pipeline.retrieve(ws, body.query(), config);
    }
}
