"""Run the JDBC probe against disposable PG17 and the parent's real native KMS fixture.

Invoked inside the existing dadp-pg17-kms-test image by run-jdbc-native-fixture.sh.
Only the Hub authority/metadata source is a fixture; SQL and cryptography are real.
"""
import os
import pathlib
import subprocess
import sys
import time

sys.path.insert(0, "/source/dadp-db-udf/v8/common/tests")
from kms_fixture import KMSFixture

work = pathlib.Path(sys.argv[1])
module = sys.argv[2]
pg = "/usr/lib/postgresql/17/bin/"
data = work / "pgdata"

with KMSFixture(work, real=True, worker_binary="/worker") as fixture:
    # Policy remains in Worker metadata, but is removed from native KMS use grants.
    fixture.modes.pop("DENYA234")
    time.sleep(2)
    command = fixture.command
    command(pg + "initdb", "-D", str(data), "-U", "fixture", "--no-locale", "-E", "UTF8", "-A", "trust")
    started = False
    try:
        command(pg + "pg_ctl", "-D", str(data), "-l", str(work / "postgres.log"),
                "-o", "-h 127.0.0.1 -p 55486 -k /tmp", "-w", "start")
        started = True
        sql = ("CREATE SCHEMA dadp; "
               f"CREATE FUNCTION dadp.dadp_encrypt(text,text) RETURNS text AS '{module}', 'dadp_encrypt' LANGUAGE C STRICT VOLATILE PARALLEL UNSAFE; "
               f"CREATE FUNCTION dadp.dadp_decrypt(text) RETURNS text AS '{module}', 'dadp_decrypt' LANGUAGE C STRICT VOLATILE PARALLEL UNSAFE; "
               "REVOKE ALL ON FUNCTION dadp.dadp_encrypt(text,text),dadp.dadp_decrypt(text) FROM PUBLIC; "
               "CREATE ROLE jdbc_probe LOGIN NOSUPERUSER; "
               "GRANT CREATE ON DATABASE postgres TO jdbc_probe; "
               "GRANT USAGE ON SCHEMA dadp TO jdbc_probe; "
               "GRANT EXECUTE ON FUNCTION dadp.dadp_encrypt(text,text),dadp.dadp_decrypt(text) TO jdbc_probe; "
               f"ALTER DATABASE postgres SET dadp_v8.config_file='{fixture.config_file}';")
        command(pg + "psql", "-h", "127.0.0.1", "-p", "55486", "-U", "fixture", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", sql)
        config = work / "jdbc.properties"
        config.write_text("user=jdbc_probe\nsslmode=disable\nconnectTimeout=10\nsocketTimeout=30\n", encoding="ascii")
        config.chmod(0o600)
        result = subprocess.run(["bash", "/source/dadp-client-libraries/scripts/run-jdbc-process-probe.sh"],
                                env=dict(os.environ, JDBC_URL="jdbc:postgresql://127.0.0.1:55486/postgres",
                                         JDBC_CONFIG=str(config), PG_JDBC_JAR="/pgjdbc.jar",
                                         JDBC_POLICY_CODE="ABCD2345", JDBC_PARTIAL_POLICY_CODE="PREFIX01",
                                         JDBC_DENIED_POLICY_CODE="DENYA234"),
                                capture_output=True, text=True, timeout=120)
        print(result.stdout, end="", flush=True)
        if result.returncode or "JDBC_PLUGIN_PROBE_PASS" not in result.stdout:
            print(result.stderr, file=sys.stderr)
            raise RuntimeError("Actual JDBC/native KMS integration failed")
        print("PASS: actual JDBC Agent -> PG17 C plugin -> native KMS, non-superuser DB role")
    finally:
        if started:
            subprocess.run([pg + "pg_ctl", "-D", str(data), "-m", "immediate", "-w", "stop"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
