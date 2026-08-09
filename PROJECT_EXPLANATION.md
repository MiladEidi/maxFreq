# MaxFreq Builder Full Project Explanation

## Overview

MaxFreq Builder is a Java command-line tool for building a single ANNOVAR-compatible population-frequency database from multiple ANNOVAR-style source databases.

The main output is a six-column ANNOVAR generic filter database:

```text
Chr    Start    End    Ref    Alt    MaxAF
```

For each exact allele, the tool finds the maximum eligible allele frequency across the configured databases and writes only that maximum numeric value. A separate details file can also be written so the user can see which source and population supplied the winning value.

The tool is designed for very large genomic frequency databases. It uses streaming IO and a k-way merge, so it does not load all variants into memory.

## Purpose

Variant interpretation workflows often need a single maximum population frequency value. Public and regional frequency resources can contain overlapping variants, different ancestry columns, different naming conventions, and different file sizes. This project combines those resources into one allele-specific max-frequency track that can be used directly by ANNOVAR.

The database is useful for workflows such as:

- filtering rare variants
- comparing a variant against the highest observed population frequency
- avoiding the need to run many separate ANNOVAR population-frequency protocols
- keeping provenance in a separate details file while keeping the main filter database numeric

## Important Biological and Technical Assumptions

Every source file used together must represent the same genome assembly. For example, do not mix hg19 and hg38 sources in one run.

Every source must use the same variant representation:

```text
Chr    Start    End    Ref    Alt
```

The tool compares exact alleles, not positions alone. These are different variants:

```text
1    100    100    A    G
1    100    100    A    T
```

Indels must already be normalized consistently before they are included. If the same biological indel is represented differently across databases, the tool will treat those rows as different alleles.

Input files must be sorted by:

```text
chromosome, start, end, ref, alt
```

Chromosome sorting uses natural order:

```text
1..22, X, Y, MT, then other contigs
```

The source files can be plain text or gzip/BGZF-like files ending in:

```text
.gz
.bgz
.bgzip
```

## Project Structure

Important files and directories:

```text
pom.xml
README.md
PROJECT_EXPLANATION.md
scripts/build.sh
scripts/demo.sh
scripts/sort_annovar_db.py
examples/sources.example.tsv
examples/auto-variant-sources.tsv
src/main/java/org/varevident/maxfreq/
```

`pom.xml` defines the Maven build and creates a runnable jar with:

```text
org.varevident.maxfreq.MaxFreqMain
```

`scripts/build.sh` builds the jar without Maven by compiling Java sources and packaging them.

`scripts/sort_annovar_db.py` is the helper for sorting large source databases before building.

`examples/auto-variant-sources.tsv` is the real local configuration currently prepared for the user's ANNOVAR source database folder.

## Main Java Classes

`MaxFreqMain` is the CLI entry point. It supports:

```text
validate
build
```

`ConfigParser` reads the source configuration TSV and creates `SourceSpec` objects.

`SourceSpec` stores one source database definition: source name, path, variant columns, frequency fields, AN filtering settings, and tie priority.

`ColumnSelector` resolves a column either by one-based numeric index or by case-insensitive header substring matching.

`FrequencyField` stores one configured AF observation definition:

```text
AF_COLUMN:POPULATION[:AC_COLUMN[:AN_COLUMN[:SCALE]]]
```

`AnnovarSourceReader` streams one sorted source file, parses rows, resolves frequency observations, and collapses consecutive duplicate allele keys within that source.

`VariantKey` stores the exact allele key:

```text
Chr, Start, End, Ref, Alt
```

It normalizes chromosome names by removing a leading `chr`, uppercasing, and mapping `M` to `MT`.

`FrequencyObservation` stores one eligible AF value and defines how observations are compared.

`MaxFreqBuilder` performs the multi-source streaming merge and writes output files.

`RegionFilter` optionally restricts output to BED intervals.

`TextIO` handles buffered plain-text and gzip/BGZF-style IO.

## Configuration File Format

The configuration file is whitespace-separated TSV-like text with one source per row.

Required columns:

