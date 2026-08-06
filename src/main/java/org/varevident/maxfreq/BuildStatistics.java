package org.varevident.maxfreq;

/**
 * Summary counters returned after a build.
 *
 * @author Milad EIDI
 */
record BuildStatistics(
        long uniqueVariantsSeen,
        long variantsWritten,
        long variantsWithoutEligibleFrequency,
        long variantsOutsideRegions,
        long eligibleObservations,
        long sourceRows
) {
}
