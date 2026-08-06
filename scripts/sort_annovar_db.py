#!/usr/bin/env python3
"""Disk-based sorter for ANNOVAR-like tabular databases.

Sort key is chromosome, start, end, ref, alt. BED/VCF-like files with only a
position column can pass --end-col 0; end is then derived from ref length.
Column numbers are one-based.
"""

from __future__ import annotations

import argparse
import gzip
import heapq
import os
import tempfile
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Sort an ANNOVAR-like database by allele key.")
    parser.add_argument("--input", required=True, help="Input .txt/.tsv/.gz file")
    parser.add_argument("--output", required=True, help="Sorted output .txt/.tsv/.gz file")
    parser.add_argument("--chr-col", required=True, type=int, help="Chromosome column, one-based")
    parser.add_argument("--start-col", required=True, type=int, help="Start/position column, one-based")
    parser.add_argument("--end-col", required=True, type=int, help="End column, one-based; use 0 to derive from ref")
    parser.add_argument("--ref-col", required=True, type=int, help="REF column, one-based")
    parser.add_argument("--alt-col", required=True, type=int, help="ALT column, one-based")
    parser.add_argument("--chunk-lines", type=int, default=1_000_000, help="Rows per sort chunk")
    parser.add_argument("--temp-dir", default=None, help="Temporary directory for sorted chunks")
    parser.add_argument(
        "--header",
        choices=("auto", "yes", "no"),
        default="auto",
        help="Whether the first nonblank line is a header",
    )
    return parser.parse_args()


def open_text(path: Path, mode: str):
    if path.name.lower().endswith((".gz", ".bgz", ".bgzip")):
        return gzip.open(path, mode + "t", encoding="utf-8", newline="")
    return path.open(mode, encoding="utf-8", newline="")


def normalize_chromosome(chromosome: str) -> str:
    value = chromosome.strip()
    if value.lower().startswith("chr"):
        value = value[3:]
    value = value.upper()
    if value == "M":
        return "MT"
    return value


def chromosome_rank(chromosome: str) -> tuple[int, int | str]:
    value = normalize_chromosome(chromosome)
    if value.isdigit():
        parsed = int(value)
        if 1 <= parsed <= 22:
            return 0, parsed
    if value == "X":
        return 0, 23
    if value == "Y":
        return 0, 24
    if value == "MT":
        return 0, 25
    return 1, value


def reference_length(ref: str) -> int:
    value = ref.strip()
    if not value or value in {".", "-", "NA", "N/A", "NULL"} or value.startswith("<"):
        return 1
    return len(value)


def field(fields: list[str], one_based: int, name: str) -> str:
    index = one_based - 1
    if index < 0 or index >= len(fields) or not fields[index].strip():
        raise ValueError(f"missing {name} column {one_based}")
    return fields[index].strip()


def sort_key(line: str, args: argparse.Namespace, line_number: int):
    fields = line.rstrip("\r\n").split("\t")
    chrom = field(fields, args.chr_col, "chromosome")
    start = int(field(fields, args.start_col, "start"))
    ref = field(fields, args.ref_col, "ref").upper()
    alt = field(fields, args.alt_col, "alt").upper()
    if args.end_col == 0:
        end = start + reference_length(ref) - 1
    else:
        end = int(field(fields, args.end_col, "end"))
    return chromosome_rank(chrom), start, end, ref, alt, line_number


def looks_like_header(line: str, args: argparse.Namespace) -> bool:
    if args.header == "yes":
        return True
    if args.header == "no":
        return False
    fields = line.lstrip("#").rstrip("\r\n").split("\t")
    try:
        int(field(fields, args.start_col, "start"))
        if args.end_col > 0:
            int(field(fields, args.end_col, "end"))
        return False
    except ValueError:
        return True


def write_chunk(rows: list[tuple], temp_root: Path, chunk_index: int) -> Path:
    rows.sort(key=lambda row: row[0])
    path = temp_root / f"chunk-{chunk_index:06d}.tsv"
    with path.open("w", encoding="utf-8", newline="") as writer:
        for _, line in rows:
            writer.write(line)
    return path


def main() -> int:
    args = parse_args()
    input_path = Path(args.input)
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(dir=args.temp_dir) as temp_name:
        temp_root = Path(temp_name)
        chunk_paths: list[Path] = []
        rows: list[tuple] = []
        header: str | None = None
        line_number = 0
        chunk_index = 0

        with open_text(input_path, "r") as reader:
            for line in reader:
                line_number += 1
                if not line.strip():
                    continue
                if header is None and looks_like_header(line, args):
                    header = line
                    continue
                if line.startswith("#"):
                    continue
                try:
                    rows.append((sort_key(line, args, line_number), line))
                except Exception as error:
                    raise ValueError(f"Cannot parse {input_path}:{line_number}: {error}") from error
                if len(rows) >= args.chunk_lines:
                    chunk_paths.append(write_chunk(rows, temp_root, chunk_index))
                    rows = []
                    chunk_index += 1

        if rows:
            chunk_paths.append(write_chunk(rows, temp_root, chunk_index))

        handles = [path.open("r", encoding="utf-8", newline="") for path in chunk_paths]
        try:
            keyed_lines = []
            for handle_index, handle in enumerate(handles):
                line = handle.readline()
                if line:
                    keyed_lines.append((sort_key(line, args, -1), handle_index, line))

            with open_text(output_path, "w") as writer:
                if header is not None:
                    writer.write(header)
                heapq.heapify(keyed_lines)
                while keyed_lines:
                    _, handle_index, line = heapq.heappop(keyed_lines)
                    writer.write(line)
                    next_line = handles[handle_index].readline()
                    if next_line:
                        heapq.heappush(keyed_lines, (sort_key(next_line, args, -1), handle_index, next_line))
        finally:
            for handle in handles:
                handle.close()

    print(f"Sorted {input_path} -> {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
