package dev.glassbox.cost;

import dev.glassbox.auth.Principal;
import dev.glassbox.cost.BudgetService.Budget;
import dev.glassbox.cost.BudgetService.ModelPrice;
import dev.glassbox.workspace.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/budget")
@Profile("api")
public class BudgetController {
    private static final String WS = "X-Workspace-Id";

    private final BudgetService budgets;

    public BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    @GetMapping
    public BudgetService.Overview overview(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws) {
        return budgets.overview(p, WorkspaceService.resolve(p, ws));
    }

    @PutMapping
    public Budget set(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestBody Budget body) {
        return budgets.setBudget(p, WorkspaceService.resolve(p, ws), body);
    }

    @PutMapping("/prices")
    public List<ModelPrice> setPrice(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestBody ModelPrice body) {
        return budgets.setPrice(p, WorkspaceService.resolve(p, ws), body);
    }

    @DeleteMapping("/prices")
    public List<ModelPrice> deletePrice(@AuthenticationPrincipal Principal p, @RequestHeader(value = WS, required = false) UUID ws, @RequestParam String model) {
        return budgets.deletePrice(p, WorkspaceService.resolve(p, ws), model);
    }
}
