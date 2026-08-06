package org.varevident.maxfreq;

/**
 * Aggregated best observation and row counts for a source/allele key.
 *
 * @author Milad EIDI
 */
record SourceAggregate(
        VariantKey key,
        FrequencyObservation best,
        long eligibleObservationCount,
        long rawLineCount
) {
}
