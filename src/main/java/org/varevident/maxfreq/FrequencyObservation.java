package org.varevident.maxfreq;

/**
 * One eligible allele-frequency observation from one configured source field.
 *
 * @author Milad EIDI
 */
public record FrequencyObservation(
        VariantKey key,
        double alleleFrequency,
        String source,
        String population,
        Long alleleCount,
        Long alleleNumber,
        int sourcePriority
) {
    public FrequencyObservation {
        if (!Double.isFinite(alleleFrequency) || alleleFrequency < 0.0 || alleleFrequency > 1.0) {
            throw new IllegalArgumentException("Allele frequency must be within [0,1], got " + alleleFrequency);
        }
    }

    /** Max AF first; deterministic source priority/name/population tie-breakers. */
    public boolean isBetterThan(FrequencyObservation other) {
        if (other == null) {
            return true;
        }
        int cmp = Double.compare(alleleFrequency, other.alleleFrequency);
        if (cmp != 0) {
            return cmp > 0;
        }
        cmp = Long.compare(alleleNumber == null ? -1 : alleleNumber, other.alleleNumber == null ? -1 : other.alleleNumber);
        if (cmp != 0) {
            return cmp > 0;
        }
        cmp = Integer.compare(sourcePriority, other.sourcePriority);
        if (cmp != 0) {
            return cmp < 0;
        }
        cmp = source.compareTo(other.source);
        if (cmp != 0) {
            return cmp < 0;
        }
        return population.compareTo(other.population) < 0;
    }
}
