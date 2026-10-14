package dev.glassbox.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Original uploads on local disk, laid out as {root}/{workspace}/{document}/v{n}/{name}. */
@Component
public class DocumentStorage {
    private final Path root;

    public DocumentStorage(@Value("${glassbox.storage.dir}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /** @return the path relative to the storage root, which is what gets stored in the database. */
    public String save(UUID workspaceId, UUID documentId, int version, String filename, byte[] data) {
        String rel = workspaceId + "/" + documentId + "/v" + version + "/" + safeName(filename);
        Path target = resolve(rel);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rel;
    }

    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void deleteDocument(UUID workspaceId, UUID documentId) {
        Path dir = resolve(workspaceId + "/" + documentId);
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Resolves under the root and refuses anything that escapes it. */
    private Path resolve(String relative) {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("path escapes storage root");
        return p;
    }

    static String safeName(String filename) {
        String base = filename == null ? "upload" : filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank() || base.equals(".") || base.equals("..")) base = "upload";
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }
}
