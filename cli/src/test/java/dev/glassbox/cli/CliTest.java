package dev.glassbox.cli;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** The CLI in process against a stand-in server: what it sends, what it prints, where it writes, and above all its exit codes. */
class CliTest {
    static final String KEY = "gb_00000000-0000-0000-0000-000000000000_secretsecretsecret";
    static final String TESTSET = "5c1d2f00-0000-4000-8000-000000000001";
    static final String BASELINE = "5c1d2f00-0000-4000-8000-000000000002";
    static final String RUN = "5c1d2f00-0000-4000-8000-000000000003";

    @TempDir Path home;
    WireMockServer server;

    record Out(int code, String out, String err) {}

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    String base() {
        return "http://localhost:" + server.port();
    }

    Map<String, String> env(boolean signedIn) {
        Map<String, String> env = new HashMap<>();
        env.put("GLASSBOX_HOME", home.toString());
        if (signedIn) {
            env.put("GLASSBOX_SERVER", base());
            env.put("GLASSBOX_API_KEY", KEY);
        }
        return env;
    }

    Out exec(Map<String, String> env, String... args) {
        StringWriter out = new StringWriter(), err = new StringWriter();
        CommandLine cli = Glassbox.commandLine(new Glassbox(env, Duration.ofSeconds(5)));
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        cli.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        int code = cli.execute(args);
        return new Out(code, out.toString(), err.toString());
    }

    Out run(String... args) {
        String[] all = new String[args.length + 4];
        all[0] = "run";
        all[1] = "--testset";
        all[2] = TESTSET;
        all[3] = "--poll-seconds=0";
        System.arraycopy(args, 0, all, 4, args.length);
        return exec(env(true), all);
    }

