package dev.glassbox.parse;

import java.util.ArrayList;
import java.util.List;

/** CSV and TSV (RFC 4180 quoting). The delimiter is detected from the first line. */
public class CsvParser implements DocumentParser {
    @Override
    public ParsedDocument parse(byte[] data) {
        String text = TextDecoding.decode(data);
        TextBuilder tb = new TextBuilder();
        Tabular.append(tb, rows(text, delimiter(text)));
        return DocumentParser.nonEmpty(tb, "The table");
    }

    static char delimiter(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        int tabs = 0, commas = 0, semis = 0;
        boolean quoted = false;
        for (char c : first.toCharArray()) {
            if (c == '"') quoted = !quoted;
            else if (!quoted) {
                if (c == '\t') tabs++;
                else if (c == ',') commas++;
                else if (c == ';') semis++;
            }
        }
        if (tabs > commas && tabs >= semis) return '\t';
        return semis > commas ? ';' : ',';
    }

    /** Minimal state machine: quoted fields may contain delimiters, doubled quotes and line breaks. */
    static List<List<String>> rows(String text, char delim) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else quoted = false;
                } else field.append(c);
            } else if (c == '"' && field.length() == 0) {
                quoted = true;
            } else if (c == delim) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                if (rows.size() > Tabular.MAX_ROWS + 2) return rows; // enough: Tabular reports the truncation
            } else field.append(c);
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
