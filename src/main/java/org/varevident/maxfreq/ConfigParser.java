package org.varevident.maxfreq;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for source configuration TSV files.
 *
 * @author Milad EIDI
 */
public final class ConfigParser {
    private ConfigParser() {
    }

    public static List<SourceSpec> parse(Path configPath) throws IOException {
        Path configDir = configPath.toAbsolutePath().getParent();
        try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            String headerLine = nextDataLine(reader);
            if (headerLine == null) {
                throw new IllegalArgumentException("Empty configuration file: " + configPath);
            }
            String[] headers = splitFields(headerLine);
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                index.put(headers[i].trim().toLowerCase(Locale.ROOT), i);
            }
            require(index, "source", "path", "frequency_fields");

            List<SourceSpec> specs = new ArrayList<>();
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.trim().startsWith("#")) continue;
                String[] fields = splitFields(line);
                try {
                    String source = get(fields, index, "source");
                    Path path = Path.of(get(fields, index, "path"));
                    if (!path.isAbsolute()) {
                        path = configDir.resolve(path).normalize();
                    }
                    List<FrequencyField> frequencyFields = parseFrequencyFields(get(fields, index, "frequency_fields"));
                    specs.add(new SourceSpec(
                            source,
                            path,
                            selector(optional(fields, index, "chr_col", "chr|chrom|chromosome|#chrom")),
                            selector(optional(fields, index, "start_col", "start|pos|position")),
                            selector(optional(fields, index, "end_col", "end|stop"), true),
                            selector(optional(fields, index, "ref_col", "ref|reference")),
                            selector(optional(fields, index, "alt_col", "alt|alternate|alternative")),
                            frequencyFields,
                            parseLong(optional(fields, index, "min_an", "0")),
                            parseBoolean(optional(fields, index, "require_an", "false")),
                            parseInt(optional(fields, index, "priority", "100"))
                    ));
                } catch (RuntimeException error) {
                    throw new IllegalArgumentException(
                            "Invalid config line " + lineNumber + ": " + error.getMessage(), error);
                }
            }
            if (specs.isEmpty()) {
                throw new IllegalArgumentException("Configuration contains no sources");
            }
            return List.copyOf(specs);
        }
    }

    private static String nextDataLine(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank() && !line.trim().startsWith("#")) {
                return line;
            }
        }
        return null;
    }

    private static void require(Map<String, Integer> index, String... fields) {
        for (String field : fields) {
            if (!index.containsKey(field)) {
                throw new IllegalArgumentException("Missing required config column: " + field);
            }
        }
    }

    private static List<FrequencyField> parseFrequencyFields(String value) {
        List<FrequencyField> result = new ArrayList<>();
        for (String token : value.split(";")) {
            if (!token.isBlank()) {
                result.add(FrequencyField.parse(token));
            }
        }
        return result;
    }

    private static ColumnSelector selector(String value) {
        return ColumnSelector.parse(value, false);
    }

    private static ColumnSelector selector(String value, boolean optional) {
        return ColumnSelector.parse(value, optional);
    }

    private static String[] splitFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (!quoted && Character.isWhitespace(ch)) {
                if (current.length() > 0) {
                    fields.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unclosed quote in config line");
        }
        if (current.length() > 0) {
            fields.add(current.toString());
        }
        return fields.toArray(String[]::new);
    }

    private static String get(String[] fields, Map<String, Integer> index, String name) {
        Integer position = index.get(name);
        if (position == null || position >= fields.length) {
            throw new IllegalArgumentException("Missing value for " + name);
        }
        String value = fields[position].trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty value for " + name);
        }
        return value;
    }

    private static String optional(String[] fields, Map<String, Integer> index, String name, String defaultValue) {
        Integer position = index.get(name);
        if (position == null || position >= fields.length || fields[position].isBlank()) {
            return defaultValue;
        }
        return fields[position].trim();
    }

    private static int parseInt(String value) {
        return Integer.parseInt(value.trim());
    }

    private static long parseLong(String value) {
        return Long.parseLong(value.trim());
    }

    private static boolean parseBoolean(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1" -> true;
            case "false", "no", "0" -> false;
            default -> throw new IllegalArgumentException("Expected boolean, got: " + value);
        };
    }
}
