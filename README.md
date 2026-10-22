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
