#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/build"
CLASSES="$BUILD/classes"
rm -rf "$BUILD"
mkdir -p "$CLASSES"
find "$ROOT/src/main/java" -name '*.java' -print0 | xargs -0 javac --release 17 -encoding UTF-8 -d "$CLASSES"
printf 'Main-Class: org.varevident.maxfreq.MaxFreqMain\n' > "$BUILD/MANIFEST.MF"
jar --create --file "$BUILD/maxfreq-builder.jar" --manifest "$BUILD/MANIFEST.MF" -C "$CLASSES" .
echo "Built $BUILD/maxfreq-builder.jar"
