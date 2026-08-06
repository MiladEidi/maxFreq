package org.varevident.maxfreq;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BED region filter. BED input is 0-based, half-open; stored intervals are 1-based inclusive.
 *
 * @author Milad EIDI
 */
public final class RegionFilter {
    private final Map<String, List<Interval>> intervalsByChromosome;

    private RegionFilter(Map<String, List<Interval>> intervalsByChromosome) {
        this.intervalsByChromosome = intervalsByChromosome;
    }

    public static RegionFilter parse(Path path) throws IOException {
        Map<String, List<Interval>> intervals = new HashMap<>();
        try (BufferedReader reader = TextIO.reader(path)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("track") || trimmed.startsWith("browser")) {
                    continue;
                }
                String[] fields = trimmed.split("\\s+");
                if (fields.length < 3) {
                    throw new IllegalArgumentException("BED line " + lineNumber + " has fewer than 3 columns: " + path);
                }
                String chromosome = VariantKey.normalizeChromosome(fields[0]);
                long bedStart = parseLong(fields[1], path, lineNumber, "start");
                long bedEnd = parseLong(fields[2], path, lineNumber, "end");
                if (bedStart < 0 || bedEnd <= bedStart) {
                    throw new IllegalArgumentException(
                            "Invalid BED interval at " + path + ":" + lineNumber + " (" + bedStart + ", " + bedEnd + ")");
                }
                intervals.computeIfAbsent(chromosome, ignored -> new ArrayList<>())
                        .add(new Interval(bedStart + 1, bedEnd));
            }
        }
        if (intervals.isEmpty()) {
            throw new IllegalArgumentException("BED file contains no regions: " + path);
        }
        intervals.values().forEach(RegionFilter::sortAndMerge);
        return new RegionFilter(Map.copyOf(intervals));
    }

    public boolean overlaps(VariantKey key) {
        List<Interval> intervals = intervalsByChromosome.get(key.chromosome());
        if (intervals == null) {
            return false;
        }

        int low = 0;
        int high = intervals.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Interval interval = intervals.get(mid);
            if (interval.end < key.start()) {
                low = mid + 1;
            } else if (interval.start > key.end()) {
                high = mid - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    private static void sortAndMerge(List<Interval> intervals) {
        intervals.sort(Comparator.comparingLong(Interval::start).thenComparingLong(Interval::end));
        int write = 0;
        for (Interval interval : intervals) {
            if (write == 0) {
                intervals.set(write++, interval);
                continue;
            }
            Interval previous = intervals.get(write - 1);
            if (interval.start <= previous.end + 1) {
                intervals.set(write - 1, new Interval(previous.start, Math.max(previous.end, interval.end)));
            } else {
                intervals.set(write++, interval);
            }
        }
        intervals.subList(write, intervals.size()).clear();
    }

    private static long parseLong(String value, Path path, int lineNumber, String fieldName) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Invalid BED " + fieldName + " at " + path + ":" + lineNumber + ": " + value, error);
        }
    }

    private record Interval(long start, long end) {
    }
}