```text
source
path
frequency_fields
```

Variant columns:

```text
chr_col
start_col
end_col
ref_col
alt_col
```

Optional behavior columns:

```text
min_an
require_an
priority
```

Column numbers are one-based. `0` means unavailable for optional columns such as `end_col`, AC column, or AN column.

If string selectors are used, the source must have a header line. Header selectors are case-insensitive and normalized before matching. For example, `AF_nfe` can match a header column named `AF_nfe`.

If a selector matches multiple columns, the tool fails and asks for a more specific selector. This prevents silent use of the wrong AF column.

## Frequency Field Syntax

The frequency field syntax is:

```text
AF_COLUMN:POPULATION[:AC_COLUMN[:AN_COLUMN[:SCALE]]]
```

Examples:

```text
6
6:global
AF_popmax:popmax
ExAC_AFR:AFR
HRC_AF:global:HRC_AC:HRC_AN
regional_percent:regional:regional_AC:regional_AN:100
```

Meaning:

- `AF_COLUMN` is required.
- `POPULATION` is a label stored in the details output.
- `AC_COLUMN` is optional.
- `AN_COLUMN` is optional.
- `SCALE` is optional and defaults to `1.0`.

If a source stores percent values such as `2.5` for 2.5 percent, use:

```text
SCALE=100
```

Then the tool converts it to:

```text
0.025
```

Multiple frequency fields can be provided for one source by separating them with semicolons:

```text
ExAC_ALL:ALL;ExAC_AFR:AFR;ExAC_AMR:AMR;ExAC_EAS:EAS
```

Each configured field becomes a candidate frequency observation for the same variant row.

## Current Auto Source Configuration

The current `examples/auto-variant-sources.tsv` includes these sources:

```text
Abraom
1000GP
GME
ExAC
gnomad211_exome
gnomad211_genome
gnomad41_exome
gnomad41_genome
hrcr1
Iranome
Kaviar
esp6500siv2_all
```

The config is currently set for maximum sensitivity:

```text
min_an=0
require_an=false
```

This means reported AF values are kept even when AN is missing or unavailable. This avoids missing variants from databases that do not provide AN columns.

The config includes population-style frequency columns for databases that have them:

- GME regional columns
- ExAC ancestry columns
- gnomAD 2.1.1 `AF_popmax` and ancestry AF columns
- gnomAD 4.1 `AF_grpmax` and ancestry/population AF columns
- HRC global and non-1000G AF columns

The config intentionally does not include gnomAD raw, sex-specific, FAF, or subset-specific popmax columns such as:

```text
AF_raw
AF_male
AF_female
AF_XX
AF_XY
faf95
faf99
non_topmed_AF_popmax
non_neuro_AF_popmax
non_cancer_AF_popmax
controls_AF_popmax
```

Those columns can be useful for specific analyses, but including them would change the meaning of the output from a general maximum population-frequency database.

## How the Tool Selects Max Frequency

The tool selects the winning value by exact allele.

For each exact allele key:

```text
Chr    Start    End    Ref    Alt
```

it considers all eligible AF observations from all configured sources.

The process is:

1. Read the next allele from each sorted source file.
2. Merge rows that have the same exact allele key.
3. For each source row, read every configured frequency field.
4. Ignore missing AF values.
5. Convert AF values by scale if configured.
6. Ignore invalid AF values below `0`, above `1`, NaN, or infinity.
7. If AN filtering is active, apply `require_an` and `min_an`.
8. Select the largest AF value.
9. Write that value to the main ANNOVAR output.
10. Write provenance for that value to the details output.

Example:

```text
Database A, global AF = 0.001
Database A, AFR AF    = 0.020
Database A, NFE AF    = 0.004
Database B, global AF = 0.015
Database C, global AF = 0
```

The output value is:

```text
0.02
```

The details file records the winning source and population label.

## Missing and Invalid Values

The tool treats these values as missing:

```text
.
NA
N/A
NULL
-
blank
```

Missing AF values are skipped. They do not become zero.

Invalid numeric values are skipped if they are:

