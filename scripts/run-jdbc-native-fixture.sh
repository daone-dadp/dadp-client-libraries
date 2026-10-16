#!/usr/bin/env bash
set -euo pipefail
# Caller supplies the existing PG17 test image, own-module build, and PostgreSQL JDBC jar.
: "${CRYPTO_INCLUDE:?required}" "${CRYPTO_LIB:?required}" "${PG_JDBC_JAR:?required}"
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "${1:-}" == "--inside" ]]; then
  umask 077
  work="$(mktemp -d /work/jdbc.XXXXXXXX)"
  mkdir "$work/common" "$work/postgresql" "$work/fixture"
  tar -C /source/dadp-db-udf/v8/common --exclude=build -cf - . | tar -C "$work/common" -xf -
  tar -C /source/dadp-db-udf/v8/postgresql --exclude=build -cf - . | tar -C "$work/postgresql" -xf -
  export LD_LIBRARY_PATH="$work/common/build/kms:$work/common/build/kms-runtime:/native"
  make -C "$work/common" -f Makefile.kms CRYPTO_INCLUDE=/include CRYPTO_LIB=/native
  make -C "$work/common" CRYPTO_INCLUDE=/include CRYPTO_LIB=/native
  make -C "$work/postgresql" PG_MAJOR=17 PG_CONFIG=/usr/lib/postgresql/17/bin/pg_config
  cc -std=c11 -Wall -Wextra -Werror -I/include "$work/common/tests/fixture_wrap.c" -L/native -ldadpcrypto -lcrypto -o "$work/fixture/wrap"
  exec python3 /source/dadp-client-libraries/scripts/jdbc-native-fixture.py "$work/fixture" "$work/postgresql/build/pg17/dadp_pg_v8_pg17.so"
fi
[[ $# == 0 ]] || exit 2
binary_dir="$(mktemp -d /tmp/dadp-jdbc-kms.XXXXXXXX)"
trap 'rm -rf -- "$binary_dir"' EXIT
chmod 0755 "$binary_dir"
for target in kms engine; do
  CGO_ENABLED=1 CGO_CFLAGS="-I$CRYPTO_INCLUDE" CGO_LDFLAGS="-L$CRYPTO_LIB" \
    go -C "$root/dadp-engine-go" build -tags dadpcrypto -o "$binary_dir/$target" "./cmd/$target"
done
docker run --rm --user postgres --network none \
  -v "$root:/source:ro" -v "$(realpath "$CRYPTO_INCLUDE"):/include:ro" \
  -v "$(realpath "$CRYPTO_LIB"):/native:ro" -v "$(realpath "$PG_JDBC_JAR"):/pgjdbc.jar:ro" \
  -v "$binary_dir/kms:/kms:ro" -v "$binary_dir/engine:/worker:ro" \
  -e DADP_KMS_BINARY=/kms -e CRYPTO_INCLUDE=/include -e CRYPTO_LIB=/native -e PG_JDBC_JAR=/pgjdbc.jar \
  -e JDBC_AGENT_MODE="${JDBC_AGENT_MODE:-plugin}" \
  --tmpfs /work:rw,exec,mode=1777 --workdir /work "${PG_TEST_IMAGE:-dadp-pg17-kms-test:local}" \
  bash /source/dadp-client-libraries/scripts/run-jdbc-native-fixture.sh --inside
