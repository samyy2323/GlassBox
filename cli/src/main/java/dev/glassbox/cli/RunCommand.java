package dev.glassbox.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Measures a configuration on a frozen test set and judges it (SPEC 14). The gate fails only when a metric is below its minimum, or has dropped against the
 * baseline by more than the allowed amount AND the drop is statistically significant, so noise does not block a merge.
 */
@Command(name = "run", mixinStandardHelpOptions = true, description = "Run the quality gate: measure a configuration on a test set and exit 0 (passed), 1 (failed) or 2 (error).",
        exitCodeListHeading = "%nExit codes:%n", exitCodeList = {"0:The gate passed", "1:The gate failed", "2:Error: bad usage, network or server error, or the run could not be judged"})
public class RunCommand implements Callable<Integer> {
    @ParentCommand Glassbox root;
    @Spec CommandSpec spec;

    @Option(names = "--testset", required = true, description = "Id of the (final) test set to measure on") UUID testset;
    @Option(names = "--config", description = "JSON file with the pipeline configuration to measure (default: the server's defaults)") Path config;
    @Option(names = "--mode", defaultValue = "retrieval", description = "retrieval (no model calls) or full (answers are generated and judged). Default: ${DEFAULT-VALUE}") String mode;
    @Option(names = "--baseline", description = "Id of a finished run on the same test set to compare against") UUID baseline;
    @Option(names = "--fail-under", split = ",", paramLabel = "METRIC=MIN", description = "Fail when the metric is below this fraction, like recall@5=0.85 (repeatable)")
    Map<String, Double> failUnder = new LinkedHashMap<>();
    @Option(names = "--fail-on-regression", description = "Fail when a metric dropped against --baseline by more than --max-drop and the drop is significant") boolean failOnRegression;
    @Option(names = "--regression-metric", split = ",", description = "Metrics to compare with the baseline (default: those of --fail-under, else recall@5 or correctness)") List<String> regressionMetrics;
    @Option(names = "--max-drop", defaultValue = "0.02", description = "Largest allowed drop as a fraction. Default: ${DEFAULT-VALUE} (2 points)") double maxDrop;
    @Option(names = "--strict", description = "Judge --fail-under by the lower end of the 95%% interval instead of the measured value") boolean strict;
    @Option(names = "--alpha", defaultValue = "0.05", description = "Significance level of the regression test. Default: ${DEFAULT-VALUE}") double alpha;
    @Option(names = "--name", description = "A label for the run, like the pull request number") String name;
    @Option(names = "--cap-usd", description = "Refuse the run if it is estimated to cost more than this") Double capUsd;
    @Option(names = "--cap-calls", description = "Refuse the run if it is estimated to need more model calls than this") Integer capCalls;
    @Option(names = "--format", defaultValue = "md", description = "What to print: md, json or junit. Default: ${DEFAULT-VALUE}") String format;
    @Option(names = "--markdown", description = "Also write the Markdown summary (for a pull request comment) to this file") Path markdownOut;
    @Option(names = "--junit", description = "Also write the JUnit XML report to this file") Path junitOut;
    @Option(names = "--timeout-minutes", defaultValue = "30", description = "Give up waiting after this long. Default: ${DEFAULT-VALUE}") double timeoutMinutes;
    @Option(names = "--poll-seconds", defaultValue = "2", description = "Seconds between checks. Default: ${DEFAULT-VALUE}") int pollSeconds;