```text
AF < 0
AF > 1
NaN
Infinity
```

If the source file itself contains `0`, the tool treats that as a real zero-frequency observation.

The output formatter only writes `0` when the stored value is exactly `0.0`. Very small nonzero values are not rounded to zero. Values below `0.000001` are written with Java's standard double formatting, usually scientific notation.

## AN Filtering

`AN` means allele number.

The config has two AN-related settings:

```text
min_an
require_an
```

If `require_an=true`, observations without AN are skipped.

If an AN column is configured and the parsed AN value is lower than `min_an`, that observation is skipped.

If no AN column is configured and `require_an=false`, the observation is kept.

For the current auto config, every source has:

```text
min_an=0
require_an=false
```

This is the safest choice when the goal is not to miss variants from databases that lack AN columns. It means the output should be described as:

```text
maximum reported AF across configured population columns
```

not as:

```text
AN-filtered maximum AF
```

## Tie-Breaking

If multiple eligible observations have the same AF, the tool uses deterministic tie-breaking:

1. Prefer larger AN if available.
2. Prefer lower configured `priority`.
3. Prefer lexical source name order.
4. Prefer lexical population label order.

This makes repeated builds stable.

The `priority` column does not affect normal max selection. It only matters when AF values tie.

## Main Output File

The main output is intended to be used directly by ANNOVAR as a generic filter database:

```text
Chr    Start    End    Ref    Alt    MaxAF
```

Example:

```text
1    100    100    A    G    0.005
1    100    100    A    T    0.02
```

Only the numeric max AF is written because ANNOVAR score filtering works best with a simple numeric field.

## Details Output File

The details output stores provenance for the winning AF:

```text
Chr
Start
End
Ref
Alt
MaxAF
Source
Population
AC
AN
EligibleObservations
SourceCount
SourceRows
```

Important columns:

- `Source`: database that supplied the winning AF
- `Population`: configured population label for the winning field
- `AC`: allele count if configured, otherwise `.`
- `AN`: allele number if configured, otherwise `.`
- `EligibleObservations`: number of AF observations considered for this allele
- `SourceCount`: number of source aggregates seen for this allele
- `SourceRows`: number of raw source rows collapsed for this allele

The details file is useful for auditing surprising values.

## Sorting Requirement

The builder assumes each source is already sorted. This is what makes streaming merge possible.

If a source is not sorted, `validate` or `build` fails with an error that names the source path and line number.

Use:

```bash
java -jar maxfreq-builder.jar validate --config examples/auto-variant-sources.tsv
```

For very large files, use the helper script:

```powershell
python scripts\sort_annovar_db.py `
  --input input.txt `
  --output input.sorted.txt `
  --chr-col 1 `
  --start-col 2 `
  --end-col 3 `
  --ref-col 4 `
  --alt-col 5
```

For a file with no `End` column:

```powershell
python scripts\sort_annovar_db.py `
  --input input.txt `
  --output input.sorted.txt `
  --chr-col 1 `
  --start-col 2 `
  --end-col 0 `
  --ref-col 3 `
  --alt-col 4
```

## Region Filtering

The `--regions` option restricts output to a BED file.

BED coordinates are 0-based and half-open. ANNOVAR coordinates are 1-based and inclusive.

This BED interval:

```text
chr1    499    500
```

matches this ANNOVAR position:

```text
1    500    500
```

A variant is included if its `Start..End` interval overlaps any BED interval.

## Build Commands

Build with Maven:

```bash
mvn clean package
```

Run with Maven-built jar:

```bash
java -jar target/maxfreq-builder-1.0.0.jar validate --config examples/auto-variant-sources.tsv
```

Build without Maven:

```bash
./scripts/build.sh
```

Run with script-built jar:

```bash
java -jar build/maxfreq-builder.jar validate --config examples/auto-variant-sources.tsv
```

Build the max-frequency database:

```bash
java -jar build/maxfreq-builder.jar build \
  --config examples/auto-variant-sources.tsv \
  --output humandb/hg19_maxfreq.txt \
  --details humandb/hg19_maxfreq.details.tsv.gz \
  --min-af 0 \
  --progress-every 1000000
```

