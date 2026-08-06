package org.varevident.maxfreq;

/**
 * Variant key columns after any header-string selectors have been resolved to one-based columns.
 *
 * @author Milad EIDI
 */
public record ResolvedVariantColumns(
        int chromosomeColumn,
        int startColumn,
        int endColumn,
        int refColumn,
        int altColumn
) {
}
