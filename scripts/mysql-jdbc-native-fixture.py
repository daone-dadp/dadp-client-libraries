"""Real JDBC Agent -> MySQL84 -> own native module/KMS; Hub authority is a fixture."""
import os
import pathlib
import subprocess
import sys
import time

work = pathlib.Path(sys.argv[1])
adapter = pathlib.Path(sys.argv[2])
sys.path.insert(0, "/source/dadp-db-udf/v8/common/tests")
from kms_fixture import KMSFixture

with KMSFixture(work, runtime_id="mysql84", real=True, worker_binary="/worker") as fixture:
    fixture.modes.pop("DENYA234")
    time.sleep(2)
    data, socket, log = work / "mysql-data", work / "mysql.sock", work / "mysql.log"
    initialized = subprocess.run(["mysqld", "--no-defaults", "--initialize-insecure", f"--datadir={data}", f"--log-error={log}"])
    if initialized.returncode:
        raise RuntimeError(log.read_text() if log.exists() else "MySQL initialization failed")
    process = subprocess.Popen(["mysqld", "--no-defaults", f"--datadir={data}", f"--socket={socket}",
        f"--pid-file={work / 'mysql.pid'}", f"--log-error={log}", "--bind-address=127.0.0.1", "--port=53306",
        "--mysqlx=0", f"--plugin-dir={adapter}", "--log-bin-trust-function-creators=1"],
        env=dict(os.environ, DADP_DB_CONFIG_FILE=str(fixture.config_file)))

    def sql(statement):
        subprocess.run(["mysql", "--no-defaults", f"--socket={socket}", "-uroot", "--default-character-set=utf8mb4"],
                       input=statement, text=True, check=True, capture_output=True)

    try:
        for _ in range(150):
            if process.poll() is not None:
                raise RuntimeError(log.read_text())
            if subprocess.run(["mysqladmin", "--no-defaults", f"--socket={socket}", "-uroot", "ping"], capture_output=True).returncode == 0:
                break
            time.sleep(0.1)
        else:
            raise RuntimeError("MySQL startup timeout")
        sql((work / "mysql/sql/install.sql").read_text())
        # Isolated loopback-only disposable server; no production credentials or TLS bypass setting escapes this fixture.
        sql("CREATE USER 'jdbc_probe'@'127.0.0.1' IDENTIFIED BY 'fixture-only'; "
            "GRANT CREATE,DROP,SELECT,INSERT,UPDATE,DELETE ON *.* TO 'jdbc_probe'@'127.0.0.1'; "
            "GRANT EXECUTE ON dadp.* TO 'jdbc_probe'@'127.0.0.1';")
        config = work / "jdbc.properties"
        config.write_text("user=jdbc_probe\npassword=fixture-only\nsslMode=DISABLED\nallowPublicKeyRetrieval=true\n"
                          "characterEncoding=UTF-8\nuseServerPrepStmts=true\ncontinueBatchOnError=false\n"
                          "connectTimeout=10000\nsocketTimeout=30000\n", encoding="ascii")
        config.chmod(0o600)
        result = subprocess.run(["bash", "/source/dadp-client-libraries/scripts/run-jdbc-process-probe.sh"],
            env=dict(os.environ, JDBC_URL="jdbc:mysql://127.0.0.1:53306/", JDBC_CONFIG=str(config),
                     JDBC_DRIVER_JAR="/jdbc.jar", JDBC_POLICY_CODE="ABCD2345", JDBC_PARTIAL_POLICY_CODE="PREFIX01",
                     JDBC_DENIED_POLICY_CODE="DENYA234"), capture_output=True, text=True, timeout=120)
        print(result.stdout, end="", flush=True)
        if result.returncode or "JDBC_PLUGIN_TARGET mysql84" not in result.stdout or "JDBC_PLUGIN_PROBE_PASS" not in result.stdout:
            print(result.stderr, file=sys.stderr)
            raise RuntimeError("Actual MySQL JDBC/native KMS probe failed")
        print("PASS: actual MySQL8.4 JDBC Agent/prepared/batch/raw storage/native KMS")
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=20)
