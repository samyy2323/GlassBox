package dev.glassbox.parse;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.tika.Tika;

/**
 * Decides what an uploaded file is, from its bytes first and its name second, and whether Glassbox can read it.
 * The result is the {@code documents.source_type} and selects the parser in {@link DocumentParser#forType}.
 */
public final class FileTypes {
    private static final Tika TIKA = new Tika();

    /** Types that need OCR or transcription, which Glassbox does not do (SPEC 1: out of scope). */
    public static final class NotSupported extends RuntimeException {
        public NotSupported(String message) {
            super(message);
        }
    }

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("pdf", "PDF"),
            Map.entry("docx", "DOCX"), Map.entry("docm", "DOCX"), Map.entry("dotx", "DOCX"),
            Map.entry("doc", "DOC"),
            Map.entry("xlsx", "XLSX"), Map.entry("xlsm", "XLSX"),
            Map.entry("xls", "XLS"),
            Map.entry("pptx", "PPTX"), Map.entry("pptm", "PPTX"),
            Map.entry("ppt", "PPT"),
            Map.entry("odt", "ODF"), Map.entry("ods", "ODF"), Map.entry("odp", "ODF"),
            Map.entry("rtf", "RTF"),
            Map.entry("epub", "EPUB"),
            Map.entry("html", "HTML"), Map.entry("htm", "HTML"), Map.entry("xhtml", "HTML"),
            Map.entry("md", "MD"), Map.entry("markdown", "MD"),
            Map.entry("csv", "CSV"), Map.entry("tsv", "CSV"),
            Map.entry("json", "JSON"), Map.entry("jsonl", "JSON"), Map.entry("ndjson", "JSON"),
            Map.entry("xml", "XML"));

    /** Plain-text families that are read as text whatever the extension says (source code, config, logs, notes). */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "text", "log", "rst", "adoc", "tex", "ini", "cfg", "conf", "properties", "toml", "yaml", "yml", "env",
            "java", "kt", "py", "js", "jsx", "ts", "tsx", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "swift", "scala",
            "sh", "bat", "ps1", "sql", "css", "scss", "gradle", "r", "m", "lua", "pl", "dart", "vue", "svelte", "diff", "patch");

    /** Types Tika reports for "some container, look inside": the extension is the better signal there. */
    private static final Set<String> GENERIC = Set.of(
            "application/octet-stream", "application/zip", "application/x-tika-ooxml", "application/x-tika-msoffice",
            "application/x-tika-msoffice-embedded", "application/x-tika-msworks-spreadsheet", "text/plain");

    private FileTypes() {}

    /** @return the source type, or empty when the file is not something we can read. Throws {@link NotSupported} with a helpful reason for known-unsupported media. */
    public static Optional<String> classify(String filename, byte[] data) {
        if (data.length == 0) return Optional.empty();
        String ext = extension(filename);
        String mime = TIKA.detect(data, filename == null ? "" : filename).toLowerCase(Locale.ROOT);

        // Office and e-book files are containers; Tika's core detector cannot tell them apart, so look inside. This also
        // recognises a spreadsheet that was renamed "export.bin" or a Word file called "report.zip".
        String container = sniffContainer(data);
        if (container != null) return Optional.of(container);

        if (mime.startsWith("image/")) throw new NotSupported("Images need OCR, which is not supported. Upload the text, or a PDF with a text layer.");
        if (mime.startsWith("audio/") || mime.startsWith("video/")) throw new NotSupported("Audio and video need transcription, which is not supported.");
        if (isArchive(mime) && !BY_EXTENSION.containsKey(ext)) throw new NotSupported("Archives are not supported. Upload the files inside it instead.");

        // 1. Content-based: unambiguous types win over whatever the file is called.
        String fromMime = fromMime(mime);
        if (fromMime != null) return Optional.of(fromMime);

        // 2. The extension, for containers and plain text where the content type is generic.
        if (GENERIC.contains(mime) || mime.startsWith("text/") || mime.equals("application/json") || mime.contains("xml")) {
            String byExt = BY_EXTENSION.get(ext);
            if (byExt != null) return Optional.of(byExt);
            if (TEXT_EXTENSIONS.contains(ext)) return Optional.of("TXT");
        }

        // 3. Anything else that is plainly text (an unknown extension, a dotfile, a script without one).
        if (looksLikeText(data)) {
            if (mime.equals("application/json")) return Optional.of("JSON");
            if (mime.contains("xml")) return Optional.of("XML");
            return Optional.of("TXT");
        }
        return Optional.empty();
    }

    /** ZIP-based (OOXML, OpenDocument, EPUB) and OLE2-based (legacy .doc .xls .ppt) formats, identified by what is inside. */
    static String sniffContainer(byte[] d) {
        try {
            if (d.length > 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4) {
                var entries = Zips.read(d, n -> n.equals("[Content_Types].xml") || n.equals("mimetype"));
                byte[] mimetype = entries.get("mimetype");
                if (mimetype != null) {
                    String m = new String(mimetype, java.nio.charset.StandardCharsets.US_ASCII).strip();
                    if (m.equals("application/epub+zip")) return "EPUB";
                    if (m.startsWith("application/vnd.oasis.opendocument.")) return "ODF";
                }
                byte[] types = entries.get("[Content_Types].xml");
                if (types != null) {
                    String t = new String(types, java.nio.charset.StandardCharsets.UTF_8);
                    if (t.contains("wordprocessingml")) return "DOCX";
                    if (t.contains("spreadsheetml")) return "XLSX";
                    if (t.contains("presentationml")) return "PPTX";
                }
            } else if (d.length > 8 && (d[0] & 0xff) == 0xD0 && (d[1] & 0xff) == 0xCF && (d[2] & 0xff) == 0x11 && (d[3] & 0xff) == 0xE0) {
                try (var fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem(new java.io.ByteArrayInputStream(d))) {
                    var names = fs.getRoot().getEntryNames();
                    if (names.contains("Workbook") || names.contains("Book")) return "XLS";
                    if (names.contains("WordDocument")) return "DOC";
                    if (names.contains("PowerPoint Document")) return "PPT";
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            // not readable as a container: fall through to the type and name based rules
        }
        return null;
    }

    private static String fromMime(String mime) {
        return switch (mime) {
            case "application/pdf" -> "PDF";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
                 "application/vnd.ms-word.document.macroenabled.12" -> "DOCX";
            case "application/msword" -> "DOC";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                 "application/vnd.ms-excel.sheet.macroenabled.12" -> "XLSX";
            case "application/vnd.ms-excel" -> "XLS";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                 "application/vnd.ms-powerpoint.presentation.macroenabled.12" -> "PPTX";
            case "application/vnd.ms-powerpoint" -> "PPT";
            case "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
                 "application/vnd.oasis.opendocument.presentation" -> "ODF";
            case "application/rtf", "text/rtf" -> "RTF";
            case "application/epub+zip" -> "EPUB";
            case "text/html", "application/xhtml+xml" -> "HTML";
            case "text/csv", "text/tab-separated-values" -> "CSV";
            default -> null;
        };
    }

    private static boolean isArchive(String mime) {
        return mime.equals("application/zip") || mime.equals("application/gzip") || mime.equals("application/x-gzip")
                || mime.equals("application/x-tar") || mime.equals("application/x-7z-compressed") || mime.equals("application/vnd.rar")
                || mime.equals("application/x-rar-compressed") || mime.equals("application/x-bzip2");
    }

    static String extension(String filename) {
        if (filename == null) return "";
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Heuristic for "this is text": a BOM, or no NUL bytes and almost all printable characters in the first 8 KB. */
    static boolean looksLikeText(byte[] data) {
        if (data.length == 0) return false;
        if (TextDecoding.hasBom(data)) return true;
        int n = Math.min(data.length, 8192);
        int bad = 0;
        for (int i = 0; i < n; i++) {
            int b = data[i] & 0xff;
            if (b == 0) return false;
            if (b < 0x09 || (b > 0x0d && b < 0x20) || b == 0x7f) bad++;
        }
        return bad * 100 / n < 3;
    }
}
