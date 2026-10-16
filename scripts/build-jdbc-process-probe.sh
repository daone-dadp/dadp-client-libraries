#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
wrapper="${WRAPPER_JAR:-$root/dadp-jdbc-wrapper/target/dadp-jdbc-wrapper-8.0.0-SNAPSHOT-all.jar}"
probe="${JDBC_PROBE_JAR:-$root/dadp-jdbc-wrapper/target/dadp-jdbc-wrapper-8.0.0-SNAPSHOT-jdbc-probe.jar}"
[[ "$wrapper" == /* && "$probe" == /* && -r "$wrapper" ]] || exit 2
classes="$(mktemp -d)"
trap 'rm -rf -- "$classes"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 8 -encoding UTF-8 -cp "$wrapper" -d "$classes" \
  "$root/dadp-jdbc-wrapper/src/test/java/com/dadp/jdbc/integration/JdbcProcessProbe.java"
"${JAVA_HOME:+$JAVA_HOME/bin/}jar" --create --file "$probe" -C "$classes" .
printf 'Built JDBC process probe: %s\n' "$probe"
