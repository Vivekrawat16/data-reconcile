# ⚖️ Moqui Data Reconciler (`data-reconciler`)

**Author:** HotWax Commerce Engineering  
**Version:** 1.0.0  
**Component Type:** Moqui Native Framework Component  
**Path:** `runtime/component/data-reconciler`

---

## 🎯 1. Overview & Objective

During release upgrades (e.g. moving an instance from `v6.4.1` to `v6.4.2` or `v6.3.0` to `v6.4.0`), production instances frequently experience **data drift**—missing system metadata such as `Enumeration`, `StatusItem`, and `SystemMessageType`.

Instead of manually checking dozens of separate component repositories, you only provide the **`maarg-docker-config` release tags**:
1. **Automated Component Shift Resolution (`DockerConfigReleaseResolver`):**
   - Automatically inspects the diff of `myaddons.xml` and `Dockerfiles` in `hotwax/hotwax-maarg-docker-config` between `fromTag` and `toTag` via GitHub API.
   - Detects all shifted components (e.g. `shopify-connector: 4.4.1 -> 4.4.2`, `oms: 3.3.0 -> 3.4.0`, `moqui-runtime: 4.0.0 -> 4.1.0`).
2. **Multi-Component GitHub Diff Scanning (`GitDiffScanner`):**
   - Queries GitHub Compare API for EACH shifted component repository directly over HTTPS.
   - Parses unified diffs to extract all newly added entity XML definitions across all components.
3. **Pulls Reference Data (`RemoteInstanceClient`):**
   - Connects to the reference instance (e.g., UAT on latest release) to fetch active metadata.
4. **Local Database Audit & Attribution (`ReconcileEngine`):**
   - Audits the local database via Moqui's native Entity Facade.
   - Flags missing records introduced by the release as **CRITICAL** and tags which component repo introduced them!
5. **Self-Healing Auto-Apply (`autoApply=true`):**
   - Automatically commits critical records into the local database inside an atomic transaction. Zero manual XML upload!

---

## 🏗️ 2. Component Architecture

```
                       [ Input: fromTag..toTag & referenceUrl ]
                                          │
                  ┌───────────────────────┴───────────────────────┐
                  ▼                                               ▼
     [ 1. RemoteInstanceClient ]                        [ 2. GitDiffScanner ]
   Fetches reference instance data                  Executes `git diff fromTag..toTag`
   via REST / DataExport API                       Extracts newly added entity XMLs
                  │                                               │
                  └───────────────────────┬───────────────────────┘
                                          │
                                          ▼
                                [ 3. ReconcileEngine ]
                     Compares Reference vs Local DB (`ec.entity`)
                     Correlates with Git Diff to isolate release additions
                                          │
                                          ▼
                                  [ 4. Output / Action ]
             ┌────────────────────────────┴────────────────────────────┐
             ▼                                                         ▼
  [ If autoApply = false ]                                  [ If autoApply = true ]
  Generates Markdown Report +                               Directly calls `ec.entity.makeValue().create()`
  Moqui XML Sync Payload                                    Auto-repairs DB in a single transaction
```

---

## 🚀 3. How to Run

### Option A: Via Moqui REST API (CI/CD Pipeline)
Trigger from Jenkins or a deployment script immediately after code checkout:

```bash
# Comparing Two Remote Instances (e.g. UAT vs Production):
curl -X POST https://your-instance.hotwax.io/rest/s1/reconciler/run \
  -H "Authorization: Bearer <YOUR_JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "referenceUrl": "https://krewe-maarg-uat.hotwax.io",
    "referenceApiKey": "Bearer eyJhbGciOi...",
    "targetUrl": "https://krewe-maarg.hotwax.io",
    "targetApiKey": "Bearer eyJhbGciOi...",
    "fromTag": "v6.3.0",
    "toTag": "v6.4.0",
    "autoApply": false
  }'
```

### Option B: From Inside Moqui Code / Upgrade Scripts (`UpgradeData.xml`)
Call the service programmatically inside an upgrade script or scheduled job:

```xml
<!-- Reconcile Source Instance vs Target Instance -->
<service-call name="co.hotwax.reconciler.ReconcileServices.reconcile#ReleaseMetadata"
              in-map="[
                  referenceUrl: 'https://krewe-maarg-uat.hotwax.io',
                  referenceApiKey: 'Bearer ...',
                  targetUrl: 'https://krewe-maarg.hotwax.io',
                  targetApiKey: 'Bearer ...',
                  fromTag: 'v6.3.0',
                  toTag: 'v6.4.0',
                  autoApply: false
              ]"
              out-map="reconcileResult"/>
```

### Option C: Groovy Script / CLI Execution
Run directly from terminal or Groovy shell:

```groovy
def res = ec.service.sync().name("co.hotwax.reconciler.ReconcileServices.reconcile#ReleaseMetadata")
            .parameters([
                referenceUrl: "https://krewe-maarg-uat.hotwax.io",
                targetUrl: "https://rails-uat.hotwax.io",
                fromTag: "v6.3.0",
                toTag: "v6.4.0",
                autoApply: false
            ]).call()

println res.summaryReport
println res.combinedSyncXml
```

---

## 📊 4. Master Scope of Supported Entities

