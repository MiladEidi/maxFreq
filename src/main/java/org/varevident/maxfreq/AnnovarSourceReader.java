package org.varevident.maxfreq;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Streaming reader for a sorted ANNOVAR-style tab-delimited database.
 * Consecutive duplicate allele keys within a source are collapsed.
 *
 * @author Milad EIDI
 */
public final class AnnovarSourceReader implements Closeable {
    private final SourceSpec spec;
    private final BufferedReader reader;
    private final String[] header;
    private final ResolvedVariantColumns variantColumns;
    private final List<ResolvedFrequencyField> frequencyFields;
    private final boolean skipBadRows;
    private final long maxBadRows;
    private long lineNumber;
    private long skippedBadRows;
    private ParsedLine buffered;
    private List<SourceAggregate> aggregateBuffer = List.of();
    private int aggregateBufferIndex;

    public AnnovarSourceReader(SourceSpec spec) throws IOException {
        this(spec, false, 0);
    }

    public AnnovarSourceReader(SourceSpec spec, boolean skipBadRows, long maxBadRows) throws IOException {
        this.spec = spec;
        this.reader = TextIO.reader(spec.path());
        this.skipBadRows = skipBadRows;
        this.maxBadRows = maxBadRows;
        this.header = spec.requiresHeader() ? readHeader() : null;
        this.variantColumns = spec.resolveVariantColumns(header);
        this.frequencyFields = resolveFrequencyFields(header);
    }

    public SourceSpec spec() {
        return spec;
    }

    public long lineNumber() {
        return lineNumber;
    }

    public long skippedBadRows() {
        return skippedBadRows;
    }

    public String describeResolvedColumns() {
        StringBuilder text = new StringBuilder();
        text.append("variant columns: chr=").append(describeColumn(variantColumns.chromosomeColumn()))
                .append(", start=").append(describeColumn(variantColumns.startColumn()))
                .append(", end=").append(variantColumns.endColumn() == 0 ? "derived-from-ref" : describeColumn(variantColumns.endColumn()))
                .append(", ref=").append(describeColumn(variantColumns.refColumn()))
                .append(", alt=").append(describeColumn(variantColumns.altColumn()))
                .append("; frequency fields: ");
        for (int i = 0; i < frequencyFields.size(); i++) {
            ResolvedFrequencyField field = frequencyFields.get(i);
            if (i > 0) {
                text.append("; ");
            }
            text.append(field.population()).append(" AF=").append(describeColumn(field.afColumn()));
            if (field.acColumn() != 0) {
                text.append(", AC=").append(describeColumn(field.acColumn()));
            }
            if (field.anColumn() != 0) {
                text.append(", AN=").append(describeColumn(field.anColumn()));
            }
        }
        return text.toString();
    }

    public SourceAggregate next() throws IOException {
        if (aggregateBufferIndex < aggregateBuffer.size()) {
            return aggregateBuffer.get(aggregateBufferIndex++);
        }

        ParsedLine first = buffered != null ? takeBuffered() : readNextParsed();
        if (first == null) {
            return null;
        }

        Map<VariantKey, MutableAggregate> byKey = new TreeMap<>();
        addParsedLine(byKey, first);

        ParsedLine next;
        while ((next = readNextParsed()) != null) {
            int comparison = compareLocus(next.key(), first.key());
            if (comparison < 0) {
                throw new IllegalStateException(
                        "Input is not sorted for source " + spec.name() + " at " + spec.path() + ":" + lineNumber
                                + ". Previous grouped locus=" + locus(first.key()) + ", current locus=" + locus(next.key()));
            }
            if (comparison > 0) {
                buffered = next;
                break;
            }
            addParsedLine(byKey, next);
        }

        aggregateBuffer = byKey.entrySet().stream()
                .map(entry -> entry.getValue().toAggregate(entry.getKey()))
                .toList();
        aggregateBufferIndex = 1;
        return aggregateBuffer.get(0);
    }

