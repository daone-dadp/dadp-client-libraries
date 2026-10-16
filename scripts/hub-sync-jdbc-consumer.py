#!/usr/bin/env python3
"""Private process_gate consumer: real Hub mapping/sync and Engine JDBC on PG17."""
import http.cookiejar
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request
from urllib.parse import urlparse
import uuid

ROOT = Path(__file__).resolve().parent.parent


def run(*args, data=None):
    result = subprocess.run(args, input=data, capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError('Fixture command failed: ' + args[0])
    return result.stdout


def loopback(url, scheme):
    parsed = urlparse(url)
    if parsed.scheme != scheme or parsed.hostname != '127.0.0.1':
        raise RuntimeError('Disposable loopback endpoint required')


def consume():
    os.umask(0o077)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    source = Path(os.environ['DADP_BROWSER_FIXTURE_FILE'])
    if source.stat().st_mode & 0o077:
        raise RuntimeError('Owner-only manifest required')
    fixture = json.loads(source.read_text())
    loopback(fixture['hubUrl'], 'http')
    loopback(fixture['workerUrl'], 'https')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def api(route, body=None, method=None, expected=200):
        request = urllib.request.Request(fixture['hubUrl'] + route,
            data=json.dumps(body).encode() if body is not None else None,
            headers={'Content-Type': 'application/json'}, method=method)
        with opener.open(request, timeout=20) as response:
            if response.status != expected:
                raise RuntimeError('Hub status mismatch for ' + route)
            return json.load(response)

    name = 'dadp-hub-jdbc-' + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix='dadp-hub-jdbc-') as directory:
        work = Path(directory)
        password = secrets.token_hex(24)
        secret = work/'pg.password'
        secret.write_text(password + '\n')
        try:
            run('docker', 'run', '-d', '--name', name, '--label', 'dadp.test=hub-jdbc',
                '-p', '127.0.0.1::5432', '--tmpfs', '/pgdata:rw',
                '-v', str(secret)+':/run/source-password:ro', 'dadp-pg17-kms-test:local', 'sh', '-ec',
                'install -d -o postgres -g postgres -m 700 /pgdata; '
                'install -o postgres -g postgres -m 600 /run/source-password /tmp/pg-password; '
                'runuser -u postgres -- /usr/lib/postgresql/17/bin/initdb -D /pgdata '
                '--auth-local=trust --auth-host=scram-sha-256 --pwfile=/tmp/pg-password >/dev/null; '
                'printf "host all all 0.0.0.0/0 scram-sha-256\\n" >> /pgdata/pg_hba.conf; '
                'exec runuser -u postgres -- /usr/lib/postgresql/17/bin/postgres -D /pgdata -c listen_addresses=*')
            for _ in range(100):
                if subprocess.run(['docker', 'exec', name, 'pg_isready', '-U', 'postgres'], capture_output=True).returncode == 0:
                    break
                time.sleep(.2)
            else:
                raise RuntimeError('PG readiness timeout')
            run('docker', 'exec', '-i', '--user', 'postgres', name, 'psql', '-v', 'ON_ERROR_STOP=1', '-d', 'postgres',
                data='CREATE TABLE public.records(id integer PRIMARY KEY, secret text);\n')
            bindings = json.loads(run('docker', 'inspect', '--format', '{{json .NetworkSettings.Ports}}', name))['5432/tcp']
            if len(bindings) != 1 or bindings[0]['HostIp'] != '127.0.0.1':
                raise RuntimeError('PG must be loopback only')
            port = int(bindings[0]['HostPort'])
            api('/hub/api/v1/auth/login', dict(username=fixture['username'], password=fixture['password']))
            api('/hub/api/v1/system/settings', dict(defaultWrapperEngineUrl=fixture['workerUrl']), method='PATCH')
            enrolled = api('/hub/api/v1/wrapper-enrollments/schema-register', dict(alias=name, wrapperType='JDBC',
                appName='isolated-hub-jdbc', wrapperVersion='8.0.0',
                datasource=dict(datasourceKey=name, vendor='postgres', host='127.0.0.1', port=port,
                    databaseName='postgres', schemaName='public', displayName=name),
                schema=dict(tables=[dict(schemaName='public', tableName='records', columns=[
                    dict(columnName='id', columnType='integer'), dict(columnName='secret', columnType='text')])])), expected=201)['data']
            api('/hub/api/v1/proxy/mappings', dict(alias=name, schemaName='public', tableName='records',
                columnName='secret', policyCode=fixture['policyCode']), expected=201)
            store_password = work/'store.password'
            store_password.write_text(secrets.token_hex(24))
            key_store, trust_store = work/'agent.p12', work/'trust.p12'
            run('openssl', 'pkcs12', '-export', '-name', 'agent', '-inkey', fixture['workerClientKeyFile'],
                '-in', fixture['workerClientCertFile'], '-certfile', fixture['workerClientCAFile'],
                '-out', str(key_store), '-passout', 'file:'+str(store_password))
            run('keytool', '-importcert', '-noprompt', '-alias', 'fixture-ca', '-storetype', 'PKCS12',
                '-keystore', str(trust_store), '-storepass:file', str(store_password), '-file', fixture['workerClientCAFile'])
            tls = work/'tls.properties'
            tls.write_text('\n'.join(f'{k}={v}' for k, v in dict(keyStore=key_store, trustStore=trust_store,
                keyStorePasswordFile=store_password, trustStorePasswordFile=store_password).items())+'\n')
            config = work/'probe.properties'
            config.write_text('\n'.join(f'{k}={v}' for k, v in dict(alias=name, tenantId=enrolled['tenantId'],
                hubUrl=fixture['hubUrl'], workerUrl=fixture['workerUrl'], policyCode=fixture['policyCode'],
                jdbcUrl=f'jdbc:postgresql://127.0.0.1:{port}/postgres', user='postgres', password=password).items())+'\n')
            wrapper = work/'agent.jar'
            shutil.copyfile(os.environ.get('WRAPPER_JAR', str(ROOT/'dadp-jdbc-wrapper/target/dadp-jdbc-wrapper-8.0.0-SNAPSHOT-all.jar')), wrapper)
            probe = os.environ.get('HUB_JDBC_PROBE_JAR', str(ROOT/'dadp-jdbc-wrapper/target/dadp-hub-sync-jdbc-probe.jar'))
            driver = os.environ['PG_JDBC_JAR']
            result = subprocess.run(['java', '-Ddadp.worker.tls.config='+str(tls), '-cp',
                os.pathsep.join([str(wrapper), probe, driver]), 'com.dadp.jdbc.integration.HubSyncJdbcProbe', str(config)],
                capture_output=True, text=True, timeout=120)
            # Parent stores this consumer's output privately; no credentials on the controlling console.
            print(result.stdout, end='')
            if result.returncode:
                print(result.stderr, file=sys.stderr)
                raise RuntimeError('Actual Hub JDBC probe failed')
            if 'REAL_HUB_ENGINE_JDBC_PASS' not in result.stdout:
                raise RuntimeError('Missing JDBC evidence')
            receipt = os.environ.get('HUB_JDBC_RESULT_FILE')
            if receipt:
                Path(receipt).write_text(json.dumps(dict(status='PASS', source='actual-Hub-refresh',
                    database='PostgreSQL17', workerTransport='HTTPS-mTLS', crypto='native-KMS',
                    agentJarSHA256=hashlib.sha256(wrapper.read_bytes()).hexdigest(),
                    evidence=[line for line in result.stdout.splitlines() if line.startswith('REAL_HUB_')]), indent=2)+'\n')
        finally:
            run('docker', 'rm', '-f', '-v', name)


def fixture_main():
    # Reuse the owner's process lifecycle without changing parent files. Only the
    # manifest's Worker endpoint and TLS identity paths are extended, never keys.
    path = ROOT.parent/'deploy/systemd/tests/process_gate.py'
    spec = importlib.util.spec_from_file_location('parent_process_gate', path)
    parent = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parent)
    base = parent.Gate

    class JdbcGate(base):
        def write(self, path, value):
            if Path(path).name == 'browser-fixture.json':
                value = dict(value, workerUrl=self.url('worker'), workerClientCAFile=str(self.private/'ca.pem'),
                    workerClientCertFile=str(self.private/'agent.pem'), workerClientKeyFile=str(self.private/'agent.key'))
            return super().write(path, value)

    parent.Gate = JdbcGate
    sys.argv += ['--consumer', sys.executable, str(Path(__file__).resolve()), '--consume']
    parent.main()


if __name__ == '__main__':
    if sys.argv[1:] == ['--consume']:
        consume()
    else:
        fixture_main()
