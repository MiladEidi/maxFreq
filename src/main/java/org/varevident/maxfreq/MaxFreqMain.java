package org.varevident.maxfreq;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI entry point for building and validating integrated frequency databases.
 *
 * @author Milad EIDI
 */
public final class MaxFreqMain {
    private MaxFreqMain() {
    }

    public static void main(String[] args) {
        try {
            int exitCode = run(args);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (Exception error) {
            System.err.println("ERROR: " + error.getMessage());
            if (Boolean.getBoolean("maxfreq.debug")) {
                error.printStackTrace(System.err);
            }
            System.exit(2);
        }
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            printUsage();
            return 0;
        }
        return switch (args[0]) {
            case "build" -> build(args);
            case "validate" -> validate(args);
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        };
    }

    private static int build(String[] args) throws Exception {
        Arguments options = Arguments.parse(args, 1);
        Path config = options.requiredPath("config");
        Path output = options.requiredPath("output");
        Path subpopulations = options.optionalPath("subpopulations", Path.of(output.toString() + ".subpopulations.tsv.gz"));
        Path details = options.optionalPath("details", Path.of(output.toString() + ".details.tsv.gz"));
        Path regions = options.optionalPath("regions", null);
        double minAf = options.optionalDouble("min-af", 0.0);
        long progressEvery = options.optionalLong("progress-every", 1_000_000L);
        boolean skipBadRows = parseBoolean(options.optional("skip-bad-rows", "false"), "skip-bad-rows");
        long maxBadRows = options.optionalLong("max-bad-rows", 1000L);

        List<SourceSpec> specs = ConfigParser.parse(config);
        validateSourceFiles(specs);
        RegionFilter regionFilter = null;
        if (regions != null) {
            if (!Files.isRegularFile(regions)) {
                throw new IllegalArgumentException("Regions BED file does not exist: " + regions);
            }
            regionFilter = RegionFilter.parse(regions);
            System.err.println("Restricting output to BED regions: " + regions.toAbsolutePath());
        }
        if (skipBadRows) {
            System.err.printf("Skipping malformed source rows is enabled; max bad rows per source: %,d%n", maxBadRows);
        }
        BuildStatistics stats = new MaxFreqBuilder().build(
                specs, output, subpopulations, details, minAf, progressEvery, regionFilter, skipBadRows, maxBadRows);
        System.err.printf(
                "Done. Analysed variants: %,d; written: %,d; outside regions: %,d; without eligible AF: %,d; eligible observations: %,d; source rows: %,d; skipped bad rows: %,d%n",
                stats.uniqueVariantsSeen(), stats.variantsWritten(), stats.variantsOutsideRegions(),
                stats.variantsWithoutEligibleFrequency(), stats.eligibleObservations(), stats.sourceRows(), stats.skippedBadRows());
        return 0;
    }

    private static int validate(String[] args) throws Exception {
        Arguments options = Arguments.parse(args, 1);
        List<SourceSpec> specs = ConfigParser.parse(options.requiredPath("config"));
        validateSourceFiles(specs);
        for (SourceSpec spec : specs) {
            try (AnnovarSourceReader reader = new AnnovarSourceReader(spec)) {
                long count = 0;
                SourceAggregate aggregate;
                while ((aggregate = reader.next()) != null) {
                    count++;
                }
                System.out.printf("OK\t%s\t%,d unique allele keys\t%s%n", spec.name(), count, spec.path());
            }
        }
        return 0;
    }

    private static void validateSourceFiles(List<SourceSpec> specs) {
        for (SourceSpec spec : specs) {
            if (!Files.isRegularFile(spec.path())) {
                throw new IllegalArgumentException("Source file does not exist: " + spec.path());
            }
        }
    }

    private static boolean parseBoolean(String value, String optionName) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "yes", "1" -> true;
            case "false", "no", "0" -> false;
            default -> throw new IllegalArgumentException("Expected boolean for --" + optionName + ", got: " + value);
        };
    }

    private static void printUsage() {
        System.out.println("""
                Variant Frequency DB Integrator 1.0.0

                Build integrated exact-allele frequency databases from sorted ANNOVAR-style
                tab-delimited files. It writes a max-only ANNOVAR database plus a wide
                database with the configured population/subpopulation frequencies.

                Usage:
                  java -jar maxfreq-builder.jar validate --config sources.tsv

                  java -jar maxfreq-builder.jar build \\
                    --config sources.tsv \\
                    --output hg38_maxfreq.txt \\
                    [--subpopulations hg38_maxfreq.subpopulations.tsv.gz] \\
                    [--details hg38_maxfreq.details.tsv.gz] \\
                    [--regions targets.bed] \\
                    [--min-af 0] \\
                    [--progress-every 1000000] \\
                    [--skip-bad-rows true] \\
                    [--max-bad-rows 1000]

                Configuration columns are documented in examples/sources.example.tsv.
                All source files must use the same genome build and the same exact ANNOVAR
                allele representation, and must be sorted by chromosome/start/end/ref/alt.
                """);
    }
}
