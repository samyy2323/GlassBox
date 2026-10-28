package dev.glassbox.cli;

/** A failure the user can act on (bad input, no sign-in, the server said no, the network is down). Printed as one line; the exit code is 2. */
public class CliException extends RuntimeException {
    public CliException(String message) {
        super(message);
    }

    public CliException(String message, Throwable cause) {
        super(message, cause);
    }
}
