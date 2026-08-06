package org.varevident.maxfreq;

/**
 * Configured frequency column, population label, optional AC/AN columns, and scale.
 *
 * @author Milad EIDI
 */
public record FrequencyField(
        ColumnSelector afColumn,
        String population,
        ColumnSelector acColumn,
        ColumnSelector anColumn,
        double scale
) {
    public FrequencyField {
        if (afColumn == null || afColumn.isUnavailable()) {
            throw new IllegalArgumentException("AF column is required");
        }
        if (population == null || population.isBlank()) {
            throw new IllegalArgumentException("Population label is required");
        }
        if (acColumn == null) {
            acColumn = ColumnSelector.parse("0", true);
        }
        if (anColumn == null) {
            anColumn = ColumnSelector.parse("0", true);
        }
        if (!(scale > 0.0)) {
            throw new IllegalArgumentException("Scale must be > 0");
        }
    }

    public static FrequencyField parse(String token) {
        String[] parts = token.trim().split(":", -1);
        if (parts.length == 1) {
            return new FrequencyField(ColumnSelector.parse(parts[0], false), "global",
                    ColumnSelector.parse("0", true), ColumnSelector.parse("0", true), 1.0);
        }
        if (parts.length < 2 || parts.length > 5) {
            throw new IllegalArgumentException(
                    "Frequency field must be AF_COLUMN:LABEL[:AC_COLUMN[:AN_COLUMN[:SCALE]]], got: " + token);
        }
        ColumnSelector af = ColumnSelector.parse(parts[0], false);
        String label = parts[1].trim();
        ColumnSelector ac = parts.length >= 3 ? ColumnSelector.parse(parts[2], true) : ColumnSelector.parse("0", true);
        ColumnSelector an = parts.length >= 4 ? ColumnSelector.parse(parts[3], true) : ColumnSelector.parse("0", true);
        double scale = parts.length >= 5 && !parts[4].isBlank() ? Double.parseDouble(parts[4]) : 1.0;
        return new FrequencyField(af, label, ac, an, scale);
    }

    public boolean requiresHeader() {
        return afColumn.requiresHeader() || acColumn.requiresHeader() || anColumn.requiresHeader();
    }

    public ResolvedFrequencyField resolve(String[] header, String sourceName, java.nio.file.Path path) {
        return new ResolvedFrequencyField(
                afColumn.resolve(header, sourceName, path),
                population,
                acColumn.resolve(header, sourceName, path),
                anColumn.resolve(header, sourceName, path),
                scale
        );
    }
}
