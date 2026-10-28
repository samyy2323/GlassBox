package dev.glassbox.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * Where the CLI talks to and with which key. The environment wins over the file, so a CI job can use secrets without a login step and without the key ever
 * appearing on a command line: {@code GLASSBOX_SERVER} and {@code GLASSBOX_API_KEY}. The file lives in {@code $GLASSBOX_HOME} or {@code ~/.glassbox} and is
 * readable by its owner only (where the file system supports permissions).
 */
public record Settings(String server, String key) {
    static final ObjectMapper JSON = new ObjectMapper();

    static Path home(Map<String, String> env) {
        String configured = env.get("GLASSBOX_HOME");
        return configured != null && !configured.isBlank() ? Path.of(configured) : Path.of(System.getProperty("user.home"), ".glassbox");
    }

    /** The settings in effect, or empty values for whatever is not set. */
    public static Settings load(Map<String, String> env) {
        String server = blankToNull(env.get("GLASSBOX_SERVER")), key = blankToNull(env.get("GLASSBOX_API_KEY"));
        Path file = home(env).resolve("config.json");
        if ((server == null || key == null) && Files.isRegularFile(file)) {
            try {
                JsonNode n = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
                if (server == null) server = blankToNull(n.path("server").asText(null));
                if (key == null) key = blankToNull(n.path("key").asText(null));
            } catch (IOException e) {
                throw new CliException("Could not read " + file + ": " + e.getMessage(), e);
            }
        }
        return new Settings(server, key);
    }

    /** Both are needed to talk to a server. */
    public Settings require() {
        if (server == null || key == null) {
            throw new CliException("Not signed in. Run `glassbox login --server URL --key KEY`, or set GLASSBOX_SERVER and GLASSBOX_API_KEY.");
        }
        return this;
    }

    public static String normalizeServer(String url) {
        String s = url == null ? "" : url.strip();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        URI uri;
        try {
            uri = URI.create(s);
        } catch (IllegalArgumentException e) {
            throw new CliException("\"" + url + "\" is not a valid server address");
        }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getHost() == null) {
            throw new CliException("The server address must start with http:// or https://, like https://glassbox.example.com");
        }
        return s;
    }

    public static Path save(Map<String, String> env, String server, String key) {
        Path dir = home(env), file = dir.resolve("config.json");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, JSON.writeValueAsString(Map.of("server", server, "key", key)), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException windows) {
                file.toFile().setReadable(false, false);
                file.toFile().setReadable(true, true);
                file.toFile().setWritable(false, false);
                file.toFile().setWritable(true, true);
            }
            return file;
        } catch (IOException e) {
            throw new CliException("Could not write " + file + ": " + e.getMessage(), e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
