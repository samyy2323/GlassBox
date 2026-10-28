package dev.glassbox.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** Test sets as JSON lines, so a frozen set can be committed next to the documents it was written from. */
@Command(name = "testset", mixinStandardHelpOptions = true, description = "Export a test set to a file, or import one.", subcommands = {TestsetCommand.Export.class, TestsetCommand.Import.class})
public class TestsetCommand implements Runnable {
    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getErr());
    }

    @Command(name = "export", mixinStandardHelpOptions = true, description = "Write the accepted questions of a test set as JSON lines (to a file, or to standard output).")
    public static class Export implements Callable<Integer> {
        @ParentCommand TestsetCommand parent;
        @Spec CommandSpec spec;
        @Option(names = "--testset", required = true, description = "Id of the test set") UUID testset;
        @Option(names = "--out", description = "File to write (default: standard output)") Path out;

        @Override
        public Integer call() {
            Glassbox root = (Glassbox) parent.spec.parent().userObject();
            String file = new Api(Settings.load(root.env), root.requestTimeout).text("GET", "/api/testsets/" + testset + "/export", null, null, "application/x-ndjson");
            if (out == null) {
                spec.commandLine().getOut().print(file);
                spec.commandLine().getOut().flush();
            } else {
                try {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Files.writeString(out, file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new CliException("Could not write " + out + ": " + e.getMessage(), e);
                }
                spec.commandLine().getErr().println("Wrote " + Math.max(0, file.split("\n").length - 1) + " questions to " + out);
            }
            return Glassbox.PASSED;
        }
    }

    @Command(name = "import", mixinStandardHelpOptions = true, description = "Load a file made by `testset export` into a new test set. Evidence is found again in the documents of this workspace by their titles.")
    public static class Import implements Callable<Integer> {
        @ParentCommand TestsetCommand parent;
        @Spec CommandSpec spec;
        @Parameters(index = "0", paramLabel = "FILE", description = "The JSON lines file") Path file;
        @Option(names = "--name", description = "Name of the new test set (default: the name in the file)") String name;
        @Option(names = "--finalize", description = "Make the new test set final, ready to be measured") boolean finalizeSet;

        @Override
        public Integer call() {
            Glassbox root = (Glassbox) parent.spec.parent().userObject();
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CliException("Could not read " + file + ": " + e.getMessage(), e);
            }
            String query = "?finalize=" + finalizeSet + (name == null || name.isBlank() ? "" : "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
            String response = new Api(Settings.load(root.env), root.requestTimeout).text("POST", "/api/testsets/import" + query, "application/x-ndjson", content, "application/json");
            JsonNode result;
            try {
                result = Settings.JSON.readTree(response);
            } catch (IOException e) {
                throw new CliException("The server answered with something that is not JSON", e);
            }
            var out = spec.commandLine().getOut();
            out.println("Imported " + result.path("imported").asInt() + " questions into test set \"" + result.path("testset").path("name").asText() + "\" ("
                    + result.path("testset").path("status").asText().toLowerCase() + ", id " + result.path("testset").path("id").asText() + ").");
            for (JsonNode s : result.path("skipped")) out.println("  skipped line " + s.path("line").asInt() + ": " + s.path("reason").asText());
            out.flush();
            return Glassbox.PASSED;
        }
    }
}
