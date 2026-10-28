package dev.glassbox.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Looks inside a zip-based upload (Word, Excel, PowerPoint, OpenDocument, e-books) before any parser opens it, so a decompression bomb is refused at the door
 * (SPEC 12). The bytes are really inflated and counted, because the sizes written in a zip header can be forged: the check stops reading as soon as a limit is
 * passed, so a bomb costs a few megabytes of work, not gigabytes.
 *
 * <p>Refused: too many entries, too many bytes once expanded, an overall expansion ratio no document needs, and an archive inside the archive (the usual
 * way to hide a bomb from a scanner that looks one level deep).
 */
public final class ArchiveGuard {
    public static final int MAX_ENTRIES = 5_000;
    public static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;
    /** A real document compresses 2 to 20 times; text-heavy spreadsheets reach perhaps 50. Only applied once the expanded size is worth worrying about. */
    public static final double MAX_RATIO = 200;
    private static final long RATIO_APPLIES_ABOVE = 10L * 1024 * 1024;
    private static final String[] NESTED = {".zip", ".jar", ".war", ".gz", ".tgz", ".tar", ".7z", ".rar", ".bz2", ".xz"};

    private ArchiveGuard() {}

    /** @return the reason the archive must not be opened, or empty when it is fine (or is not a zip at all) */
    public static Optional<String> problem(byte[] data) {
        return problem(data, MAX_ENTRIES, MAX_EXPANDED_BYTES, MAX_RATIO);
    }

    static boolean looksLikeZip(byte[] data) {
        return data.length > 4 && data[0] == 'P' && data[1] == 'K' && (data[2] == 3 || data[2] == 5 || data[2] == 7);
    }

    static Optional<String> problem(byte[] data, int maxEntries, long maxExpanded, double maxRatio) {
        if (!looksLikeZip(data)) return Optional.empty();
        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (++entries > maxEntries) return Optional.of("The archive has more than " + maxEntries + " parts.");
                String name = e.getName().toLowerCase(Locale.ROOT);
                for (String ext : NESTED) {
                    if (name.endsWith(ext)) return Optional.of("The archive contains another archive (" + e.getName() + "), which is not accepted.");
                }
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    total += n;
                    if (total > maxExpanded) return Optional.of("The archive expands to more than " + (maxExpanded >> 20) + " MB.");
                    if (total > RATIO_APPLIES_ABOVE && total / (double) data.length > maxRatio) {
                        return Optional.of("The archive expands " + Math.round(total / (double) data.length) + " times or more, which no real document does.");
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty(); // not readable as a zip: the parser will say so with its own error
        }
        return Optional.empty();
    }
}
