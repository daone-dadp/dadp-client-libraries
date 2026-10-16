#!/usr/bin/env bash
set -euo pipefail
# Both native targets are mandatory. No skipped target may produce this success marker.
: "${CRYPTO_SOURCE:?required}" "${CRYPTO_LIB:?required}" "${MYSQL_INCLUDE_DIR:?required}"
: "${PG_JDBC_JAR:?required}" "${MYSQL_JDBC_JAR:?required}"
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
bash "$here/build-jdbc-process-probe.sh"
CRYPTO_INCLUDE="$CRYPTO_SOURCE/include" bash "$here/run-jdbc-native-fixture.sh"
JDBC_DRIVER_JAR="$MYSQL_JDBC_JAR" bash "$here/run-mysql-jdbc-native-fixture.sh"
printf '%s\n' 'JDBC_TARGET_GATES_PASS pg17=true mysql84=true'