Use `--regions` when you want only target intervals:

```bash
java -jar build/maxfreq-builder.jar build \
  --config examples/auto-variant-sources.tsv \
  --output humandb/hg19_maxfreq.txt \
  --details humandb/hg19_maxfreq.details.tsv.gz \
  --regions targets.bed \
  --min-af 0 \
  --progress-every 1000000
```

## ANNOVAR Integration

After building the database, create the ANNOVAR index:

```bash
perl index_annovar.pl humandb/hg19_maxfreq.txt \
  -outfile humandb/hg19_maxfreq.txt
```

Then use it as a filter protocol:

```bash
table_annovar.pl patient.vcf humandb/ \
  -buildver hg19 \
  -protocol refGeneWithVer,maxfreq \
  -operation g,f \
  -vcfinput \
  -nastring .
```

The protocol name comes from the filename. For protocol `maxfreq`, the expected database filename is:

```text
hg19_maxfreq.txt
```

or for hg38:

```text
hg38_maxfreq.txt
```

## Practical Interpretation of Output Values

The output value means:

```text
largest eligible reported AF among the configured fields for this exact allele
```

It does not mean:

- the true worldwide allele frequency
- an average frequency
- a frequency recalculated from all source AC/AN values
- a position-level max independent of REF/ALT
- a quality-filtered AF unless the configured source values already represent quality-filtered data

If the output is `0`, then the winning eligible configured observation was exactly `0.0`.

If a variant has no valid eligible AF in any configured field, it is not written to the main output.

If `--min-af` is greater than zero, variants with max AF below that threshold are not written.

## Current Publishing Meaning

With the current auto config, the generated database should be described as:

```text
An exact-allele ANNOVAR max-frequency database containing the largest reported AF across configured global, popmax/groupmax, and population-specific frequency fields from the listed source databases.
```

Because `min_an=0` and `require_an=false`, the database prioritizes sensitivity and source coverage over AN-based quality filtering.

## Recommended Pre-Publication Checklist

Before publishing a generated database, record:

- tool version or git commit
- exact config file used
- exact command used
- genome assembly
- source database versions or download dates
- whether source files were sorted or normalized
- output file checksum
- details file checksum
- total variants written
- total eligible observations
- whether `--regions` was used
- whether `--min-af` was used

The details file should be kept with the main database whenever possible because it explains where each max AF came from.

## Limitations

The tool does not normalize variants. Normalization must happen before input files are used.

The tool does not lift over coordinates between genome assemblies.

The tool does not recalculate frequency from AC/AN values. It uses the AF fields configured by the user.

The tool does not know which columns are biologically appropriate unless they are listed in the config.

The tool does not combine multiple rows that represent the same variant differently.

The tool does not validate source database licensing or redistribution permissions.

## Debugging Tips

If many frequencies are `0`, inspect the details file to see the winning source and population.

If a source is skipped or fails, run:

```bash
java -Dmaxfreq.debug=true -jar build/maxfreq-builder.jar validate --config examples/auto-variant-sources.tsv
```

If a column selector is ambiguous, use an exact column name or a numeric column index.

If the output misses expected variants, check:

- genome assembly
- exact REF/ALT representation
- source sorting
- missing AF values
- `--min-af`
- `--regions`
- whether the needed population column is listed in `frequency_fields`

If AN filtering is enabled in the future, also check:

- whether AN columns are configured
- whether `require_an=true` is excluding sources without AN
- whether `min_an` is too high

## Summary

MaxFreq Builder is a streaming exact-allele merge tool. It reads configured ANNOVAR-style frequency databases, considers every configured AF field for each exact allele, filters invalid or missing values, optionally applies AN rules, selects the largest AF, and writes an ANNOVAR-compatible max-frequency database plus a provenance details file.

The correctness of the final database depends mainly on:

- using one genome assembly
- using consistent normalized allele representation
- sorting every source correctly
- configuring all intended population AF columns
- choosing AN filtering rules that match the intended sensitivity/quality tradeoff
