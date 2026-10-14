#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchain/jdk/Contents/Home}"
CLASSES="$ROOT/.toolchain/recurrence-test-classes"
mkdir -p "$CLASSES"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$CLASSES" "$ROOT/android/app/src/main/java/dev/remember/app/Recurrence.java" "$ROOT/android/app/src/test/java/dev/remember/app/RecurrenceTest.java"
"$JAVA_HOME/bin/java" -ea -cp "$CLASSES" dev.remember.app.RecurrenceTest