    @Override
    public Integer call() throws Exception {
        String runMode = mode.toLowerCase(Locale.ROOT);
        if (!runMode.equals("retrieval") && !runMode.equals("full")) throw new CliException("--mode must be retrieval or full");
        if (!List.of("md", "json", "junit").contains(format)) throw new CliException("--format must be md, json or junit");
        if (failOnRegression && baseline == null) throw new CliException("--fail-on-regression needs --baseline RUN_ID to compare with");
        if (failUnder.isEmpty() && !failOnRegression) throw new CliException("Nothing to check. Give --fail-under METRIC=MIN and/or --fail-on-regression.");
        for (var e : failUnder.entrySet()) fraction("--fail-under " + e.getKey(), e.getValue());
        fraction("--max-drop", maxDrop);
        if (alpha <= 0 || alpha >= 1) throw new CliException("--alpha must be between 0 and 1");
        if (timeoutMinutes <= 0) throw new CliException("--timeout-minutes must be positive");

        ObjectNode body = Settings.JSON.createObjectNode();
        body.put("testsetId", testset.toString());
        body.put("name", name == null || name.isBlank() ? "CI gate" : name);
        body.put("mode", runMode.toUpperCase(Locale.ROOT));
        if (config != null) body.set("config", readConfig(config));
        if (baseline != null) body.put("baselineRunId", baseline.toString());
        ObjectNode under = body.putObject("failUnder");
        failUnder.forEach(under::put);
        if (failOnRegression) {
            List<String> metrics = new ArrayList<>(regressionMetrics != null ? regressionMetrics : failUnder.isEmpty() ? List.of(runMode.equals("full") ? "correctness" : "recall@5") : failUnder.keySet());
            ObjectNode regress = body.putObject("failOnRegression");
            metrics.forEach(m -> regress.put(m, maxDrop));
        }
        body.put("strict", strict);
        body.put("alpha", alpha);
        if (capUsd != null) body.put("capUsd", capUsd);
        if (capCalls != null) body.put("capCalls", capCalls);

        Api api = new Api(Settings.load(root.env), root.requestTimeout);
        String id = api.json("POST", "/api/ci/run", body).path("id").asText();
        if (id.isBlank()) throw new CliException("The server did not return a run id");
        var err = spec.commandLine().getErr();
        err.println("Started gate run " + id + "; waiting for it to finish.");
        err.flush();

        long deadline = System.nanoTime() + (long) (timeoutMinutes * 60_000_000_000.0);
        JsonNode result;
        while (true) {
            result = api.json("GET", "/api/ci/runs/" + id, null);
            if (!"RUNNING".equals(result.path("state").asText())) break;
            if (System.nanoTime() > deadline) {
                throw new CliException("Still running after " + timeoutMinutes + " minutes. The run " + id + " keeps going on the server; its result is at GET /api/ci/runs/" + id + ".");
            }
            Thread.sleep(Math.max(0, pollSeconds) * 1000L);
        }

        String md = api.text("GET", "/api/ci/runs/" + id + "?format=md", null, null, "text/markdown");
        String junit = api.text("GET", "/api/ci/runs/" + id + "?format=junit", null, null, "application/xml");
        write(markdownOut, md);
        write(junitOut, junit);
        var out = spec.commandLine().getOut();
        out.println(switch (format) {
            case "json" -> Settings.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            case "junit" -> junit;
            default -> md;
        });
        out.flush();

        return switch (result.path("state").asText()) {
            case "PASSED" -> Glassbox.PASSED;
            case "FAILED" -> Glassbox.GATE_FAILED;
            default -> {
                err.println("glassbox: the run could not be judged: " + result.path("note").asText("no reason given"));
                yield Glassbox.ERROR;
            }
        };
    }

    private static void fraction(String what, double v) {
        if (v < 0 || v > 1 || Double.isNaN(v)) throw new CliException(what + " must be a fraction between 0 and 1 (0.85 means 85%), not " + v);
    }

    private static JsonNode readConfig(Path file) {
        try {
            JsonNode n = Settings.JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (n == null || !n.isObject()) throw new CliException(file + " must hold one JSON object");
            return n;
        } catch (IOException e) {
            throw new CliException("Could not read the configuration " + file + ": " + e.getMessage(), e);
        }
    }

    private static void write(Path file, String text) {
        if (file == null) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliException("Could not write " + file + ": " + e.getMessage(), e);
        }
    }
}
