#!/usr/bin/env python3
"""Rebuild the frozen, version-locked JDBC compatibility candidate; never deploy."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
BASE_SHA = "f4ed47c03620cc044cf35b80f25082712644a809a9a3bddac90ce42ce50b0198"
URL = "https://repo.maven.apache.org/maven2/com/sqream/sqream-jdbc/6.6/sqream-jdbc-6.6.jar"
EXPECTED = {
    "compat": "249a4dd56cc34035b09bce8986475d0a7ed0b8460db2d899f470097d04b37fbb",
    "baseline": "5486c9de381db21cccb1813e03f9500cf83f447678e2c66eba98025f3a21884d",
}
SOURCES = [
    "com/sqream/jdbc/connector/messenger/MessengerImpl.java",
    "com/sqream/jdbc/connector/heartbeat/HeartBeatServiceImpl.java",
]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def manifest(raw, version):
    attrs = {}
    last = None
    for line in raw.decode().replace("\r\n", "\n").splitlines():
        if not line:
            continue
        if line.startswith(" ") and last:
            attrs[last] += line[1:]
        elif ": " in line:
            last, value = line.split(": ", 1)
            attrs[last] = value
    attrs["Implementation-Version"] = version
    attrs["DADP-Compat-Baseline-SHA256"] = BASE_SHA
    result = []
    for key, value in attrs.items():
        line = key + ": " + value
        while len(line) > 70:
            result.append(line[:70])
            line = " " + line[70:]
        result.append(line)
    return ("\r\n".join(result) + "\r\n\r\n").encode()


def fixed_entry(dst, name, data):
    entry = zipfile.ZipInfo(name, (2026, 11, 18, 0, 0, 0))
    entry.compress_type = zipfile.ZIP_DEFLATED
    dst.writestr(entry, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-jar", type=Path)
    parser.add_argument("--output", type=Path, required=True,
                        help="External output directory; original JAR is never overwritten")
    args = parser.parse_args()
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents:
        raise SystemExit("Use an output directory outside the tracked compatibility source")
    output.mkdir(parents=True, exist_ok=True)
    base = args.base_jar.resolve() if args.base_jar else output / "sqream-jdbc-6.6.original.jar"
    if not base.exists():
        if args.base_jar:
            raise SystemExit("Explicit baseline JAR does not exist")
        with urllib.request.urlopen(URL, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != BASE_SHA:
            raise SystemExit("Downloaded original JAR hash mismatch")
        base.write_bytes(data)
    if sha(base) != BASE_SHA:
        raise SystemExit("Only the exact pinned official Maven Central JDBC 6.6 JAR is accepted")
    metadata = json.loads((ROOT / "build-metadata.json").read_text())
    for name, expected in metadata["sourceHashes"].items():
        if sha(ROOT / name) != expected:
            raise SystemExit("Frozen source hash mismatch: " + name)
    compiler = subprocess.check_output(["javac", "-version"], stderr=subprocess.STDOUT, text=True).strip()
    receipt = dict(baseSha256=BASE_SHA, compiler=compiler, sqlRetries=0, deploymentPerformed=False, outputs={})
    for kind, version, source_dir, metadata_name in [
        ("baseline", "6.6-dadp-baseline-recompiled", "baseline-source", "baseline-build-metadata.json"),
        ("compat", "6.6-dadpcompat1", "compat-src", "build-metadata.json"),
    ]:
        with tempfile.TemporaryDirectory(prefix="sqream-compat-classes-") as temporary:
            classes_dir = Path(temporary)
            subprocess.run(["javac", "--release", "8", "-cp", str(base), "-d", str(classes_dir),
                            *[str(ROOT / source_dir / name) for name in SOURCES]], check=True)
            classes = {str(p.relative_to(classes_dir)): p.read_bytes()
                       for p in classes_dir.rglob("*.class")}
            target = output / ("sqream-jdbc-" + version + ".jar")
            if target.resolve() == base:
                raise SystemExit("Refusing to overwrite baseline JAR")
            with zipfile.ZipFile(base) as original, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as rebuilt:
                for info in original.infolist():
                    data = original.read(info.filename)
                    if info.filename == "META-INF/MANIFEST.MF":
                        data = manifest(data, version)
                    if info.filename in classes:
                        data = classes.pop(info.filename)
                    rebuilt.writestr(copy.copy(info), data)
                for name, data in sorted(classes.items()):
                    fixed_entry(rebuilt, name, data)
                fixed_entry(rebuilt, "META-INF/DADP-COMPAT-LICENSE.txt",
                            (ROOT / "DADP-COMPAT-LICENSE.txt").read_bytes())
                # Canonical original experiment provenance is intentionally retained verbatim.
                fixed_entry(rebuilt, "META-INF/DADP-COMPAT-BUILD.json",
                            (ROOT / metadata_name).read_bytes())
            actual = sha(target)
            if actual != EXPECTED[kind]:
                raise SystemExit("Rebuilt candidate differs from frozen hash: " + kind + " " + actual)
            receipt["outputs"][kind] = dict(path=str(target), sha256=actual)
    (output / "rebuild-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
