#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
wrapper="${WRAPPER_JAR:-$root/dadp-jdbc-wrapper/target/dadp-jdbc-wrapper-8.0.0-SNAPSHOT-all.jar}"
probe="${WORKER_PROBE_JAR:-$root/dadp-jdbc-wrapper/target/dadp-jdbc-wrapper-8.0.0-SNAPSHOT-worker-probe.jar}"
if [[ "$wrapper" != /* || "$probe" != /* || ! -f "$wrapper" ]]; then
  printf "%s\n" "Build the fat Wrapper JAR first; WRAPPER_JAR and WORKER_PROBE_JAR must be absolute paths." >&2
  exit 2
fi
javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
jar="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
classes="$(mktemp -d)"
trap 'rm -rf -- "$classes"' EXIT
"$javac" --release 8 -encoding UTF-8 -cp "$wrapper" -d "$classes" \
  "$root/dadp-jdbc-wrapper/src/test/java/com/dadp/jdbc/integration/WorkerProcessProbe.java"
"$jar" --create --file "$probe" -C "$classes" .
printf "%s\n" "Built Worker process probe: $probe"
