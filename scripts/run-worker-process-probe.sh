#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "--help" ]]; then
  printf "%s\n" \
    "Required: WORKER_URL (HTTPS origin), WORKER_TLS_CONFIG (absolute private properties path)," \
    "WORKER_POLICY_CODE (existing allowed policy), WORKER_DENIED_POLICY_CODE (existing unauthorized policy)." \
    "Optional: JAVA_HOME, WRAPPER_JAR, WORKER_PROBE_JAR (absolute artifact paths)." \
    "Build fat jar: mvn -Pjava8 -Dmaven.javadoc.skip=true clean install" \
    "Build probe against fat jar: scripts/build-worker-process-probe.sh (JDK 17 required only for build)" \
    "Success: WORKER_PROBE_PASS and exit 0. Worker/certificates must be provisioned externally."
  exit 0
fi
if [[ $# -ne 0 ]]; then
  printf "%s\n" "Use --help or environment configuration." >&2
  exit 2
fi
: "${WORKER_URL:?WORKER_URL is required}"
: "${WORKER_TLS_CONFIG:?WORKER_TLS_CONFIG is required}"
: "${WORKER_POLICY_CODE:?WORKER_POLICY_CODE is required}"
: "${WORKER_DENIED_POLICY_CODE:?WORKER_DENIED_POLICY_CODE is required}"
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
wrapper="${WRAPPER_JAR:-$root/dadp-jdbc-wrapper/target/dadp-jdbc-wrapper-8.0.0-SNAPSHOT-all.jar}"
probe="${WORKER_PROBE_JAR:-$root/dadp-jdbc-wrapper/target/dadp-jdbc-wrapper-8.0.0-SNAPSHOT-worker-probe.jar}"
for file in "$WORKER_TLS_CONFIG" "$wrapper" "$probe"; do
  if [[ "$file" != /* || ! -f "$file" || ! -r "$file" ]]; then
    printf "%s\n" "Required absolute readable file missing: $file" >&2
    exit 2
  fi
done
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$java" "-Ddadp.worker.tls.config=$WORKER_TLS_CONFIG" \
  -cp "$probe:$wrapper" com.dadp.jdbc.integration.WorkerProcessProbe \
  "$WORKER_URL" "$WORKER_POLICY_CODE" "$WORKER_DENIED_POLICY_CODE"
