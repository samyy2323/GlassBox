package dev.glassbox.parse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads wanted entries of an uploaded ZIP into memory with hard caps, so a decompression bomb cannot exhaust it. */
final class Zips {
    static final long MAX_ENTRY_BYTES = 30L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;
    static final int MAX_ENTRIES = 5000;

    private Zips() {}

    static Map<String, byte[]> read(byte[] zip, Predicate<String> wanted) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (++entries > MAX_ENTRIES) throw new UnsupportedDocumentException("The archive contains too many files");
                String name = e.getName();
                boolean keep = !e.isDirectory() && wanted.test(name);
                ByteArrayOutputStream buf = keep ? new ByteArrayOutputStream() : null;
                byte[] chunk = new byte[8192];
                long entryBytes = 0;
                for (int r = in.read(chunk); r != -1; r = in.read(chunk)) {
                    entryBytes += r;
                    total += r;
                    if (entryBytes > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        throw new UnsupportedDocumentException("The file is too large once unpacked");
                    }
                    if (buf != null) buf.write(chunk, 0, r);
                }
                if (buf != null) out.put(name, buf.toByteArray());
            }
        }
        return out;
    }
}
