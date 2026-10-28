package dev.glassbox.observability;

import dev.glassbox.cost.BudgetService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code llm_tokens_total{provider,model,direction}} and {@code llm_cost_usd_total{provider,model}}, recorded where the numbers already exist: after a chat answer and after
 * each scored question of an experiment. Tokens are counted in the project's own units (see TokenIndex), which run below a real tokenizer's count; the cost is what the
 * workspace's price table says, so a local model records tokens and a cost of zero.
 */
@Component
public class LlmMetrics {
    public static final String PROVIDER = "openai-compatible";

    private final MeterRegistry registry;
    private final BudgetService budgets;

    public LlmMetrics(MeterRegistry registry, BudgetService budgets) {
        this.registry = registry;
        this.budgets = budgets;
    }

    public void record(String model, long tokensIn, long tokensOut, double usd) {
        registry.counter("llm_tokens_total", "provider", PROVIDER, "model", model, "direction", "input").increment(Math.max(0, tokensIn));
        registry.counter("llm_tokens_total", "provider", PROVIDER, "model", model, "direction", "output").increment(Math.max(0, tokensOut));
        registry.counter("llm_cost_usd_total", "provider", PROVIDER, "model", model).increment(Math.max(0, usd));
    }

    /** For a caller that has only the token counts: the workspace's price for the model gives the cost. */
    public void record(UUID workspace, String model, long tokensIn, long tokensOut) {
        double usd = 0;
        try {
            var price = budgets.price(workspace, model);
            usd = (tokensIn * price.promptUsdPerMillion() + tokensOut * price.completionUsdPerMillion()) / 1_000_000.0;
        } catch (RuntimeException e) {
            // a price lookup must never fail an answer; the tokens are still counted
        }
        record(model, tokensIn, tokensOut, usd);
    }
}
