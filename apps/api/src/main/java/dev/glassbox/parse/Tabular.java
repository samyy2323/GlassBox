package dev.glassbox.parse;

import dev.glassbox.parse.ParsedDocument.Kind;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns table rows (CSV, spreadsheets) into blocks. A row becomes "Header: value; Header: value" because that reads as
 * a sentence a search can match ("Price: 10" next to "Plan: Basic"), where a bare "Basic | 10" loses what the numbers mean.
 */
final class Tabular {
    static final int MAX_COLUMNS = 200;
    /** Rows beyond this are not indexed; a visible note says so rather than truncating silently. */
    static final int MAX_ROWS = 50_000;

    private Tabular() {}

    /** @return true if rows were cut off at {@link #MAX_ROWS}. The first non-blank row is the header. */
    static boolean append(TextBuilder tb, List<List<String>> rows) {
        List<String> header = null;
        List<String> headerRow = null;
        int emitted = 0;
        boolean truncated = false;
        for (List<String> raw : rows) {
            List<String> row = raw.size() > MAX_COLUMNS ? raw.subList(0, MAX_COLUMNS) : raw;
            if (row.stream().allMatch(c -> c == null || c.isBlank())) continue;
            if (header == null) {
                header = usableHeader(row);
                if (header != null) {
                    headerRow = row;
                    continue;
                }
                header = List.of(); // the first row is data: keep going without headers
            }
            if (emitted >= MAX_ROWS) {
                truncated = true;
                break;
            }
            tb.block(Kind.TABLE_ROW, describe(header, row));
            emitted++;
        }
        // A table that is only a "header" (one line of text in a sheet) has no data rows: keep the text rather than lose it.
        if (emitted == 0 && headerRow != null) tb.block(Kind.TABLE_ROW, describe(List.of(), headerRow));
        if (truncated) tb.block(Kind.PARAGRAPH, "Note: only the first " + MAX_ROWS + " rows of this table were indexed.");
        return truncated;
    }

    /** A header row is usable when it has mostly non-empty, mostly distinct cells that are not just numbers. */
    private static List<String> usableHeader(List<String> row) {
        long filled = row.stream().filter(c -> c != null && !c.isBlank()).count();
        Set<String> distinct = new HashSet<>();
        long numeric = 0;
        for (String c : row) {
            if (c == null || c.isBlank()) continue;
            distinct.add(c.strip().toLowerCase());
            if (c.strip().matches("-?[\\d.,]+%?")) numeric++;
        }
        if (filled < Math.max(1, row.size() * 2 / 3) || distinct.size() < filled || numeric * 2 > filled) return null;
        return row.stream().map(c -> c == null ? "" : c.strip()).toList();
    }

    private static String describe(List<String> header, List<String> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
            String v = row.get(i) == null ? "" : row.get(i).strip();
            if (v.isEmpty()) continue;
            if (sb.length() > 0) sb.append(header.isEmpty() ? " | " : "; ");
            String h = i < header.size() ? header.get(i) : "";
            if (!h.isEmpty()) sb.append(h).append(": ");
            sb.append(v);
        }
        return sb.toString();
    }
}
