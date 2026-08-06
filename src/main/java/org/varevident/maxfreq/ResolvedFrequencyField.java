package org.varevident.maxfreq;

/**
 * Frequency field after any header-string selectors have been resolved to one-based columns.
 *
 * @author Milad EIDI
 */
public record ResolvedFrequencyField(
        int afColumn,
        String population,
        int acColumn,
        int anColumn,
        double scale
) {
}
