#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/scripts/build.sh"
cd "$ROOT/examples"
java -jar "$ROOT/build/maxfreq-builder.jar" build \
  --config demo-sources.tsv \
  --output demo.maxfreq.txt \
  --details demo.maxfreq.details.tsv.gz \
  --progress-every 0
cat demo.maxfreq.txt
