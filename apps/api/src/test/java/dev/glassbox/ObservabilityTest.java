package dev.glassbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.ingest.ApiTestSupport;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** SPEC 14: the metrics, the structured log context and the API description, checked against a running application. */
@org.springframework.test.context.TestPropertySource(properties = "management.prometheus.metrics.export.enabled=true")
class ObservabilityTest extends ApiTestSupport {
    @Autowired PrometheusMeterRegistry prometheus;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    void theScrapeCarriesEveryMetricOfTheSpecAfterOneAnswerAndOneIngest() throws Exception {
        Account a = accountWithDoc("# Pumps\n\nAll pumps must be inspected every ninety days by a certified engineer. " + UUID.randomUUID());
        chat(a, a.workspace(), "how often are pumps inspected");

        String scrape = prometheus.scrape();
        for (String name : List.of("ingest_queue_depth", "ingest_duration_seconds_count", "ingest_duration_seconds_bucket", "retrieval_stage_duration_seconds_bucket{",
                "llm_tokens_total{", "llm_cost_usd_total{", "experiment_runs_active", "job_retries_total", "rls_context_missing_total", "llm_requests_rejected_total",
                "llm_generations_in_flight")) {
            assertThat(scrape).as("metric %s", name).contains(name);
        }
        assertThat(scrape).contains("direction=\"input\"").contains("direction=\"output\"").contains("stage=\"query_embedding\"").contains("stage=\"retrieval\"").contains("stage=\"context\"");
        assertThat(scrape.lines().filter(l -> l.startsWith("llm_tokens_total{") && l.contains("direction=\"input\"")).findFirst().orElseThrow())
                .as("the answer's prompt was counted").doesNotEndWith(" 0.0");
        assertThat(scrape).as("no workspace id is ever a metric label").doesNotContain(a.workspace().toString());
    }

    @Test
    void tenantWorkAndRequestsLogWithTheVerifiedWorkspaceAndATraceId() throws Exception {
        Account a = register();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        try {
            tx.workspace(a.workspace(), null, j -> {
                LoggerFactory.getLogger("inside.tenant.work").info("working");
                return null;
            });
            LoggerFactory.getLogger("outside.tenant.work").info("idle");
            mvc.perform(get("/api/workspaces").header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace().toString())
                    .queryParam("secret", "do-not-log")).andExpect(status().isOk());
            mvc.perform(get("/api/public/reports/SECRET-SHARE-TOKEN-123/pdf"));
            mvc.perform(get("/api/workspaces")); // refused: no credential
        } finally {
            root.detachAppender(logs);
        }

        assertThat(logs.list.stream().filter(e -> e.getLoggerName().equals("inside.tenant.work")).findFirst().orElseThrow().getMDCPropertyMap())
                .containsEntry("workspace_id", a.workspace().toString());
        assertThat(logs.list.stream().filter(e -> e.getLoggerName().equals("outside.tenant.work")).findFirst().orElseThrow().getMDCPropertyMap())
                .doesNotContainKey("workspace_id");

