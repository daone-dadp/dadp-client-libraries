#!/usr/bin/env bash
set -euo pipefail
: "${CRYPTO_SOURCE:?required}" "${MYSQL_INCLUDE_DIR:?required}" "${JDBC_DRIVER_JAR:?required}"
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "${1:-}" == "--inside" ]]; then
  umask 077
  work="$(mktemp -d /work/mysqljdbc.XXXXXXXX)"
  mkdir "$work/common" "$work/mysql"
  tar -C /source/dadp-db-udf/v8/common --exclude=build -cf - . | tar -C "$work/common" -xf -
  tar -C /source/dadp-db-udf/v8/mysql --exclude='build*' -cf - . | tar -C "$work/mysql" -xf -
  cmake -S /crypto -B "$work/native" -DBUILD_TESTING=OFF -DCMAKE_BUILD_TYPE=Release
  cmake --build "$work/native" -j2
  export LD_LIBRARY_PATH="$work/common/build/kms:$work/common/build/kms-runtime:$work/native"
  make -C "$work/common" -f Makefile.kms CRYPTO_INCLUDE=/crypto/include CRYPTO_LIB="$work/native"
  make -C "$work/common" CRYPTO_INCLUDE=/crypto/include CRYPTO_LIB="$work/native"
  make -C "$work/mysql" EVAL_ONLY=1 MYSQL_INCLUDE_DIR=/headers RUNTIME_LIB="$work/common/build/kms-runtime" all test
  cp /usr/lib64/mysql/plugin/component_reference_cache.so "$work/mysql/build/"
  cc -std=c11 -Wall -Wextra -Werror -I/crypto/include "$work/common/tests/fixture_wrap.c" -L"$work/native" -ldadpcrypto -lcrypto -o "$work/wrap"
  exec python3 /source/dadp-client-libraries/scripts/mysql-jdbc-native-fixture.py "$work" "$work/mysql/build"
fi
[[ $# == 0 ]] || exit 2
: "${CRYPTO_LIB:?host native build required for Go link}"
binary_dir="$(mktemp -d /tmp/dadp-mysql-jdbc.XXXXXXXX)"
trap 'rm -rf -- "$binary_dir"' EXIT
chmod 0755 "$binary_dir"
for target in kms engine; do
  CGO_ENABLED=1 CGO_CFLAGS="-I$CRYPTO_SOURCE/include" CGO_LDFLAGS="-L$CRYPTO_LIB" \
    go -C "$root/dadp-engine-go" build -tags dadpcrypto -o "$binary_dir/$target" "./cmd/$target"
done
docker build -t dadp-mysql84-jdbc-test:local -f "$root/dadp-client-libraries/scripts/Dockerfile.mysql-jdbc" "$root/dadp-client-libraries/scripts"
docker run --rm --user mysql --network none --tmpfs /work:rw,exec,mode=1777 \
  -v "$root:/source:ro" -v "$(realpath "$CRYPTO_SOURCE"):/crypto:ro" \
  -v "$(realpath "$MYSQL_INCLUDE_DIR"):/headers:ro" -v "$(realpath "$JDBC_DRIVER_JAR"):/jdbc.jar:ro" \
  -v "$binary_dir/kms:/kms:ro" -v "$binary_dir/engine:/worker:ro" \
  -e DADP_KMS_BINARY=/kms -e CRYPTO_SOURCE=/crypto -e MYSQL_INCLUDE_DIR=/headers -e JDBC_DRIVER_JAR=/jdbc.jar \
  dadp-mysql84-jdbc-test:local bash /source/dadp-client-libraries/scripts/run-mysql-jdbc-native-fixture.sh --inside
