#!/usr/bin/env python3
"""Deploy the built wrapper fat JAR to S3 through an IAM-enabled remote host.

This script is designed for the dadp-client-libraries repo layout where wrapper code lives in
the `dadp-jdbc-wrapper` module and public artifact metadata is served
from `https://dadp-artifacts.s3.ap-northeast-2.amazonaws.com/metadata.json`.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import date
from pathlib import Path


DEFAULT_HOST = "43.202.206.43"
DEFAULT_USER = "ec2-user"
DEFAULT_BUCKET = "dadp-artifacts"
DEFAULT_REGION = "ap-northeast-2"
DEFAULT_REMOTE_TMP = "/tmp/dadp-wrapper-deploy"
DEFAULT_PUBLIC_BASE = "https://dadp-artifacts.s3.ap-northeast-2.amazonaws.com"
DEFAULT_METADATA_URL = f"{DEFAULT_PUBLIC_BASE}/metadata.json"
DEFAULT_HUB_URL = "http://localhost:9004/hub/api/v1/artifacts/wrapper"


@dataclass
class DeployContext:
    repo_root: Path
    wrapper_pom: Path
    wrapper_target: Path
    wrapper_version: str
    artifact_path: Path
    artifact_name: str
    artifact_size: int


def run(cmd: list[str], check: bool = True, capture_output: bool = False, text: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=check, capture_output=capture_output, text=text)


def fetch_json(url: str) -> dict:
    with urllib.request.urlopen(url) as response:
        charset = response.headers.get_content_charset() or "utf-8"
        return json.loads(response.read().decode(charset))


def http_head(url: str) -> tuple[int, dict[str, str]]:
    req = urllib.request.Request(url, method="HEAD")
    with urllib.request.urlopen(req) as response:
        return response.status, {k.lower(): v for k, v in response.headers.items()}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Deploy wrapper artifact to S3 via remote IAM host")
    parser.add_argument("--repo-root", default=str(Path(__file__).resolve().parents[1]), help="dadp-client-libraries repo root")
    parser.add_argument("--host", default=DEFAULT_HOST, help="IAM-enabled remote host")
    parser.add_argument("--user", default=DEFAULT_USER, help="SSH user for the remote host")
    parser.add_argument("--key-path", default=os.environ.get("DADP_DEPLOY_KEY_PATH", ""), help="SSH private key path")
    parser.add_argument("--bucket", default=DEFAULT_BUCKET, help="Target S3 bucket")
    parser.add_argument("--region", default=DEFAULT_REGION, help="AWS region")
    parser.add_argument("--remote-tmp", default=DEFAULT_REMOTE_TMP, help="Remote temporary directory")
    parser.add_argument("--public-base-url", default=DEFAULT_PUBLIC_BASE, help="Public S3 base URL")
    parser.add_argument("--metadata-url", default=DEFAULT_METADATA_URL, help="Public metadata.json URL")
    parser.add_argument("--hub-url", default=DEFAULT_HUB_URL, help="Local Hub wrapper artifact API URL")
    parser.add_argument("--release-date", default=str(date.today()), help="Release date in YYYY-MM-DD")
    parser.add_argument("--skip-build", action="store_true", help="Skip local Maven build")
    parser.add_argument("--skip-upload", action="store_true", help="Skip SSH/SCP and remote aws upload")
    parser.add_argument("--skip-hub-verify", action="store_true", help="Skip local Hub API verification")
    return parser.parse_args()


def load_context(args: argparse.Namespace) -> DeployContext:
    repo_root = Path(args.repo_root).resolve()
    wrapper_root = repo_root / "dadp-jdbc-wrapper"
    wrapper_pom = wrapper_root / "pom.xml"
    if not wrapper_pom.exists():
        raise FileNotFoundError(f"Wrapper pom not found: {wrapper_pom}")

    tree = ET.parse(wrapper_pom)
    root = tree.getroot()
    ns = {"m": root.tag.split("}")[0].strip("{")} if "}" in root.tag else {}
    version = root.findtext("m:version", namespaces=ns) if ns else root.findtext("version")
    if not version:
        raise RuntimeError(f"Could not read wrapper version from {wrapper_pom}")

    artifact_name = f"dadp-jdbc-wrapper-{version}-all.jar"
    artifact_path = wrapper_root / "target" / artifact_name
    artifact_size = artifact_path.stat().st_size if artifact_path.exists() else 0
    return DeployContext(
        repo_root=repo_root,
        wrapper_pom=wrapper_pom,
        wrapper_target=wrapper_root / "target",
        wrapper_version=version,
        artifact_path=artifact_path,
        artifact_name=artifact_name,
        artifact_size=artifact_size,
    )


def build_wrapper(ctx: DeployContext) -> None:
    cmd = [
        "mvn",
        "-pl",
        "dadp-hub-crypto-lib",
        "-Pjava8",
        "-DskipTests",
        "-Dmaven.javadoc.skip=true",
        "install",
    ]
    print("+", " ".join(cmd))
    run(cmd, check=True, capture_output=False, text=True)

    cmd = [
        "mvn",
        "-pl",
        "dadp-jdbc-wrapper",
        "-am",
        "-DskipTests",
        "-Dmaven.javadoc.skip=true",
        "package",
    ]
    print("+", " ".join(cmd))
    run(cmd, check=True, capture_output=False, text=True)


def update_wrapper_metadata(metadata: dict, ctx: DeployContext, release_date: str) -> dict:
    artifacts = metadata.setdefault("artifacts", {})
    wrapper = artifacts.setdefault("wrapper", {})
    wrapper["name"] = "DADP JDBC Wrapper"
    wrapper["description"] = "JDBC 드라이버 래핑 방식 암복호화 모듈"
    versions = wrapper.setdefault("versions", [])

    new_entry = {
        "version": ctx.wrapper_version,
        "releaseDate": release_date,
        "files": [
            {
                "fileName": ctx.artifact_name,
                "displayName": "JDBC Wrapper (Java 8+)",
                "size": ctx.artifact_size,
            }
        ],
    }

    filtered_versions = [entry for entry in versions if entry.get("version") != ctx.wrapper_version]
    wrapper["latestVersion"] = ctx.wrapper_version
    wrapper["versions"] = [new_entry] + filtered_versions
    metadata["lastUpdated"] = release_date
    return metadata


def ensure_artifact_exists(ctx: DeployContext) -> None:
    if not ctx.artifact_path.exists():
        raise FileNotFoundError(f"Built wrapper artifact not found: {ctx.artifact_path}")
    ctx.artifact_size = ctx.artifact_path.stat().st_size


def ssh_base_cmd(args: argparse.Namespace) -> list[str]:
    if not args.key_path:
        raise RuntimeError("SSH key path is required. Pass --key-path or set DADP_DEPLOY_KEY_PATH.")
    key_path = Path(args.key_path).expanduser().resolve()
    if not key_path.exists():
        raise FileNotFoundError(f"SSH key not found: {key_path}")
    return [
        "ssh",
        "-i",
        str(key_path),
        "-o",
        "StrictHostKeyChecking=no",
        f"{args.user}@{args.host}",
    ]


def scp_base_cmd(args: argparse.Namespace) -> list[str]:
    if not args.key_path:
        raise RuntimeError("SSH key path is required. Pass --key-path or set DADP_DEPLOY_KEY_PATH.")
    key_path = Path(args.key_path).expanduser().resolve()
    if not key_path.exists():
        raise FileNotFoundError(f"SSH key not found: {key_path}")
    return [
        "scp",
        "-i",
        str(key_path),
        "-o",
        "StrictHostKeyChecking=no",
    ]


def verify_ssh(args: argparse.Namespace) -> None:
    cmd = ssh_base_cmd(args) + ["echo", "SSH_OK"]
    print("+", " ".join(cmd))
    result = run(cmd, check=False, capture_output=True)
    if result.returncode != 0 or "SSH_OK" not in result.stdout:
        raise RuntimeError(f"SSH verification failed: {result.stderr.strip() or result.stdout.strip()}")


def upload_via_remote_host(args: argparse.Namespace, ctx: DeployContext, metadata_file: Path) -> None:
    verify_ssh(args)
    remote_jar = f"{args.remote_tmp}/{ctx.artifact_name}"
    remote_metadata = f"{args.remote_tmp}/metadata.json"

    mkdir_cmd = ssh_base_cmd(args) + [f"mkdir -p {args.remote_tmp}"]
    print("+", " ".join(mkdir_cmd))
    run(mkdir_cmd, check=True)

    scp_cmd = scp_base_cmd(args) + [str(ctx.artifact_path), f"{args.user}@{args.host}:{remote_jar}"]
    print("+", " ".join(scp_cmd))
    run(scp_cmd, check=True)

    scp_cmd = scp_base_cmd(args) + [str(metadata_file), f"{args.user}@{args.host}:{remote_metadata}"]
    print("+", " ".join(scp_cmd))
    run(scp_cmd, check=True)

    s3_artifact_key = f"wrapper/v{ctx.wrapper_version}/{ctx.artifact_name}"
    remote_script = f"""
