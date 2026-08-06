package org.varevident.maxfreq;

import java.util.Locale;
import java.util.Objects;

/**
 * Exact ANNOVAR allele key. Coordinates are 1-based and inclusive.
 *
 * @author Milad EIDI
 */
public record VariantKey(String chromosome, long start, long end, String ref, String alt)
        implements Comparable<VariantKey> {

    public VariantKey {
        chromosome = normalizeChromosome(chromosome);
        ref = normalizeAllele(ref);
        alt = normalizeAllele(alt);
        if (start < 1 || end < start) {
            throw new IllegalArgumentException("Invalid coordinates: " + chromosome + ":" + start + "-" + end);
        }
    }

    private static String normalizeAllele(String allele) {
        Objects.requireNonNull(allele, "allele");
        String normalized = allele.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Empty allele");
        }
        return normalized;
    }

    public static String normalizeChromosome(String chromosome) {
        Objects.requireNonNull(chromosome, "chromosome");
        String value = chromosome.trim();
        if (value.regionMatches(true, 0, "chr", 0, 3)) {
            value = value.substring(3);
        }
        value = value.toUpperCase(Locale.ROOT);
        if (value.equals("M")) {
            return "MT";
        }
        return value;
    }

    @Override
    public int compareTo(VariantKey other) {
        int cmp = ChromosomeOrder.compare(chromosome, other.chromosome);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Long.compare(start, other.start);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Long.compare(end, other.end);
        if (cmp != 0) {
            return cmp;
        }
        cmp = ref.compareTo(other.ref);
        if (cmp != 0) {
            return cmp;
        }
        return alt.compareTo(other.alt);
    }

    public String annovarPrefix() {
        return chromosome + '\t' + start + '\t' + end + '\t' + ref + '\t' + alt;
    }
}
