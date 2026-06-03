# Scripts

## Confirmed

| Script | Purpose | Usage |
|---|---|---|
| `deploy_wrapper_s3_via_iam_host.py` | Build or reuse the wrapper fat JAR, update public `metadata.json`, and upload both to S3 through IAM host `54.180.239.225` via SSH/SCP. | Wrapper release deployment from the `dadp-client-libraries` repository when local `aws` CLI is unavailable and an SSH key for the IAM host is available. |
