#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
wrapper="${WRAPPER_JAR:-$root/dadp-jdbc-wrapper/target/dadp-jdbc-wrapper-8.0.0-SNAPSHOT-all.jar}"
probe="${HUB_JDBC_PROBE_JAR:-$root/dadp-jdbc-wrapper/target/dadp-hub-sync-jdbc-probe.jar}"
classes="$(mktemp -d)"
trap 'rm -rf -- "$classes"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 8 -encoding UTF-8 -cp "$wrapper" -d "$classes" \
  "$root/dadp-jdbc-wrapper/src/test/java/com/dadp/jdbc/integration/HubSyncJdbcProbe.java"
"${JAVA_HOME:+$JAVA_HOME/bin/}jar" --create --file "$probe" -C "$classes" .
printf 'Built real Hub JDBC probe: %s\n' "$probe"
