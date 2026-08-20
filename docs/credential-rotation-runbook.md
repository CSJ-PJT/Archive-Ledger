# Credential Rotation Runbook

This runbook uses placeholder names only. Do not put token values in tickets, logs, source code, or Git.

1. Generate one high-entropy token per Ledger scope: Market ingest, Nexus ingest, Logistics ingest, ArchiveOS callback, ArchiveOS admin, and ArchiveOS read.
2. Store each value in the approved local secret store or CI secret store and inject it through the corresponding `ARCHIVE_LEDGER_*_TOKEN` environment variable.
3. Deploy the new receiving configuration and callers during a planned overlap window. The current baseline accepts one active token per scope; use the gateway or a short coordinated restart for overlap if a grace period is required.
4. Recreate only the Ledger container after changing environment variables. Do not delete database volumes or reset synthetic data.
5. Verify valid calls return `2xx`, old credentials return `401`, wrong source/scope returns `403`, and no `401`/`403` retry loop is running.
6. Search application and container logs for token-shaped values. Investigate and redact any accidental disclosure before continuing.
7. Revoke the previous value in the secret store and update the rotation record without recording the value itself.

Required configuration is validated at RC startup. Missing DB credentials, missing service tokens, or wildcard CORS origins stop startup rather than falling back to a development credential.
