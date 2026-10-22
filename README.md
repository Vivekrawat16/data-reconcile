# Moqui Data Reconciler

`data-reconciler` audits release metadata introduced between two component releases and compares it with a reference and target Moqui instance.

Version 2.0 is fail-closed and read-only by default. An unavailable GitHub/reference/target input produces `AUDIT_INCOMPLETE`; it is never interpreted as an empty dataset or `IN_SYNC`.

## Safety model

- The REST resource requires authentication.
- The service requires the `DATA_RECONCILE_ADMIN` permission.
- Remote instance URLs must use HTTPS, may not resolve to private/local addresses, and may be restricted with `-Ddata.reconciler.allowedHosts=uat.example.com,prod.example.com`.
- The service is strictly read-only and audit-only; it never modifies the database or applies mutations automatically.
- Existing records and field conflicts are reported and never overwritten.
- Generates `combinedSyncXml` containing verified candidate records for manual review or import.

Load `data/ReconcilerSecurityData.xml` as `ext-seed` before using the service. It assigns the permission to the `ADMIN` group; assign it to other operator groups explicitly if required.

## Supported metadata

- `moqui.basic.EnumerationType`
- `moqui.basic.Enumeration`
- `moqui.basic.StatusType`
- `moqui.basic.StatusItem`
- `moqui.service.message.SystemMessageType`
- `moqui.basic.Uom`
- `mantle.product.feature.ProductFeatureType`
- `mantle.facility.FacilityType`

The service intentionally rejects arbitrary entity names. Primary keys are read from Moqui entity metadata, including composite keys where supported by future allowlisted entities.

## API

`POST /rest/s1/reconciler/run`

Example read-only request:

```bash
curl -X POST https://your-instance.example/rest/s1/reconciler/run \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "referenceUrl": "https://reference.example",
    "targetUrl": "local",
    "fromTag": "v6.3.0",
    "toTag": "v6.4.0"
  }'
```

Important response fields:

- `auditComplete`: true only when all required scans and fetches succeeded.
- `overallStatus`: never `IN_SYNC` when `auditComplete=false`.
- `errors` and `warnings`: explicit input, scan, validation, and apply outcomes.
- `resultsByEntity`: missing records, environment-only gaps, and field-level conflicts.
- `combinedSyncXml`: create-only candidate payload for review.

`githubRepo` bypasses docker-config resolution for one GitHub repository. `repoPath` does the same for one local Git checkout. Without either, component shifts are resolved from `dockerConfigRepo`.

## Service and test analysis

The reconciler inventories changed service contracts using full Moqui service names and checks active scheduled jobs for newly required parameters. Unified diff hunks without enough service context make the audit incomplete instead of silently passing.

Runtime test execution and service smoke invocation are deliberately disabled. `runAutomatedTests=true` returns a warning directing the operator to isolated build/CI execution. It never marks discovered tests as passed without executing them and never invokes application services as a dry run.

## Build and test

Use Java 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew :runtime:component:data-reconciler:test
```

The component tests cover CSV quoting/multiline fields, authentication headers, fail-closed diff parsing, release shift parsing, service contract classification, component path validation, scheduled-job filtering, and composite primary-key encoding.

## Current boundaries

- Git attribution comes from added data records in Git unified diffs. Ambiguous/incomplete multiline changes stop the audit.
- Developer omission verification is local-target only; it is skipped with a warning for remote targets.
- Service definition loading validates the currently deployed target checkout, not an undeployed future artifact.