        List<ILoggingEvent> requests = logs.list.stream().filter(e -> e.getLoggerName().endsWith("RequestLogFilter")).toList();
        assertThat(requests).hasSize(3);
        ILoggingEvent ok = requests.get(0);
        assertThat(ok.getFormattedMessage()).startsWith("GET /api/workspaces 200 ").doesNotContain("do-not-log").doesNotContain(a.token());
        assertThat(ok.getMDCPropertyMap()).containsEntry("workspace_id", a.workspace().toString());
        assertThat(ok.getMDCPropertyMap().get("traceId")).as("the tracing bridge puts a trace id in the log context").isNotBlank();
        assertThat(requests.get(1).getFormattedMessage()).startsWith("GET /api/public/reports/{token}/pdf 404 ").doesNotContain("SECRET-SHARE-TOKEN");
        assertThat(requests.get(2).getFormattedMessage()).startsWith("GET /api/workspaces 401 ");
        assertThat(requests.get(2).getMDCPropertyMap()).as("a refused request has not proven its workspace").doesNotContainKey("workspace_id");
    }

    @Test
    void theApiDescriptionListsEveryRouteTheApplicationServes() throws Exception {
        Account a = register();
        String raw = mvc.perform(get("/api/docs/openapi").header("Authorization", "Bearer " + a.token())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode spec = json.readTree(raw);
        assertThat(spec.get("openapi").asText()).startsWith("3.");

        Set<String> described = new HashSet<>();
        spec.get("paths").fields().forEachRemaining(p -> p.getValue().fieldNames().forEachRemaining(m -> described.add(m.toUpperCase() + " " + p.getKey())));
        Set<String> served = new HashSet<>();
        for (var e : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            HandlerMethod method = e.getValue();
            if (!method.getBeanType().getPackageName().startsWith("dev.glassbox")) continue;
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String path : info.getPathPatternsCondition().getPatternValues()) {
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) served.add(m.name() + " " + path);
            }
        }
        assertThat(served).as("the controllers were found").hasSizeGreaterThan(50);
        assertThat(described).as("a route without a description: new controllers appear in the spec by themselves, so this is a routing mismatch").containsAll(served);
    }

    /** Words that are PromQL syntax or label names, not metric names. */
    private static final Set<String> PROMQL = Set.of("sum", "rate", "increase", "histogram_quantile", "max", "min", "avg", "by", "without", "le", "stage", "direction", "scope",
            "status", "job", "on", "and", "or", "unless", "up", "for", "model", "provider");

    private static Set<String> metricsIn(String expr) {
        Set<String> out = new HashSet<>();
        var m = java.util.regex.Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*").matcher(expr.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", ""));
        while (m.find()) if (!PROMQL.contains(m.group())) out.add(m.group());
        return out;
    }

    @Test
    void everyMetricTheDashboardAndTheAlertsUseIsOneTheApplicationExports() throws Exception {
        Account a = accountWithDoc("# Pumps\n\nAll pumps must be inspected every ninety days. " + UUID.randomUUID());
        chat(a, a.workspace(), "how often are pumps inspected");
        mvc.perform(get("/api/workspaces").header("Authorization", "Bearer " + a.token())).andExpect(status().isOk());
        String scrape = prometheus.scrape();
        // counters that only exist after their first event are checked against the source that registers them
        String limiter = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/glassbox/ratelimit/ApiRateLimitFilter.java"));

        Set<String> used = new HashSet<>();
        JsonNode dashboard = json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../../deploy/grafana/dashboards/glassbox.json")));
        assertThat(dashboard.get("panels").size()).isGreaterThanOrEqualTo(10);
        for (JsonNode panel : dashboard.get("panels")) for (JsonNode target : panel.get("targets")) used.addAll(metricsIn(target.get("expr").asText()));
        Object rules = new org.yaml.snakeyaml.Yaml().load(java.nio.file.Files.readString(java.nio.file.Path.of("../../deploy/prometheus/alerts.yml")));
        int alerts = 0;
        for (Object group : (List<?>) ((java.util.Map<?, ?>) rules).get("groups")) {
            for (Object rule : (List<?>) ((java.util.Map<?, ?>) group).get("rules")) {
                used.addAll(metricsIn(String.valueOf(((java.util.Map<?, ?>) rule).get("expr"))));
                alerts++;
            }
        }
        assertThat(alerts).isGreaterThanOrEqualTo(5);
        assertThat(used).contains("ingest_queue_depth", "llm_tokens_total", "retrieval_stage_duration_seconds_bucket", "rate_limited_total");
        for (String metric : used) {
            boolean exported = scrape.contains("\n" + metric + "{") || scrape.contains("\n" + metric + " ") || scrape.startsWith(metric);
            boolean registeredOnFirstEvent = limiter.contains("\"" + metric + "\"");
            assertThat(exported || registeredOnFirstEvent).as("dashboard or alert uses %s, which nothing exports", metric).isTrue();
        }
    }
}
