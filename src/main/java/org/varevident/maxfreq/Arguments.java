package org.varevident.maxfreq;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Command-line option parser for MaxFreq commands.
 *
 * @author Milad EIDI
 */
final class Arguments {
    private final Map<String, String> values;

    private Arguments(Map<String, String> values) {
        this.values = values;
    }

    static Arguments parse(String[] args, int start) {
        Map<String, String> values = new HashMap<>();
        for (int i = start; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("Expected --option, got: " + token);
            }
            String name = token.substring(2);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Empty option name");
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                values.put(name, "true");
            } else {
                values.put(name, args[++i]);
            }
        }
        return new Arguments(values);
    }

    String required(String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option --" + name);
        }
        return value;
    }

    String optional(String name, String defaultValue) {
        return values.getOrDefault(name, defaultValue);
    }

    Path requiredPath(String name) {
        return Path.of(required(name));
    }

    Path optionalPath(String name, Path defaultValue) {
        String value = values.get(name);
        return value == null ? defaultValue : Path.of(value);
    }

    double optionalDouble(String name, double defaultValue) {
        String value = values.get(name);
        return value == null ? defaultValue : Double.parseDouble(value);
    }

    long optionalLong(String name, long defaultValue) {
        String value = values.get(name);
        return value == null ? defaultValue : Long.parseLong(value);
    }
}
