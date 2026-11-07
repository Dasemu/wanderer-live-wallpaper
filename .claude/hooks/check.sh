#!/usr/bin/env bash
# Stop: unit tests + Android lint must pass before the turn ends.
cd "$(git rev-parse --show-toplevel)" || exit 0
[ -x ./gradlew ] || exit 0
# Gradle needs a full JDK 17+; prefer a local JDK 21 when the default java is a JRE.
[ -z "$JAVA_HOME" ] && [ -x "$HOME/Android/jdk21/bin/javac" ] && export JAVA_HOME="$HOME/Android/jdk21"
out=$(./gradlew -q testDebugUnitTest lintDebug 2>&1) || { echo "$out" | tail -40 >&2; exit 2; }
exit 0