    /** The server's answers to a gate run that finishes in the given state. */
    void gate(String state, String note) {
        server.stubFor(post(urlEqualTo("/api/ci/run")).willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody("{\"id\":\"" + RUN + "\",\"status\":\"QUEUED\"}")));
        server.stubFor(get(urlEqualTo("/api/ci/runs/" + RUN)).inScenario("poll").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"id\":\"" + RUN + "\",\"state\":\"RUNNING\"}")).willSetStateTo("done"));
        server.stubFor(get(urlEqualTo("/api/ci/runs/" + RUN)).inScenario("poll").whenScenarioStateIs("done")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"id\":\"" + RUN + "\",\"state\":\"" + state + "\",\"note\":" + (note == null ? "null" : "\"" + note + "\"") + "}")));
        server.stubFor(get(urlEqualTo("/api/ci/runs/" + RUN + "?format=md")).willReturn(aResponse().withHeader("Content-Type", "text/markdown").withBody("### Glassbox quality gate: " + state.toLowerCase() + "\n")));
        server.stubFor(get(urlEqualTo("/api/ci/runs/" + RUN + "?format=junit")).willReturn(aResponse().withHeader("Content-Type", "application/xml").withBody("<testsuite name=\"glassbox\" tests=\"1\"/>")));
    }

    JsonNode sentToRun() throws Exception {
        return Settings.JSON.readTree(server.findAll(postRequestedFor(urlEqualTo("/api/ci/run"))).get(0).getBodyAsString());
    }

    // ------------------------------------------------------------------ login

    @Test
    void loginChecksTheKeyThenRemembersItReadableByTheOwnerOnlyAndNeverPrintsIt() throws Exception {
        server.stubFor(get(urlEqualTo("/api/testsets")).withHeader("X-API-Key", equalTo(KEY)).willReturn(okJson("{\"items\":[]}")));
        Out r = exec(env(false), "login", "--server", base() + "/", "--key", KEY);
        assertThat(r.code()).isEqualTo(0);
        assertThat(r.out()).contains("Signed in to " + base()).doesNotContain(KEY);
        assertThat(r.err()).doesNotContain(KEY);
        JsonNode saved = Settings.JSON.readTree(Files.readString(home.resolve("config.json")));
        assertThat(saved.get("server").asText()).as("trailing slash removed").isEqualTo(base());
        assertThat(saved.get("key").asText()).isEqualTo(KEY);
        try {
            assertThat(Files.getPosixFilePermissions(home.resolve("config.json"))).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        } catch (UnsupportedOperationException windows) {
            assertThat(home.resolve("config.json").toFile().canRead()).isTrue();
        }

        // and a later command needs no environment: it reads the file
        server.stubFor(get(urlEqualTo("/api/testsets/" + TESTSET + "/export")).willReturn(aResponse().withBody("{\"format\":\"glassbox-testset\"}\n")));
        assertThat(exec(env(false), "testset", "export", "--testset", TESTSET).out()).contains("glassbox-testset");
    }

    @Test
    void loginRefusesABadKeyABadAddressAndAServerThatIsNotGlassboxAndSavesNothing() {
        server.stubFor(get(urlEqualTo("/api/testsets")).willReturn(aResponse().withStatus(401)));
        Out refused = exec(env(false), "login", "--server", base(), "--key", KEY);
        assertThat(refused.code()).isEqualTo(2);
        assertThat(refused.err()).contains("did not accept the API key").doesNotContain(KEY);
        assertThat(exec(env(false), "login", "--server", "glassbox.example.com", "--key", KEY).err()).contains("http://");
        server.stubFor(get(urlEqualTo("/api/testsets")).willReturn(aResponse().withStatus(200).withBody("<html>hello</html>")));
        assertThat(exec(env(false), "login", "--server", base(), "--key", KEY).err()).contains("not JSON");
        assertThat(home.resolve("config.json")).doesNotExist();
    }

    @Test
    void theEnvironmentBeatsTheFileAndWithNeitherTheCliSaysHowToSignIn() throws Exception {
        Settings.save(Map.of("GLASSBOX_HOME", home.toString()), "http://file.example", "from-file");
        assertThat(Settings.load(env(true)).server()).isEqualTo(base());
        assertThat(Settings.load(env(true)).key()).isEqualTo(KEY);
        assertThat(Settings.load(env(false)).server()).isEqualTo("http://file.example");
        Path empty = Files.createTempDirectory(home, "empty");
        Out r = exec(Map.of("GLASSBOX_HOME", empty.toString()), "run", "--testset", TESTSET, "--fail-under", "mrr=0.5");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("Not signed in").contains("GLASSBOX_API_KEY");
    }

    // ------------------------------------------------------------------ run: the three exit codes

    @Test
    void aPassedGateExitsZeroPrintsTheMarkdownAndWritesTheFiles() throws Exception {
        gate("PASSED", null);
        Path md = home.resolve("out/summary.md"), junit = home.resolve("out/junit.xml");
        Out r = run("--fail-under", "recall@5=0.85", "--markdown", md.toString(), "--junit", junit.toString(), "--name", "PR 12");
        assertThat(r.code()).isEqualTo(0);
        assertThat(r.out()).contains("quality gate: passed");
        assertThat(r.err()).contains("Started gate run " + RUN);
        assertThat(Files.readString(md)).contains("passed");
        assertThat(Files.readString(junit)).startsWith("<testsuite");

        JsonNode sent = sentToRun();
        assertThat(sent.get("testsetId").asText()).isEqualTo(TESTSET);
        assertThat(sent.get("mode").asText()).isEqualTo("RETRIEVAL");
        assertThat(sent.get("name").asText()).isEqualTo("PR 12");
        assertThat(sent.get("failUnder").get("recall@5").asDouble()).isEqualTo(0.85);
        assertThat(sent.has("failOnRegression")).isFalse();
        assertThat(sent.get("strict").asBoolean()).isFalse();
        server.verify(postRequestedFor(urlEqualTo("/api/ci/run")).withHeader("X-API-Key", equalTo(KEY)).withHeader("Content-Type", equalTo("application/json")));
        assertThat(r.out() + r.err()).doesNotContain(KEY);
    }

    @Test
    void aFailedGateExitsOneSoThePullRequestIsBlocked() {
        gate("FAILED", null);
        Out r = run("--fail-under", "recall@5=0.85");
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("failed");
        assertThat(r.err()).doesNotContain("could not be judged");
    }

    @Test
    void aRunThatCouldNotBeJudgedExitsTwoAndStillWritesTheJunitFileForTheCiReport() throws Exception {
        gate("ERROR", "The run was cancelled");
        Path junit = home.resolve("junit.xml");
        Out r = run("--fail-under", "recall@5=0.85", "--junit", junit.toString());
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("could not be judged: The run was cancelled");
        assertThat(junit).exists();
    }

    @Test
    void theOtherFormatsAreWhatIsPrinted() throws Exception {
        gate("PASSED", null);
        assertThat(run("--fail-under", "mrr=0.1", "--format", "junit").out()).startsWith("<testsuite");
        server.resetScenarios();
        JsonNode printed = Settings.JSON.readTree(run("--fail-under", "mrr=0.1", "--format", "json").out());
        assertThat(printed.get("state").asText()).isEqualTo("PASSED");
        assertThat(run("--fail-under", "mrr=0.1", "--format", "yaml").code()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ run: what is sent

    @Test
    void regressionRulesNeedABaselineAndDefaultToTheMetricsOfFailUnder() throws Exception {
        gate("PASSED", null);
        assertThat(run("--fail-on-regression").code()).as("no baseline").isEqualTo(2);
        server.verify(0, postRequestedFor(urlEqualTo("/api/ci/run")));

        assertThat(run("--baseline", BASELINE, "--fail-on-regression", "--fail-under", "mrr=0.4,recall@5=0.8", "--max-drop", "0.03", "--strict", "--alpha", "0.01", "--cap-usd", "1.5", "--cap-calls", "200").code()).isEqualTo(0);
        JsonNode sent = sentToRun();
        assertThat(sent.get("baselineRunId").asText()).isEqualTo(BASELINE);
        assertThat(sent.get("failOnRegression").get("mrr").asDouble()).isEqualTo(0.03);
        assertThat(sent.get("failOnRegression").get("recall@5").asDouble()).isEqualTo(0.03);
        assertThat(sent.get("strict").asBoolean()).isTrue();
        assertThat(sent.get("alpha").asDouble()).isEqualTo(0.01);
        assertThat(sent.get("capUsd").asDouble()).isEqualTo(1.5);
        assertThat(sent.get("capCalls").asInt()).isEqualTo(200);
    }

    @Test
    void theRegressionMetricsCanBeChosenAndFullRunsDefaultToCorrectness() throws Exception {
        gate("PASSED", null);
        run("--baseline", BASELINE, "--fail-on-regression", "--regression-metric", "ndcg@10,mrr");
        assertThat(sentToRun().get("failOnRegression").fieldNames()).toIterable().containsExactlyInAnyOrder("ndcg@10", "mrr");
        server.resetRequests();
        server.resetScenarios();
        run("--mode", "FULL", "--baseline", BASELINE, "--fail-on-regression");
        JsonNode full = sentToRun();
        assertThat(full.get("mode").asText()).isEqualTo("FULL");
        assertThat(full.get("failOnRegression").has("correctness")).isTrue();
    }

    @Test
    void theConfigurationFileIsSentAsItIs() throws Exception {
        gate("PASSED", null);
        Path config = home.resolve("config.json");
        Files.writeString(config, "{\"name\":\"candidate\",\"mode\":\"HYBRID\",\"topN\":3,\"tokenBudget\":1500}");
        assertThat(run("--fail-under", "mrr=0.1", "--config", config.toString()).code()).isEqualTo(0);
        JsonNode sent = sentToRun().get("config");
        assertThat(sent.get("topN").asInt()).isEqualTo(3);
        assertThat(sent.get("tokenBudget").asInt()).isEqualTo(1500);

        Files.writeString(config, "not json");
        assertThat(run("--fail-under", "mrr=0.1", "--config", config.toString()).code()).isEqualTo(2);
        assertThat(run("--fail-under", "mrr=0.1", "--config", home.resolve("missing.json").toString()).err()).contains("Could not read the configuration");
    }

    // ------------------------------------------------------------------ run: errors are exit code 2, never a stack trace

    @Test
    void badUsageIsAnErrorBeforeAnythingIsSent() {
        gate("PASSED", null);
        assertThat(exec(env(true), "run", "--fail-under", "mrr=0.5").code()).as("no --testset").isEqualTo(2);
        assertThat(run().code()).as("nothing to check").isEqualTo(2);
        assertThat(run().err()).contains("Nothing to check");
        assertThat(run("--fail-under", "mrr=1.5").err()).contains("fraction between 0 and 1");
        assertThat(run("--fail-under", "mrr").code()).isEqualTo(2);
        assertThat(run("--fail-under", "mrr=0.5", "--mode", "weird").err()).contains("retrieval or full");
        assertThat(run("--fail-under", "mrr=0.5", "--alpha", "2").err()).contains("--alpha");
        assertThat(exec(env(true), "run", "--testset", "not-a-uuid", "--fail-under", "mrr=0.5").code()).isEqualTo(2);
        assertThat(exec(env(true), "frobnicate").code()).isEqualTo(2);
        server.verify(0, postRequestedFor(urlEqualTo("/api/ci/run")));
    }

    @Test
    void serverRefusalsBecomeOneLineMessagesAndExitTwo() {
        server.stubFor(post(urlEqualTo("/api/ci/run")).willReturn(aResponse().withStatus(401)));
        Out unauthorised = run("--fail-under", "mrr=0.5");
        assertThat(unauthorised.code()).isEqualTo(2);
        assertThat(unauthorised.err()).contains("did not accept the API key").doesNotContain(KEY).doesNotContain("Exception");

        server.stubFor(post(urlEqualTo("/api/ci/run")).willReturn(aResponse().withStatus(403).withHeader("Content-Type", "application/problem+json").withBody("{\"detail\":\"Requires role EDITOR\"}")));
        assertThat(run("--fail-under", "mrr=0.5").err()).contains("may not do that").contains("Requires role EDITOR").contains("run scope");

        server.stubFor(post(urlEqualTo("/api/ci/run")).willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"detail\":\"This workspace already has 3 experiments or AutoTune searches queued or running\"}")));
        assertThat(run("--fail-under", "mrr=0.5").err()).contains("already has 3 experiments");

        server.stubFor(post(urlEqualTo("/api/ci/run")).willReturn(aResponse().withStatus(500).withBody("<html>boom</html>")));
        Out broken = run("--fail-under", "mrr=0.5");
        assertThat(broken.code()).isEqualTo(2);
        assertThat(broken.err()).contains("500");
    }

    @Test
    void anUnreachableServerAndARunThatNeverFinishesAreErrorsToo() {
        Map<String, String> pointingAtIt = env(true);
        server.stop();
        Out down = exec(pointingAtIt, "run", "--testset", TESTSET, "--poll-seconds=0", "--fail-under", "mrr=0.5");
        assertThat(down.code()).isEqualTo(2);
        assertThat(down.err()).contains("Could not reach");

        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(post(urlEqualTo("/api/ci/run")).willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody("{\"id\":\"" + RUN + "\"}")));
        server.stubFor(get(urlEqualTo("/api/ci/runs/" + RUN)).willReturn(okJson("{\"state\":\"RUNNING\"}")));
        Out slow = run("--fail-under", "mrr=0.5", "--timeout-minutes", "0.01");
        assertThat(slow.code()).isEqualTo(2);
        assertThat(slow.err()).contains("Still running").contains(RUN);
    }

    // ------------------------------------------------------------------ test sets and reports

    @Test
    void aTestSetIsExportedToAFileOrTheTerminalAndImportedWithItsSkippedQuestionsListed() throws Exception {
        String file = "{\"format\":\"glassbox-testset\",\"version\":1,\"name\":\"frozen\"}\n{\"question\":\"q\"}\n";
        server.stubFor(get(urlEqualTo("/api/testsets/" + TESTSET + "/export")).willReturn(aResponse().withHeader("Content-Type", "application/x-ndjson").withBody(file)));
        Path out = home.resolve("frozen/set.jsonl");
        Out exported = exec(env(true), "testset", "export", "--testset", TESTSET, "--out", out.toString());
        assertThat(exported.code()).isEqualTo(0);
        assertThat(Files.readString(out)).isEqualTo(file);
        assertThat(exported.err()).contains("Wrote 1 questions");

        server.stubFor(post(urlEqualTo("/api/testsets/import?finalize=true&name=my+copy")).willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                .withBody("{\"imported\":7,\"skipped\":[{\"line\":4,\"question\":\"Where is it?\",\"reason\":\"no document titled \\\"Gone\\\" is ready in this workspace\"}],"
                        + "\"testset\":{\"id\":\"abc\",\"name\":\"my copy\",\"status\":\"FINAL\"}}")));
        Out imported = exec(env(true), "testset", "import", out.toString(), "--name", "my copy", "--finalize");
        assertThat(imported.code()).isEqualTo(0);
        assertThat(imported.out()).contains("Imported 7 questions into test set \"my copy\" (final").contains("skipped line 4: no document titled");
        server.verify(postRequestedFor(urlEqualTo("/api/testsets/import?finalize=true&name=my+copy")).withHeader("Content-Type", equalTo("application/x-ndjson")).withRequestBody(equalTo(file)));

        server.stubFor(post(urlPathEqualTo("/api/testsets/import")).willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"detail\":\"None of the 3 questions could be imported.\"}")));
        Out refused = exec(env(true), "testset", "import", out.toString());
        assertThat(refused.code()).isEqualTo(2);
        assertThat(refused.err()).contains("None of the 3 questions");
        assertThat(exec(env(true), "testset", "import", home.resolve("nothing.jsonl").toString()).err()).contains("Could not read");
    }

    @Test
    void aReportComesOutAsMarkdownOrAPdfFile() throws Exception {
        server.stubFor(post(urlEqualTo("/api/reports")).willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"id\":\"r-1\"}")));
        server.stubFor(get(urlEqualTo("/api/reports/r-1")).willReturn(okJson("{\"title\":\"Experiment: budgets\",\"redacted\":true,\"summary\":[\"30 questions were measured.\"],"
                + "\"testset\":{\"name\":\"reported\",\"version\":1,\"questions\":30,\"accepted\":28,\"edited\":2,\"rejected\":4},"
                + "\"configs\":[{\"label\":\"tiny | budget\",\"settings\":[\"top 5\"]}],"
                + "\"metrics\":[{\"label\":\"tiny | budget\",\"rows\":[{\"metric\":\"context_pass\",\"name\":\"Evidence in prompt\",\"value\":0.5,\"ciLow\":0.3,\"ciHigh\":0.7,\"n\":30,\"judged\":false},"
                + "{\"metric\":\"latency_p50_ms\",\"name\":\"Latency p50 (ms)\",\"value\":15.6,\"n\":30}]}],\"limitations\":[\"Every interval is a 95% bootstrap.\"]}")));
        Out md = exec(env(true), "report", "--experiment", RUN, "--redact");
        assertThat(md.code()).isEqualTo(0);
        assertThat(md.out()).startsWith("# Experiment: budgets").contains("Question texts are left out").contains("50.0% [30.0%, 70.0%]").contains("16 ms")
                .contains("tiny \\| budget").contains("Every interval is a 95% bootstrap.");
        JsonNode request = Settings.JSON.readTree(server.findAll(postRequestedFor(urlEqualTo("/api/reports"))).get(0).getBodyAsString());
        assertThat(request.get("kind").asText()).isEqualTo("EXPERIMENT");
        assertThat(request.get("redact").asBoolean()).isTrue();

        byte[] pdf = "%PDF-1.7 fake".getBytes(StandardCharsets.UTF_8);
        server.stubFor(get(urlEqualTo("/api/reports/r-1/pdf")).willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody(pdf)));
        Path file = home.resolve("r.pdf");
        assertThat(exec(env(true), "report", "--experiment", RUN, "--format", "pdf").code()).as("a PDF needs --out").isEqualTo(2);
        assertThat(exec(env(true), "report", "--experiment", RUN, "--format", "pdf", "--out", file.toString()).code()).isEqualTo(0);
        assertThat(Files.readAllBytes(file)).isEqualTo(pdf);
        assertThat(exec(env(true), "report", "--experiment", RUN, "--format", "docx").code()).isEqualTo(2);
    }

    @Test
    void helpAndVersionWorkAndDocumentTheExitCodes() {
        Out help = exec(env(false), "--help");
        assertThat(help.code()).isEqualTo(0);
        assertThat(help.out()).contains("login", "run", "testset", "report", "Exit codes", "The gate failed");
        assertThat(exec(env(false), "run", "--help").out()).contains("--fail-under", "--fail-on-regression", "--baseline", "Exit codes");
        assertThat(exec(env(false), "--version").out()).contains("glassbox-cli");
    }
}
