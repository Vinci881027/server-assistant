package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.util.CommandMarkers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CommandResultPostProcessor {

    private static final Pattern QUERY_CSV_PATTERN = Pattern.compile(
            "--query-([a-z0-9._-]+)=('([^']+)'|\"([^\"]+)\"|([^\\s]+))",
            Pattern.CASE_INSENSITIVE);

    private CommandResultPostProcessor() {}

    static String normalizeQueryCsvForToolResult(String command, String output) {
        if (command == null || output == null) return output;

        QueryCsvSpec spec = parseQueryCsvSpec(command);
        if (spec == null) return output;

        String trimmed = output.trim();
        if (trimmed.isEmpty()) return output;
        if (trimmed.startsWith(CommandMarkers.SECURITY_VIOLATION) || trimmed.startsWith("[ERROR]")) return output;

        List<List<String>> rows = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        for (String rawLine : output.split("\\r?\\n")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[System Notice]")
                    || line.startsWith("[⚠️ System Notice")
                    || line.startsWith("[系統提示]")) {
                notices.add(line);
                continue;
            }

            List<String> cols = parseCsvLine(line);
            if (cols == null || cols.isEmpty()) return output;
            if (isHeaderRow(cols, spec.fields())) continue;
            if (cols.size() != spec.fields().size()) return output;

            List<String> formattedRow = new ArrayList<>(cols.size());
            for (int i = 0; i < cols.size(); i++) {
                String field = spec.fields().get(i);
                formattedRow.add(formatQueryValue(field, cols.get(i), spec.nounits()));
            }
            rows.add(formattedRow);
        }

        if (rows.isEmpty()) return output;

        StringBuilder sb = new StringBuilder();
        sb.append("Structured query output (query-").append(spec.queryType()).append(")\n");
        sb.append("Field mapping: ").append(String.join(",", spec.fields())).append("\n\n");
        sb.append("| ");
        for (int i = 0; i < spec.fields().size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(escapeMarkdownTableCell(formatHeaderLabel(spec.fields().get(i), spec.nounits())));
        }
        sb.append(" |\n");
        sb.append("| ");
        for (int i = 0; i < spec.fields().size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append("---");
        }
        sb.append(" |\n");
        for (List<String> row : rows) {
            sb.append("| ");
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(escapeMarkdownTableCell(row.get(i)));
            }
            sb.append(" |\n");
        }

        if (!notices.isEmpty()) {
            sb.append("\n");
            for (String notice : notices) {
                sb.append(notice).append("\n");
            }
        }

        return sb.toString().trim();
    }

    private static QueryCsvSpec parseQueryCsvSpec(String command) {
        String normalized = command.trim().replaceAll("\\s+", " ");
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.contains("--format=csv")) return null;

        Matcher matcher = QUERY_CSV_PATTERN.matcher(normalized);
        if (!matcher.find()) return null;

        String queryType = matcher.group(1) == null ? "" : matcher.group(1).trim();
        String fieldsRaw = firstNonBlank(matcher.group(3), matcher.group(4), matcher.group(5));
        if (fieldsRaw == null || fieldsRaw.isBlank()) return null;

        List<String> fields = new ArrayList<>();
        for (String token : fieldsRaw.split("\\s*,\\s*")) {
            String t = token == null ? "" : token.trim();
            if (!t.isEmpty()) fields.add(t);
        }
        if (fields.isEmpty()) return null;

        boolean nounits = lower.contains("nounits");
        return new QueryCsvSpec(queryType, fields, nounits);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static List<String> parseCsvLine(String line) {
        if (line == null) return null;
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                    continue;
                }
                inQuotes = !inQuotes;
                continue;
            }
            if (c == ',' && !inQuotes) {
                cols.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (inQuotes) return null;
        cols.add(cur.toString().trim());
        return cols;
    }

    private static boolean isHeaderRow(List<String> cols, List<String> fields) {
        if (cols == null || fields == null) return false;
        if (cols.size() != fields.size()) return false;
        for (int i = 0; i < cols.size(); i++) {
            if (!normalizeFieldToken(cols.get(i)).equals(normalizeFieldToken(fields.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeFieldToken(String text) {
        if (text == null) return "";
        return text.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s._-]+", "");
    }

    private static String formatQueryValue(String field, String value, boolean nounits) {
        if (value == null || value.isBlank()) return "-";
        String v = value.trim();
        if (!nounits) return v;

        String unit = inferUnitFromField(field);
        if (unit == null || !v.matches("^-?\\d+(\\.\\d+)?$")) return v;
        if ("%".equals(unit)) return v + "%";
        return v + " " + unit;
    }

    private static String formatHeaderLabel(String field, boolean nounits) {
        if (field == null || field.isBlank()) return "-";
        String label = field.trim().replace('.', ' ').replace('_', ' ');
        label = label.replaceAll("\\s+", " ");
        if (!nounits) return label;

        String unit = inferUnitFromField(field);
        if (unit == null) return label;
        return label + " (" + unit + ")";
    }

    private static String inferUnitFromField(String field) {
        if (field == null) return null;
        String f = field.toLowerCase(Locale.ROOT);
        if (f.contains("temperature")) return "C";
        if (f.contains("utilization")) return "%";
        if (f.contains("power")) return "W";
        if (f.contains("memory")
                && (f.contains("used") || f.contains("total") || f.contains("free") || f.contains("reserved"))) {
            return "MiB";
        }
        return null;
    }

    private record QueryCsvSpec(String queryType, List<String> fields, boolean nounits) {}

    private static String escapeMarkdownTableCell(String raw) {
        if (raw == null || raw.isBlank()) return "-";
        return raw.replace("|", "\\|").replace("\n", " ").trim();
    }
}
