package org.varevident.maxfreq;

/**
 * Natural chromosome ordering for sorted ANNOVAR-style inputs.
 *
 * @author Milad EIDI
 */
final class ChromosomeOrder {
    private ChromosomeOrder() {
    }

    static int compare(String left, String right) {
        int leftRank = rank(left);
        int rightRank = rank(right);
        if (leftRank != rightRank) {
            return Integer.compare(leftRank, rightRank);
        }
        return left.compareTo(right);
    }

    private static int rank(String chromosome) {
        try {
            int numeric = Integer.parseInt(chromosome);
            if (numeric >= 1 && numeric <= 999) {
                return numeric;
            }
        } catch (NumberFormatException ignored) {
            // Non-numeric contig.
        }
        return switch (chromosome) {
            case "X" -> 1000;
            case "Y" -> 1001;
            case "MT" -> 1002;
            default -> 2000;
        };
    }
}
