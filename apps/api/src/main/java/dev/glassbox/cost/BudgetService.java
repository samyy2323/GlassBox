package dev.glassbox.cost;

import static dev.glassbox.workspace.WorkspaceService.audit;
import static dev.glassbox.workspace.WorkspaceService.require;

import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.cost.CostEstimator.Price;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The price table, the workspace budget, the check made before a run starts and the record made when it ends. */
@Service
public class BudgetService {
    public record Budget(Double monthlyUsd, Double perRunUsd, Double confirmAboveUsd) {}

    public record ModelPrice(String model, double promptUsdPerMillion, double completionUsdPerMillion) {}

    public record Entry(String kind, UUID refId, double estimatedUsd, double actualUsd, long estimatedTokens, long actualTokens, int estimatedCalls, int actualCalls,
                        boolean aborted, java.time.OffsetDateTime createdAt) {}

    /** {@code tokenError} is the mean of (actual - estimated) / estimated over the runs that had a token estimate; null until there is one. */
    public record Overview(Budget budget, double spentThisMonthUsd, long tokensThisMonth, List<ModelPrice> prices, ModelPrice defaults, List<Entry> recent, Double tokenError, int measuredRuns) {}

    private final Tx tx;
    private final double defaultPrompt, defaultCompletion;

    public BudgetService(Tx tx, @Value("${glassbox.cost.prompt-usd-per-million}") double defaultPrompt, @Value("${glassbox.cost.completion-usd-per-million}") double defaultCompletion) {
        this.tx = tx;
        this.defaultPrompt = defaultPrompt;
        this.defaultCompletion = defaultCompletion;
    }

    // ------------------------------------------------------------------ prices

    public Price price(UUID ws, String model) {
        return tx.workspace(ws, null, j -> price(j, model));
    }

    public Price price(JdbcTemplate j, String model) {
        var rows = j.query("SELECT prompt_usd_per_million, completion_usd_per_million FROM pricing WHERE model = ?",
                (rs, n) -> new Price(rs.getDouble(1), rs.getDouble(2)), model);
        return rows.isEmpty() ? new Price(defaultPrompt, defaultCompletion) : rows.get(0);
    }

    // ------------------------------------------------------------------ the overview and its edits

