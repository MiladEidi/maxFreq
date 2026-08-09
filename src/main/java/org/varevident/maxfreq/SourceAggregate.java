package org.varevident.maxfreq;

import java.util.List;

/**
 * Aggregated best observation and row counts for a source/allele key.
 *
 * @author Milad EIDI
 */
record SourceAggregate(
        VariantKey key,
        FrequencyObservation best,
        List<FrequencyObservation> observations,
        long eligibleObservationCount,
        long rawLineCount
) {
}
