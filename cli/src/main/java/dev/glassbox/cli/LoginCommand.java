package dev.glassbox.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "login", mixinStandardHelpOptions = true, description = "Check an API key against a server and remember both (in $GLASSBOX_HOME or ~/.glassbox, readable by you only).")
public class LoginCommand implements Callable<Integer> {
    @ParentCommand Glassbox root;
    @Spec CommandSpec spec;

    @Option(names = "--server", required = true, description = "Address of the server, like https://glassbox.example.com") String server;
    @Option(names = "--key", required = true, description = "An API key made on the server (its scope decides what the CLI may do)", arity = "1") String key;

    @Override
    public Integer call() {
        String normalized = Settings.normalizeServer(server);
        if (key.isBlank()) throw new CliException("The key is empty");
        // the cheapest call that needs any valid key: it proves the address is a Glassbox server and the key is accepted
        new Api(new Settings(normalized, key.strip()), root.requestTimeout).json("GET", "/api/testsets", null);
        Path file = Settings.save(root.env, normalized, key.strip());
        spec.commandLine().getOut().println("Signed in to " + normalized + " (settings in " + file + ")");
        return Glassbox.PASSED;
    }
}