    private ParsedLine takeBuffered() {
        ParsedLine value = buffered;
        buffered = null;
        return value;
    }

    private ParsedLine readNextParsed() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank() || line.charAt(0) == '#') {
                continue;
            }
            String[] columns = line.split("\t", -1);
            try {
                long start = parseRequiredLong(columns, variantColumns.startColumn(), "Start");
                String ref = value(columns, variantColumns.refColumn());
                VariantKey key = new VariantKey(
                        value(columns, variantColumns.chromosomeColumn()),
                        start,
                        resolveEnd(columns, start, ref),
                        ref,
                        value(columns, variantColumns.altColumn())
                );
                FrequencyObservation best = null;
                List<FrequencyObservation> observations = new ArrayList<>();
                long eligible = 0;
                for (ResolvedFrequencyField field : frequencyFields) {
                    Double af = parseOptionalDouble(
                            valueOrEmpty(columns, field.afColumn()),
                            "AF field '" + field.population() + "'",
                            field.afColumn());
                    if (af == null) {
                        continue;
                    }
                    af /= field.scale();
                    if (af < 0.0 || af > 1.0 || !Double.isFinite(af)) {
                        continue;
                    }

                    Long ac = field.acColumn() == 0 ? null : parseOptionalLong(
                            valueOrEmpty(columns, field.acColumn()),
                            "AC field '" + field.population() + "'",
                            field.acColumn());
                    Long an = field.anColumn() == 0 ? null : parseOptionalLong(
                            valueOrEmpty(columns, field.anColumn()),
                            "AN field '" + field.population() + "'",
                            field.anColumn());
                    if (spec.requireAlleleNumber() && an == null) {
                        continue;
                    }
                    if (an != null && an < spec.minimumAlleleNumber()) {
                        continue;
                    }

                    FrequencyObservation observation = new FrequencyObservation(
                            key, af, spec.name(), field.population(), ac, an, spec.priority());
                    observations.add(observation);
                    eligible++;
                    if (observation.isBetterThan(best)) {
                        best = observation;
                    }
                }
                return new ParsedLine(key, best, List.copyOf(observations), eligible);
            } catch (RuntimeException error) {
                String message = "Cannot parse " + spec.name() + " at " + spec.path() + ":" + lineNumber + ": "
                        + error.getMessage() + ". Row has " + columns.length + " tab-delimited columns; preview: "
                        + preview(columns);
                if (!skipBadRows) {
                    throw new IllegalArgumentException(message, error);
                }
                skippedBadRows++;
                if (maxBadRows >= 0 && skippedBadRows > maxBadRows) {
                    throw new IllegalArgumentException(
                            "Too many bad rows for " + spec.name() + ": skipped " + skippedBadRows
                                    + " rows, limit is " + maxBadRows + ". Last error: " + message, error);
                }
                if (skippedBadRows <= 20 || skippedBadRows % 1000 == 0) {
                    System.err.printf("WARNING: Skipping bad row %,d for source %s: %s%n",
                            skippedBadRows, spec.name(), message);
                }
            }
        }
        return null;
    }

    private static void addParsedLine(Map<VariantKey, MutableAggregate> byKey, ParsedLine line) {
        MutableAggregate aggregate = byKey.computeIfAbsent(line.key(), ignored -> new MutableAggregate());
        aggregate.rawLineCount++;
        aggregate.eligibleObservationCount += line.eligibleObservationCount();
        aggregate.observations.addAll(line.observations());
        if (line.bestObservation() != null && line.bestObservation().isBetterThan(aggregate.best)) {
            aggregate.best = line.bestObservation();
        }
    }

    private static int compareLocus(VariantKey left, VariantKey right) {
        int cmp = ChromosomeOrder.compare(left.chromosome(), right.chromosome());
        if (cmp != 0) {
            return cmp;
        }
        return Long.compare(left.start(), right.start());
    }

    private static String locus(VariantKey key) {
        return key.chromosome() + ":" + key.start();
    }

    private static String value(String[] columns, int oneBasedColumn) {
        String value = valueOrEmpty(columns, oneBasedColumn);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Empty column " + oneBasedColumn);
        }
        return value.trim();
    }

    private static String valueOrEmpty(String[] columns, int oneBasedColumn) {
        int index = oneBasedColumn - 1;
        if (index < 0 || index >= columns.length) return "";
        return columns[index].trim();
    }

    private long resolveEnd(String[] columns, long start, String ref) {
        if (variantColumns.endColumn() > 0) {
            return parseRequiredLong(columns, variantColumns.endColumn(), "End");
        }
        return start + referenceLength(ref) - 1;
    }

    private static long referenceLength(String ref) {
        if (ref == null || ref.isBlank() || isMissing(ref)) {
            return 1;
        }
        String normalized = ref.trim();
        if (normalized.equals("-") || normalized.startsWith("<")) {
            return 1;
        }
        return normalized.length();
    }

    private long parseRequiredLong(String[] columns, int oneBasedColumn, String fieldName) {
        String raw = value(columns, oneBasedColumn);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    fieldName + " column " + describeColumn(oneBasedColumn) + " must be an integer, got '" + raw + "'", error);
        }
    }

    private Double parseOptionalDouble(String value, String fieldName, int oneBasedColumn) {
        if (isMissing(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    fieldName + " column " + describeColumn(oneBasedColumn) + " must be numeric, got '" + value + "'", error);
        }
    }

    private Long parseOptionalLong(String value, String fieldName, int oneBasedColumn) {
        if (isMissing(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    fieldName + " column " + describeColumn(oneBasedColumn) + " must be an integer, got '" + value + "'", error);
        }
    }

    private static boolean isMissing(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.equals(".") || normalized.equals("NA") || normalized.equals("N/A")
                || normalized.equals("NULL") || normalized.equals("-");
    }

    private List<ResolvedFrequencyField> resolveFrequencyFields(String[] header) {
        List<ResolvedFrequencyField> resolved = new ArrayList<>();
        for (FrequencyField field : spec.frequencyFields()) {
            resolved.add(field.resolve(header, spec.name(), spec.path()));
        }
        return List.copyOf(resolved);
    }

    private String describeColumn(int oneBasedColumn) {
        if (oneBasedColumn <= 0) {
            return "unavailable";
        }
        if (header != null && oneBasedColumn <= header.length) {
            return oneBasedColumn + " (" + header[oneBasedColumn - 1] + ")";
        }
        return Integer.toString(oneBasedColumn);
    }

    private static String preview(String[] columns) {
        int limit = Math.min(columns.length, 12);
        List<String> parts = new ArrayList<>(limit + 1);
        for (int i = 0; i < limit; i++) {
            String value = columns[i];
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            parts.add((i + 1) + "='" + value + "'");
        }
        if (columns.length > limit) {
            parts.add("...");
        }
        return String.join(", ", parts);
    }

    private String[] readHeader() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                trimmed = trimmed.substring(1).trim();
            }
            if (trimmed.isBlank()) {
                continue;
            }
            return trimmed.split("\\s+");
        }
        throw new IllegalArgumentException(
                "Source " + spec.name() + " uses string column selectors but has no header line: " + spec.path());
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    private record ParsedLine(
            VariantKey key,
            FrequencyObservation bestObservation,
            List<FrequencyObservation> observations,
            long eligibleObservationCount
    ) {
    }

    private static final class MutableAggregate {
        private FrequencyObservation best;
        private final List<FrequencyObservation> observations = new ArrayList<>();
        private long eligibleObservationCount;
        private long rawLineCount;

        private SourceAggregate toAggregate(VariantKey key) {
            return new SourceAggregate(key, best, List.copyOf(observations), eligibleObservationCount, rawLineCount);
        }
    }
}
