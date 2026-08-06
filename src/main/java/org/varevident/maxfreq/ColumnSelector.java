package org.varevident.maxfreq;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One-based numeric column or a case-insensitive header substring.
 *
 * @author Milad EIDI
 */
public record ColumnSelector(String token, boolean optional) {
    public ColumnSelector {
        if (token == null) token = "";
        token = token.trim();
        if (!optional && token.isBlank()) {
            throw new IllegalArgumentException("Column selector is required");
        }
    }

    public static ColumnSelector parse(String value, boolean optional) {
        return new ColumnSelector(value, optional);
    }

    public boolean isUnavailable() {
        return optional && (token.isBlank() || token.equals("0"));
    }

    public boolean requiresHeader() {
        return !isUnavailable() && !isInteger(token);
    }

    public int resolve(String[] header, String sourceName, Path path) {
        return resolve(header, sourceName, path, false);
    }

    public int resolveOrUnavailable(String[] header, String sourceName, Path path) {
        return resolve(header, sourceName, path, true);
    }

    private int resolve(String[] header, String sourceName, Path path, boolean allowMissingHeaderMatch) {
        if (isUnavailable()) {
            return 0;
        }
        if (isInteger(token.trim())) {
            int parsed = Integer.parseInt(token);
            if (parsed < 1) {
                throw new IllegalArgumentException("Column must be >= 1");
            }
            return parsed;
        }
        if (header == null) {
            throw new IllegalArgumentException(
                    "Column selector '" + token + "' for source " + sourceName
                            + " requires a header line in " + path);
        }

        List<String> selectors = Arrays.stream(token.split("[|,]"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(ColumnSelector::normalize)
                .toList();
        List<Integer> exact = new ArrayList<>();
        List<Integer> partial = new ArrayList<>();
        for (int i = 0; i < header.length; i++) {
            String candidate = normalize(header[i]);
            for (String wanted : selectors) {
                if (candidate.equals(wanted)) exact.add(i + 1);
                if (candidate.contains(wanted)) partial.add(i + 1);
            }
        }

        exact = distinct(exact);
        partial = distinct(partial);
        if (exact.size() == 1) {
            return exact.get(0);
        }
        if (exact.size() > 1) {
            throw ambiguous(sourceName, path, exact);
        }
        if (partial.size() == 1) {
            return partial.get(0);
        }
        if (partial.isEmpty()) {
            if (allowMissingHeaderMatch) {
                return 0;
            }
            throw new IllegalArgumentException(
                    "Column selector '" + token + "' did not match any header column for source "
                            + sourceName + " at " + path);
        }
        throw ambiguous(sourceName, path, partial);
    }

    private IllegalArgumentException ambiguous(String sourceName, Path path, List<Integer> matches) {
        return new IllegalArgumentException(
                "Column selector '" + token + "' matched multiple header columns for source "
                        + sourceName + " at " + path + ": " + matches
                        + ". Use a more specific substring or a numeric column.");
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static boolean isInteger(String value) {
        if (value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> distinct(List<Integer> values) {
        List<Integer> result = new ArrayList<>();
        for (Integer value : values) {
            if (!result.contains(value)) result.add(value);
        }
        return result;
    }
}