    public Overview overview(Principal p, UUID ws) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            double[] spent = month(j);
            List<Entry> recent = j.query("SELECT kind, ref_id, estimated_usd, actual_usd, estimated_tokens, actual_tokens, estimated_calls, actual_calls, aborted, created_at "
                    + "FROM spend_log ORDER BY created_at DESC LIMIT 20", (rs, n) -> new Entry(rs.getString("kind"), rs.getObject("ref_id", UUID.class), rs.getDouble("estimated_usd"),
                    rs.getDouble("actual_usd"), rs.getLong("estimated_tokens"), rs.getLong("actual_tokens"), rs.getInt("estimated_calls"), rs.getInt("actual_calls"),
                    rs.getBoolean("aborted"), rs.getObject("created_at", java.time.OffsetDateTime.class)));
            return new Overview(budget(j), spent[0], (long) spent[1], prices(j), new ModelPrice("default", defaultPrompt, defaultCompletion), recent, error(recent), measured(recent));
        });
    }

    static Double error(List<Entry> entries) {
        double sum = 0;
        int n = 0;
        for (Entry e : entries) {
            if (e.estimatedTokens() <= 0) continue;
            sum += (e.actualTokens() - e.estimatedTokens()) / (double) e.estimatedTokens();
            n++;
        }
        return n == 0 ? null : sum / n;
    }

    static int measured(List<Entry> entries) {
        return (int) entries.stream().filter(e -> e.estimatedTokens() > 0).count();
    }

    private List<ModelPrice> prices(JdbcTemplate j) {
        return j.query("SELECT model, prompt_usd_per_million, completion_usd_per_million FROM pricing ORDER BY model",
                (rs, n) -> new ModelPrice(rs.getString(1), rs.getDouble(2), rs.getDouble(3)));
    }

    private Budget budget(JdbcTemplate j) {
        var rows = j.query("SELECT monthly_usd, per_run_usd, confirm_above_usd FROM budgets", (rs, n) -> new Budget(dbl(rs.getObject(1)), dbl(rs.getObject(2)), dbl(rs.getObject(3))));
        return rows.isEmpty() ? new Budget(null, null, null) : rows.get(0);
    }

    private static Double dbl(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }

    /** Spent so far this calendar month, in dollars and in tokens. */
    private double[] month(JdbcTemplate j) {
        var r = j.queryForMap("SELECT coalesce(sum(actual_usd), 0) AS usd, coalesce(sum(actual_tokens), 0) AS tokens FROM spend_log WHERE created_at >= date_trunc('month', now())");
        return new double[] {((Number) r.get("usd")).doubleValue(), ((Number) r.get("tokens")).doubleValue()};
    }

    public Budget setBudget(Principal p, UUID ws, Budget b) {
        for (Double v : new Double[] {b.monthlyUsd(), b.perRunUsd(), b.confirmAboveUsd()}) {
            if (v != null && (v < 0 || v.isNaN() || v > 1_000_000)) throw ApiException.badRequest("Amounts must be between 0 and 1,000,000 dollars");
        }
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.OWNER);
            j.update("INSERT INTO budgets (workspace_id, monthly_usd, per_run_usd, confirm_above_usd, updated_at) VALUES (?, ?, ?, ?, now()) "
                    + "ON CONFLICT (workspace_id) DO UPDATE SET monthly_usd = EXCLUDED.monthly_usd, per_run_usd = EXCLUDED.per_run_usd, "
                    + "confirm_above_usd = EXCLUDED.confirm_above_usd, updated_at = now()", ws, b.monthlyUsd(), b.perRunUsd(), b.confirmAboveUsd());
            audit(j, ws, p.actor(), "budget.update", "workspace:" + ws);
            return budget(j);
        });
    }

    public List<ModelPrice> setPrice(Principal p, UUID ws, ModelPrice price) {
        if (price.model() == null || price.model().isBlank() || price.model().length() > 120) throw ApiException.badRequest("A price needs a model name");
        if (price.promptUsdPerMillion() < 0 || price.completionUsdPerMillion() < 0) throw ApiException.badRequest("Prices cannot be negative");
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.OWNER);
            j.update("INSERT INTO pricing (workspace_id, model, prompt_usd_per_million, completion_usd_per_million) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (workspace_id, model) DO UPDATE SET prompt_usd_per_million = EXCLUDED.prompt_usd_per_million, "
                    + "completion_usd_per_million = EXCLUDED.completion_usd_per_million", ws, price.model().strip(), price.promptUsdPerMillion(), price.completionUsdPerMillion());
            audit(j, ws, p.actor(), "pricing.update", "model:" + price.model());
            return prices(j);
        });
    }

    public List<ModelPrice> deletePrice(Principal p, UUID ws, String model) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.OWNER);
            j.update("DELETE FROM pricing WHERE model = ?", model);
            return prices(j);
        });
    }

    // ------------------------------------------------------------------ before a run, and after

    /** What the caller (already inside the workspace transaction) needs to know before it starts a run. */
    public record Verdict(boolean needsConfirmation, Double effectiveCapUsd, double spentThisMonthUsd, Double monthlyUsd) {}

    /**
     * Refuses a run whose estimate is over the per-run cap (the request's own, or else the workspace's) or would take the month past the monthly budget,
     * with the numbers in the message. A run above the confirmation threshold is allowed only when {@code confirmed}.
     */
    public Verdict check(JdbcTemplate j, double estimatedUsd, Double requestCapUsd, boolean confirmed) {
        Budget b = budget(j);
        Double cap = requestCapUsd != null ? requestCapUsd : b.perRunUsd();
        double spent = month(j)[0];
        if (cap != null && estimatedUsd > cap) {
            throw ApiException.conflict(String.format("This run is estimated at $%.2f, over its cap of $%.2f. Raise the cap or choose less to run.", estimatedUsd, cap));
        }
        if (b.monthlyUsd() != null && spent + estimatedUsd > b.monthlyUsd()) {
            throw ApiException.conflict(String.format("This run is estimated at $%.2f and $%.2f has been spent this month, which passes the monthly budget of $%.2f.",
                    estimatedUsd, spent, b.monthlyUsd()));
        }
        boolean needs = b.confirmAboveUsd() != null && estimatedUsd > b.confirmAboveUsd();
        if (needs && !confirmed) {
            throw ApiException.conflict(String.format("This run is estimated at $%.2f, above the $%.2f that needs confirmation. Confirm to start it.", estimatedUsd, b.confirmAboveUsd()));
        }
        return new Verdict(needs, cap, spent, b.monthlyUsd());
    }

    /** Whether an estimate would need confirmation, for showing it before the user asks to start (no refusal). */
    public boolean needsConfirmation(JdbcTemplate j, double estimatedUsd) {
        Budget b = budget(j);
        return b.confirmAboveUsd() != null && estimatedUsd > b.confirmAboveUsd();
    }

    /** Records estimate against actual when a run ends; one row per run, so a repeated call changes nothing. */
    public void record(UUID ws, String kind, UUID refId, double estimatedUsd, double actualUsd, long estimatedTokens, long actualTokens, int estimatedCalls, int actualCalls,
                       boolean aborted) {
        tx.workspace(ws, null, j -> j.update("INSERT INTO spend_log (workspace_id, kind, ref_id, estimated_usd, actual_usd, estimated_tokens, actual_tokens, estimated_calls, actual_calls, aborted) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (kind, ref_id) DO NOTHING", ws, kind, refId, estimatedUsd, actualUsd, estimatedTokens, actualTokens,
                estimatedCalls, actualCalls, aborted));
    }
}
