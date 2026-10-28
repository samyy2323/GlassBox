package dev.glassbox.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** A frozen report of an experiment, as Markdown for a wiki or a pull request, or as the PDF the web app offers. */
@Command(name = "report", mixinStandardHelpOptions = true, description = "Make a report of an experiment (Markdown or PDF).")
public class ReportCommand implements Callable<Integer> {
    @ParentCommand Glassbox root;
    @Spec CommandSpec spec;

    @Option(names = "--experiment", required = true, description = "Id of the experiment") UUID experiment;
    @Option(names = "--format", defaultValue = "md", description = "md or pdf. Default: ${DEFAULT-VALUE}") String format;
    @Option(names = "--out", description = "File to write (required for pdf; Markdown goes to standard output without it)") Path out;
    @Option(names = "--redact", description = "Leave the text of questions out of the report") boolean redact;

    @Override
    public Integer call() throws IOException {
        String fmt = format.toLowerCase(Locale.ROOT);
        if (!fmt.equals("md") && !fmt.equals("pdf")) throw new CliException("--format must be md or pdf");
        if (fmt.equals("pdf") && out == null) throw new CliException("A PDF cannot be printed to the terminal: give --out FILE");
        Api api = new Api(Settings.load(root.env), root.requestTimeout);
        ObjectNode request = Settings.JSON.createObjectNode().put("kind", "EXPERIMENT").put("refId", experiment.toString()).put("redact", redact);
        String id = api.json("POST", "/api/reports", request).path("id").asText();
        if (id.isBlank()) throw new CliException("The server did not return a report id");

        if (fmt.equals("pdf")) {
            byte[] pdf = api.bytes("GET", "/api/reports/" + id + "/pdf", "application/pdf");
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.write(out, pdf);
            spec.commandLine().getErr().println("Wrote " + pdf.length + " bytes to " + out);
        } else {
            String md = MarkdownReport.render(api.json("GET", "/api/reports/" + id, null));
            if (out == null) {
                spec.commandLine().getOut().print(md);
                spec.commandLine().getOut().flush();
            } else {
                if (out.getParent() != null) Files.createDirectories(out.getParent());
                Files.writeString(out, md, StandardCharsets.UTF_8);
                spec.commandLine().getErr().println("Wrote " + out);
            }
        }
        return Glassbox.PASSED;
    }
}
