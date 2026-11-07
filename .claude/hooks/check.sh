#!/usr/bin/env bash
# Stop: unit tests + Android lint must pass before the turn ends.
cd "$(git rev-parse --show-toplevel)" || exit 0
[ -x ./gradlew ] || exit 0
out=$(./gradlew -q testDebugUnitTest lintDebug 2>&1) || { echo "$out" | tail -40 >&2; exit 2; }
exit 0
