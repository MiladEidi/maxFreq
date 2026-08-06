package org.varevident.maxfreq;

import java.nio.file.Path;
import java.util.List;

/**
 * Configuration for one ANNOVAR-like tab-delimited frequency file.
 *
 * @author Milad EIDI
 */
public record SourceSpec(
        String name,
        Path path,
        ColumnSelector chromosomeColumn,
        ColumnSelector startColumn,
        ColumnSelector endColumn,
        ColumnSelector refColumn,
        ColumnSelector altColumn,
        List<FrequencyField> frequencyFields,
        long minimumAlleleNumber,
        boolean requireAlleleNumber,
        int priority
) {
    public SourceSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Source name is required");
        }
        if (path == null) {
            throw new IllegalArgumentException("Source path is required");
        }
        if (chromosomeColumn == null || chromosomeColumn.isUnavailable()) {
            throw new IllegalArgumentException("Chromosome column is required");
        }
        if (startColumn == null || startColumn.isUnavailable()) {
            throw new IllegalArgumentException("Start column is required");
        }
        if (endColumn == null) {
            endColumn = ColumnSelector.parse("0", true);
        }
        if (refColumn == null || refColumn.isUnavailable()) {
            throw new IllegalArgumentException("REF column is required");
        }
        if (altColumn == null || altColumn.isUnavailable()) {
            throw new IllegalArgumentException("ALT column is required");
        }
        if (frequencyFields == null || frequencyFields.isEmpty()) {
            throw new IllegalArgumentException("At least one frequency field is required for " + name);
        }
        frequencyFields = List.copyOf(frequencyFields);
        if (minimumAlleleNumber < 0) {
            throw new IllegalArgumentException("minimumAlleleNumber cannot be negative");
        }
    }

    public boolean requiresHeader() {
        return chromosomeColumn.requiresHeader()
                || startColumn.requiresHeader()
                || endColumn.requiresHeader()
                || refColumn.requiresHeader()
                || altColumn.requiresHeader()
                || frequencyFields.stream().anyMatch(FrequencyField::requiresHeader);
    }

    public ResolvedVariantColumns resolveVariantColumns(String[] header) {
        return new ResolvedVariantColumns(
                chromosomeColumn.resolve(header, name, path),
                startColumn.resolve(header, name, path),
                endColumn.resolveOrUnavailable(header, name, path),
                refColumn.resolve(header, name, path),
                altColumn.resolve(header, name, path)
        );
    }
}