By default, the component reconciles the critical Tier-1 & Tier-2 metadata required for release stability:

1. `moqui.basic.EnumerationType`
2. `moqui.basic.Enumeration`
3. `moqui.basic.StatusType`
4. `moqui.basic.StatusItem`
5. `moqui.service.message.SystemMessageType`
6. `moqui.basic.Uom`
7. `mantle.product.feature.ProductFeatureType`
8. `mantle.facility.FacilityType`

Custom entity lists can be passed via the `entityList` parameter.

---

## 🛡️ 5. Key Advantages for Tech Leads & Managers

1. **Zero Human Error:** No developer needs to manually inspect tables, XML diffs, or run ad-hoc queries.
2. **Git-Verified:** Correlates with Git history so experimental/test records in UAT do not get pushed to Production.
3. **Ghost Enum & Status Detection:** Identifies code conditions where developers referenced enums/statuses in Groovy/XML/Java but forgot to commit `UpgradeData.xml`.
4. **Self-Healing:** Auto-applies fixes directly into the database within an atomic transaction.
5. **Universal Reusability:** Once deployed in `runtime/component`, works for any HotWax client (Krewe, Rails, Perry Ellis, ThirdLove) during every future release.

---

## 🕵️‍♂️ 6. Developer Omission Scanner (Ghost Enums & Statuses)

Often a developer writes logic in a service or script:
```groovy
if (orderHeader.statusId == 'ORDER_CANCELLED') { ... }
```
or in XML:
```xml
<set field="settingTypeEnumId" value="SHPFY_LOC_CUSTOM"/>
```
...but forgets to define `<moqui.basic.Enumeration>` or `<moqui.basic.StatusItem>` in `UpgradeData.xml`.

The reconciler:
1. Scans code diffs across all shifted components for enum and status references.
2. Cross-references against the release's Git data additions (`UpgradeData.xml`, `*Data.xml`).
3. Queries Moqui's local database.
4. If missing from both release XML and database:
   - Emits a high-priority **Developer Omission Alert**.
   - Identifies the exact component, file, line snippet, and tag shift.
   - Generates copy-pasteable XML stubs for instant recovery!

---

## ⚡ 7. Service Contract & Breaking Change Scanner (Phase 1)

Whenever services in Moqui are updated between release tags:
1. **Detects Breaking In-Parameters:** Identifies existing services that added a new `required="true"` parameter (which breaks existing callers and scheduled jobs that do not pass it).
2. **Detects Removed In/Out Parameters:** Catches deleted parameters from existing services that calling code relies on.
3. **Detects Deleted/Renamed Services:** Flags services removed from XML files.
4. **New Services Catalog:** Catalogs all newly introduced services with their implementation type (`inline`, `script`, `oms-rest`, etc.).
5. **Pre-flight Definition Verification:** Uses Moqui's Service Facade to verify that every new or modified service compiles and loads without error.

---

## 🔬 8. Deep Service Integrity & Scheduled Job Pre-Flight Audit (Phase 2)

Phase 2 builds upon the Service Contract Scanner by analyzing deep runtime linkages and scheduled job compatibility:

1. **Scheduled Job Crash Pre-Flight Check:**
   - Queries `moqui.service.job.ServiceJob` and `moqui.service.job.ServiceJobParameter` in the database.
   - Cross-references all active scheduled jobs against services that introduced new `required="true"` parameters in the incoming release tag.
   - Flags any active job missing the new required parameter with a **`CRITICAL`** alert before cron trigger, preventing production job failures and outages.
2. **Disk & Component Script Verification:**
   - For services using `location="component://..."` or `location="file://..."`, resolves the path via Moqui's `ec.resource.getLocationReference(loc)`.
   - Verifies whether the referenced script file actually exists on the filesystem, catching missing `.groovy` or `.xml` script files before execution.
3. **Escalated Audit Status:**
   - Automatically escalates the release health status to `CRITICAL_SCHEDULED_JOBS_AT_RISK` if any active cron job is in danger of failing.

---

## 🧪 9. Automated Test Suite Runner & Smoke Audit (Phase 3)

Phase 3 provides end-to-end automated test verification and service dry-run execution:

1. **Component Test Suite Discovery:**
   - Automatically scans all shifted components for Spock specifications (`*Spec.groovy`), JUnit test suites (`*Test*.groovy`, `*Test*.java`), and Moqui XML test cases.
   - Extracts test methods, features, and test counts.
   - Verifies test class compilation and imports in Moqui's `GroovyClassLoader` to catch compile-time breakage before running.
2. **Non-Destructive Service Smoke Tests (Dry-Run):**
   - For newly introduced services, tests invocation readiness.
   - If a service requires no mandatory parameters, executes a dry-run invocation within an isolated transaction that is immediately rolled back (0 persistent side-effects or dirty data in DB).
   - If a service requires mandatory parameters, verifies definition and parameter schema completeness.
3. **Execution Controls:**
   - `runAutomatedTests` (Boolean, default: `false`): When set to `true`, actively runs test suites and smoke tests.
   - `testComponentList` (String, optional): Filter specific components (e.g., `mantle-shopify-connector,oms`).
4. **Automated Health Status:**
   - Emits `AUTOMATED_TESTS_FAILED` if any test suite fails or throws a compilation error.