set -euo pipefail
aws s3 cp '{remote_jar}' 's3://{args.bucket}/{s3_artifact_key}' --region '{args.region}'
aws s3 cp '{remote_metadata}' 's3://{args.bucket}/metadata.json' --region '{args.region}' --content-type 'application/json; charset=utf-8'
"""
    cmd = ssh_base_cmd(args) + [remote_script]
    print("+", " ".join(cmd[:-1] + ["<remote-upload-script>"]))
    run(cmd, check=True)


def verify_public_artifact(args: argparse.Namespace, ctx: DeployContext) -> None:
    jar_url = f"{args.public_base_url.rstrip('/')}/wrapper/v{ctx.wrapper_version}/{ctx.artifact_name}"
    status, headers = http_head(jar_url)
    if status != 200:
        raise RuntimeError(f"Public JAR HEAD failed: {status} {jar_url}")
    remote_size = int(headers.get("content-length", "0"))
    if remote_size != ctx.artifact_size:
        raise RuntimeError(f"Public JAR size mismatch: local={ctx.artifact_size}, remote={remote_size}")
    print(f"Verified public JAR: {jar_url} ({remote_size} bytes)")


def verify_public_metadata(args: argparse.Namespace, ctx: DeployContext) -> None:
    metadata = fetch_json(args.metadata_url)
    wrapper = metadata.get("artifacts", {}).get("wrapper", {})
    latest_version = wrapper.get("latestVersion")
    if latest_version != ctx.wrapper_version:
        raise RuntimeError(f"metadata.json latestVersion mismatch: expected {ctx.wrapper_version}, got {latest_version}")
    versions = wrapper.get("versions", [])
    current = next((entry for entry in versions if entry.get("version") == ctx.wrapper_version), None)
    if current is None:
        raise RuntimeError(f"metadata.json missing wrapper version {ctx.wrapper_version}")
    files = current.get("files", [])
    current_file = next((entry for entry in files if entry.get("fileName") == ctx.artifact_name), None)
    if current_file is None:
        raise RuntimeError(f"metadata.json missing file entry for {ctx.artifact_name}")
    if int(current_file.get("size", 0)) != ctx.artifact_size:
        raise RuntimeError("metadata.json size does not match local artifact size")
    print(f"Verified metadata.json for wrapper {ctx.wrapper_version}")


def verify_hub_api(args: argparse.Namespace, ctx: DeployContext) -> None:
    metadata = fetch_json(args.hub_url)
    wrapper = metadata.get("data", {}).get("wrapper", {})
    latest_version = wrapper.get("latestVersion")
    if latest_version != ctx.wrapper_version:
        raise RuntimeError(f"Hub API latestVersion mismatch: expected {ctx.wrapper_version}, got {latest_version}")
    versions = wrapper.get("versions", [])
    current = next((entry for entry in versions if entry.get("version") == ctx.wrapper_version), None)
    if current is None:
        raise RuntimeError(f"Hub API missing wrapper version {ctx.wrapper_version}")
    files = current.get("files", [])
    current_file = next((entry for entry in files if entry.get("fileName") == ctx.artifact_name), None)
    if current_file is None:
        raise RuntimeError(f"Hub API missing file entry for {ctx.artifact_name}")
    if int(current_file.get("size", 0)) != ctx.artifact_size:
        raise RuntimeError("Hub API size does not match local artifact size")
    print(f"Verified Hub API for wrapper {ctx.wrapper_version}")


def main() -> int:
    args = parse_args()
    ctx = load_context(args)

    if not args.skip_build:
        cwd = os.getcwd()
        os.chdir(ctx.repo_root)
        try:
            build_wrapper(ctx)
        finally:
            os.chdir(cwd)

    ensure_artifact_exists(ctx)
    print(f"Wrapper version: {ctx.wrapper_version}")
    print(f"Artifact: {ctx.artifact_path}")
    print(f"Size: {ctx.artifact_size}")

    metadata = fetch_json(args.metadata_url)
    updated = update_wrapper_metadata(metadata, ctx, args.release_date)

    with tempfile.TemporaryDirectory(prefix="dadp-wrapper-deploy-") as tmpdir:
        metadata_file = Path(tmpdir) / "metadata.json"
        metadata_file.write_text(json.dumps(updated, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Prepared metadata file: {metadata_file}")

        if not args.skip_upload:
            upload_via_remote_host(args, ctx, metadata_file)

    if not args.skip_upload:
        verify_public_artifact(args, ctx)
        verify_public_metadata(args, ctx)
        if not args.skip_hub_verify:
            verify_hub_api(args, ctx)
    else:
        print("Upload skipped; public verification skipped.")

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except urllib.error.URLError as exc:
        print(f"Network error: {exc}", file=sys.stderr)
        raise SystemExit(1)
    except Exception as exc:  # noqa: BLE001
        print(f"Error: {exc}", file=sys.stderr)
        raise SystemExit(1)
