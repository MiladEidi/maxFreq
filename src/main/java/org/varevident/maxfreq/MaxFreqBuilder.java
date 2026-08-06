package org.varevident.maxfreq;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Streaming multi-source builder that writes the maximum eligible AF per allele.
 *
 * @author Milad EIDI
 */
public final class MaxFreqBuilder {

    public BuildStatistics build(
            List<SourceSpec> specs,
            Path annovarOutput,
            Path detailsOutput,
            double minimumOutputFrequency,
            long progressEvery,
            RegionFilter regionFilter
    ) throws IOException {
        if (minimumOutputFrequency < 0.0 || minimumOutputFrequency > 1.0) {
            throw new IllegalArgumentException("minimumOutputFrequency must be in [0,1]");
        }

        List<AnnovarSourceReader> readers = new ArrayList<>();
        PriorityQueue<MergeNode> queue = new PriorityQueue<>();
        Instant started = Instant.now();

        long uniqueSeen = 0;
        long written = 0;
        long noEligible = 0;
        long outsideRegions = 0;
        long eligibleObservations = 0;
        long sourceRows = 0;

        try (BufferedWriter annovarWriter = TextIO.writer(annovarOutput);
             BufferedWriter detailsWriter = TextIO.writer(detailsOutput)) {

            detailsWriter.write("Chr\tStart\tEnd\tRef\tAlt\tMaxAF\tSource\tPopulation\tAC\tAN\tEligibleObservations\tSourceCount\tSourceRows\n");

            for (SourceSpec spec : specs) {
                AnnovarSourceReader reader = new AnnovarSourceReader(spec);
                readers.add(reader);
                SourceAggregate aggregate = reader.next();
                if (aggregate != null) {
                    queue.add(new MergeNode(reader, aggregate));
                }
            }

            while (!queue.isEmpty()) {
                VariantKey key = queue.peek().aggregate().key();
                FrequencyObservation best = null;
                long variantEligibleObservations = 0;
                long variantSourceRows = 0;
                int sourceCount = 0;

                while (!queue.isEmpty() && queue.peek().aggregate().key().equals(key)) {
                    MergeNode node = queue.poll();
                    SourceAggregate aggregate = node.aggregate();
                    sourceCount++;
                    sourceRows++;
                    variantSourceRows += aggregate.rawLineCount();
                    variantEligibleObservations += aggregate.eligibleObservationCount();
                    if (aggregate.best() != null && aggregate.best().isBetterThan(best)) {
                        best = aggregate.best();
                    }
                    SourceAggregate next = node.reader().next();
                    if (next != null) {
                        queue.add(new MergeNode(node.reader(), next));
                    }
                }

                uniqueSeen++;
                if (regionFilter != null && !regionFilter.overlaps(key)) {
                    outsideRegions++;
                    continue;
                }
                eligibleObservations += variantEligibleObservations;
                if (best == null) {
                    noEligible++;
                } else if (best.alleleFrequency() >= minimumOutputFrequency) {
                    annovarWriter.write(key.annovarPrefix());
                    annovarWriter.write('\t');
                    annovarWriter.write(formatFrequency(best.alleleFrequency()));
                    annovarWriter.newLine();

                    detailsWriter.write(key.annovarPrefix());
                    detailsWriter.write('\t');
                    detailsWriter.write(formatFrequency(best.alleleFrequency()));
                    detailsWriter.write('\t');
                    detailsWriter.write(best.source());
                    detailsWriter.write('\t');
                    detailsWriter.write(best.population());
                    detailsWriter.write('\t');
                    detailsWriter.write(best.alleleCount() == null ? "." : best.alleleCount().toString());
                    detailsWriter.write('\t');
                    detailsWriter.write(best.alleleNumber() == null ? "." : best.alleleNumber().toString());
                    detailsWriter.write('\t');
                    detailsWriter.write(Long.toString(variantEligibleObservations));
                    detailsWriter.write('\t');
                    detailsWriter.write(Integer.toString(sourceCount));
                    detailsWriter.write('\t');
                    detailsWriter.write(Long.toString(variantSourceRows));
                    detailsWriter.newLine();
                    written++;
                }

                if (progressEvery > 0 && uniqueSeen % progressEvery == 0) {
                    long seconds = Math.max(1, Duration.between(started, Instant.now()).toSeconds());
                    System.err.printf("Processed %,d unique variants; wrote %,d; rate %,d variants/s%n",
                            uniqueSeen, written, uniqueSeen / seconds);
                }
            }
        } finally {
            IOException closeError = null;
            for (AnnovarSourceReader reader : readers) {
                try {
                    reader.close();
                } catch (IOException error) {
                    closeError = error;
                }
            }
            if (closeError != null) {
                throw closeError;
            }
        }

        return new BuildStatistics(uniqueSeen, written, noEligible, outsideRegions, eligibleObservations, sourceRows);
    }

    static String formatFrequency(double value) {
        if (value == 0.0) {
            return "0";
        }
        if (value >= 0.000001 && value < 1.0) {
            String fixed = String.format(java.util.Locale.ROOT, "%.10f", value);
            return fixed.replaceFirst("0+$", "").replaceFirst("\\.$", "");
        }
        return Double.toString(value);
    }
}
