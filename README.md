# MaxFreq Builder

`MaxFreq Builder` creates an allele-specific maximum population-frequency database from multiple updated ANNOVAR filter databases.

It uses a streaming k-way merge, so it does **not** load all variants into RAM. Memory usage is approximately proportional to the number of source files.

## Important assumptions

- Every source uses the same genome assembly.
- Every source is represented in exact ANNOVAR form: `Chr Start End Ref Alt ...`.
- Indels have already been normalized consistently.
- Every input is sorted by chromosome, start, end, REF, and ALT using natural chromosome order: `1..22, X, Y, MT`, then other contigs.
- Aggregation is by exact allele, never by position alone.

For VCF sources, normalize and split them before converting to ANNOVAR:

```bash
bcftools norm -f GRCh38.fa -m -any source.vcf.gz -Oz -o source.norm.vcf.gz
convert2annovar.pl -format vcf4 source.norm.vcf.gz > source.avinput
```

## Build

### Maven

```bash
mvn clean package
```

Output:

```text
target/maxfreq-builder-1.0.0.jar
```

### Without Maven

```bash
./scripts/build.sh
```

Output:

```text
build/maxfreq-builder.jar
```

## Configuration

The configuration is TSV with one source per line. Column numbers are one-based.

Required config columns:

| Column | Meaning |
|---|---|
| `source` | Stable source name |
| `path` | Plain, `.gz`, `.bgz`, or `.bgzip` file |
| `frequency_fields` | One or more AF definitions separated by semicolons |

Variant-location config columns are optional when the source file has a header:

| Column | Default header selector |
|---|---|
| `chr_col` | Chromosome column |
| `start_col` | Start column |
| `end_col` | End column |
| `ref_col` | REF column |
| `alt_col` | ALT column |

If omitted or blank, these are detected from common header names such as `Chr`,
`Chromosome`, `Start`, `Position`, `End`, `Ref`, and `Alt`. You can still provide
one-based numbers, or provide your own partial header strings.

Some source databases have `Start` and `End`; others only have one position
column such as `Position` or `POS`. If no `End`/`Stop` column is found, the tool
derives `End` from `Start` and `Ref`:

```text
End = Start + length(Ref) - 1
```

For ordinary SNVs this makes `End` equal to `Start`.

Optional columns:

| Column | Default | Meaning |
|---|---:|---|
| `min_an` | `0` | Minimum AN for observations from this source |
| `require_an` | `false` | Exclude observations without AN |
| `priority` | `100` | Lower value wins exact ties |

A frequency field is:

```text
AF_COLUMN:POPULATION[:AC_COLUMN[:AN_COLUMN[:SCALE]]]
```

Column selectors such as `chr_col`, `start_col`, `AF_COLUMN`, `AC_COLUMN`, and
`AN_COLUMN` may be either one-based column numbers or case-insensitive
substrings matched against the source file header. String selectors do not need
to match the full header. If a selector matches more than one column,
validation/build fails and asks for a more specific selector.

Examples:

```text
gnomAD_AF
6
6:global
7:NFE:0:0
8:MID:9:10
gnomAD_AF:global
AF_nfe:NFE:AC_nfe:AN_nfe
11:regional_percent:12:13:100
```

- A single selector such as `gnomAD_AF` or `6` is accepted as shorthand for
  `gnomAD_AF:global` or `6:global`.
- `0` or an empty AC/AN column means unavailable.
- `SCALE=100` converts a percentage such as `2.5` into AF `0.025`.
- Multiple population columns in one source are permitted. The largest eligible AF is retained.
- Header-string selectors and omitted variant columns require the first
  non-comment, non-blank source line to be the tab-delimited header. Numeric
  selectors keep the old behavior and do not require a source header.

See `examples/sources.example.tsv`.

## Validate source ordering and parsing

```bash
java -jar maxfreq-builder.jar validate --config sources.tsv
```

The command fails on the first unsorted or malformed record.

## Sort a Source Database

Large source files must be sorted before the streaming builder can merge them.
Use the helper script instead of PowerShell `Sort-Object` for multi-GB files:

```powershell
python scripts\sort_annovar_db.py `
  --input "E:\NGSneeds\Annovar_Databases\hg19_databases\hg19_gnomad211_exome.txt" `
  --output "E:\NGSneeds\Annovar_Databases\hg19_databases\sorted\hg19_gnomad211_exome.sorted.txt" `
  --chr-col 1 `
  --start-col 2 `
  --end-col 3 `
  --ref-col 4 `
  --alt-col 5
```

For files with only one position column, use `--end-col 0`; the script derives
`End = Start + length(Ref) - 1`.

After sorting, update the `path` for that source in `sources.tsv` to the sorted
file and run `validate` again.

## Build the database

```bash
java -jar maxfreq-builder.jar build \
  --config sources.tsv \
  --output humandb/hg38_maxfreq.txt \
  --details humandb/hg38_maxfreq.details.tsv.gz \
  --regions targets.bed \
  --min-af 0 \
  --progress-every 1000000
```

`--regions` is optional. When provided, it must point to a BED file. BED files
use 0-based, half-open coordinates, so this BED line:

```text
chr1  499  500
```

matches ANNOVAR coordinate `1  500  500`. A variant is included if its
`Start..End` span overlaps any BED interval.

Outputs:

1. `hg38_maxfreq.txt`: six-column ANNOVAR generic filter database:

```text
Chr  Start  End  Ref  Alt  MaxAF
```

2. `hg38_maxfreq.details.tsv.gz`: provenance table containing source, population, AC, AN, number of eligible observations, source count, and duplicate source-row count.

## Index for ANNOVAR

Create the ANNOVAR index using the `index_annovar.pl` script distributed/referenced by ANNOVAR:

```bash
perl index_annovar.pl humandb/hg38_maxfreq.txt \
  -outfile humandb/hg38_maxfreq.txt
```

Then annotate with it as a filter database:

```bash
table_annovar.pl patient.vcf humandb/ \
  -buildver hg38 \
  -protocol refGeneWithVer,maxfreq \
  -operation g,f \
  -vcfinput \
  -nastring .
```

The file name must be `hg38_maxfreq.txt` for protocol name `maxfreq`.

## How the winning value is selected

For each exact allele:

1. Invalid or missing AF values are ignored.
2. AF values are converted by their configured scale.
3. Observations failing `min_an` or `require_an` are ignored.
4. The maximum AF is selected.
5. Ties prefer larger AN, then lower source priority, then stable lexical order.

The main ANNOVAR file contains only the numeric maximum so `-score_threshold` remains usable. Provenance is stored separately in the details file.

## Demo

```bash
./scripts/demo.sh
```

Expected ANNOVAR output:

```text
1\t100\t100\tA\tG\t0.005
1\t100\t100\tA\tT\t0.02
1\t150\t150\tG\tC\t0.0002
1\t200\t200\tC\tT\t0.003
X\t50\t50\tG\tA\t0.1
```

The `sourceB` X-chromosome value `0.2` is excluded because its AN is only 100, below the configured `min_an=1000`.
