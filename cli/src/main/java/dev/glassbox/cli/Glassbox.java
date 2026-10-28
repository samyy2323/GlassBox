package dev.glassbox.cli;

import java.time.Duration;
import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The Glassbox command line (SPEC 14): sign in, run the quality gate, move test sets in and out, make reports.
 * Exit codes: 0 passed, 1 the gate failed, 2 an error (bad usage, no sign-in, network, the server said no).
 */
@Command(name = "glassbox", mixinStandardHelpOptions = true, exitCodeOnInvalidInput = 2, exitCodeOnExecutionException = 2, version = "glassbox-cli 0.1.0", description = "Measure a RAG pipeline against a frozen test set and gate changes on the result.",
        subcommands = {LoginCommand.class, RunCommand.class, TestsetCommand.class, ReportCommand.class},
        exitCodeListHeading = "%nExit codes:%n", exitCodeList = {"0:Success, or the gate passed", "1:The gate failed", "2:Error: bad usage, not signed in, network or server error"})
public class Glassbox implements Runnable {
    public static final int PASSED = 0, GATE_FAILED = 1, ERROR = 2;

    final Map<String, String> env;
    final Duration requestTimeout;

    public Glassbox() {
        this(System.getenv(), Duration.ofSeconds(60));
    }

    public Glassbox(Map<String, String> env, Duration requestTimeout) {
        this.env = env;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.err);
    }

    public static CommandLine commandLine(Glassbox root) {
        CommandLine cli = new CommandLine(root);
        cli.setExecutionExceptionHandler((e, commandLine, parseResult) -> {
            commandLine.getErr().println("glassbox: " + (e instanceof CliException ? e.getMessage() : "unexpected error: " + e));
            commandLine.getErr().flush();
            return ERROR;
        });
        return cli;
    }

    public static void main(String[] args) {
        System.exit(commandLine(new Glassbox()).execute(args));
    }
}
