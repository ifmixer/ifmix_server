#!/usr/bin/env bash
# Phase 6 verification: assert AnonymousCleanupDecision.shouldDelete against compiled main classes.
# The project test source set has pre-existing rot (WechatVerifierTest references a removed symbol),
# so this standalone script verifies the pure decision function without the test set.
#
# Prereq: ./gradlew :core-api:compileKotlin  (produces core-api/build/classes/kotlin/main)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/core-api/build/classes/kotlin/main"
OUT="$(mktemp -d)"
JAVA_HOME_25="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.4-amzn}"
KOTLINC="${KOTLINC:-$HOME/.sdkman/candidates/kotlin/current/bin/kotlinc}"
STDLIB="$(find "$HOME/.gradle" -name 'kotlin-stdlib-2*.jar' 2>/dev/null | grep -v sources | sort | tail -1)"

[ -d "$MAIN" ] || { echo "main classes missing; run ./gradlew :core-api:compileKotlin first" >&2; exit 2; }

"$KOTLINC" -cp "$MAIN" "$ROOT/scripts/verify_anon_cleanup.kts.kt" -include-runtime -d "$OUT/verify.jar" 2>&1 | grep -vi warning || true
"$JAVA_HOME_25/bin/java" -cp "$OUT/verify.jar:$MAIN:$STDLIB" Verify_anon_cleanup_ktsKt
