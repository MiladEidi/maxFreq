package org.varevident.maxfreq;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            Path subpopulationOutput,
            Path detailsOutput,
            double minimumOutputFrequency,
            long progressEvery,
            RegionFilter regionFilter,
            boolean skipBadRows,
            long maxBadRows
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
        List<String> subpopulationColumns = subpopulationColumns(specs);

        try (BufferedWriter annovarWriter = TextIO.writer(annovarOutput);
             BufferedWriter subpopulationWriter = TextIO.writer(subpopulationOutput);
             BufferedWriter detailsWriter = TextIO.writer(detailsOutput)) {

            writeSubpopulationHeader(subpopulationWriter, subpopulationColumns);
            detailsWriter.write("Chr\tStart\tEnd\tRef\tAlt\tMaxAF\tSource\tPopulation\tAC\tAN\tEligibleObservations\tSourceCount\tSourceRows\n");

            for (SourceSpec spec : specs) {
                AnnovarSourceReader reader = new AnnovarSourceReader(spec, skipBadRows, maxBadRows);
                readers.add(reader);
                SourceAggregate aggregate = reader.next();
                if (aggregate != null) {
                    queue.add(new MergeNode(reader, aggregate));
                }
            }

            while (!queue.isEmpty()) {
                VariantKey key = queue.peek().aggregate().key();
                FrequencyObservation best = null;
                Map<String, FrequencyObservation> observationsByColumn = new LinkedHashMap<>();
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
                    for (FrequencyObservation observation : aggregate.observations()) {
                        String column = subpopulationColumn(observation.source(), observation.population());
                        FrequencyObservation previous = observationsByColumn.get(column);
                        if (observation.isBetterThan(previous)) {
                            observationsByColumn.put(column, observation);
                        }
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

                    subpopulationWriter.write(key.annovarPrefix());
                    subpopulationWriter.write('\t');
                    subpopulationWriter.write(formatFrequency(best.alleleFrequency()));
                    for (String column : subpopulationColumns) {
                        subpopulationWriter.write('\t');
                        FrequencyObservation observation = observationsByColumn.get(column);
                        subpopulationWriter.write(observation == null ? "." : formatFrequency(observation.alleleFrequency()));
                    }
                    subpopulationWriter.newLine();

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
                    System.err.printf(
                            "Analysed %,d variants; wrote %,d; elapsed %s; rate %,d variants/s%n",
                            uniqueSeen, written, formatDuration(seconds), uniqueSeen / seconds);
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

        long skippedBadRows = readers.stream().mapToLong(AnnovarSourceReader::skippedBadRows).sum();
        return new BuildStatistics(uniqueSeen, written, noEligible, outsideRegions, eligibleObservations, sourceRows, skippedBadRows);
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

    private static List<String> subpopulationColumns(List<SourceSpec> specs) {
        List<String> columns = new ArrayList<>();
        for (SourceSpec spec : specs) {
            for (FrequencyField field : spec.frequencyFields()) {
                columns.add(subpopulationColumn(spec.name(), field.population()));
            }
        }
        return List.copyOf(columns);
    }

    private static String subpopulationColumn(String source, String population) {
        return sanitizeColumnName(source) + "_" + sanitizeColumnName(population);
    }

    private static String sanitizeColumnName(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return "unknown";
        }
        return trimmed.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static void writeSubpopulationHeader(BufferedWriter writer, List<String> subpopulationColumns) throws IOException {
        writer.write("Chr\tStart\tEnd\tRef\tAlt\tMaxFreq");
        for (String column : subpopulationColumns) {
            writer.write('\t');
            writer.write(column);
        }
        writer.newLine();
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%dh%02dm%02ds", hours, minutes, remainingSeconds);
        }
        if (minutes > 0) {
            return String.format(java.util.Locale.ROOT, "%dm%02ds", minutes, remainingSeconds);
        }
        return remainingSeconds + "s";
    }
}
