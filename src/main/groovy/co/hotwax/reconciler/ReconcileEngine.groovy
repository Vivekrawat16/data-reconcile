package co.hotwax.reconciler

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * ReconcileEngine
 * Core reconciliation processor comparing Reference Instance data,
 * Local Instance database, and Git Diff between release tags.
 */
class ReconcileEngine {
    private static final Logger logger = LoggerFactory.getLogger(ReconcileEngine.class)

    private static final Set<String> AUDIT_FIELDS = [
        "lastUpdatedStamp", "createdStamp", "lastUpdatedTxStamp", "_isMulti"
    ] as Set

    static Map<String, Object> reconcileEntity(
            ExecutionContext ec,
            String referenceUrl,
            String apiKey,
            String githubRepo,
            String githubToken,
            String fromTag,
            String toTag,
            String entityName,
            String repoPath,
            boolean autoApply) {

        Map<String, Map<String, Map<String, String>>> gitAdditions = [:]
        if (fromTag && toTag && (githubRepo || repoPath)) {
            gitAdditions = GitDiffScanner.scanDiff(githubRepo, githubToken, repoPath, fromTag, toTag, [entityName])
        }
        Map<String, Map<String, String>> gitEntityMap = gitAdditions.get(entityName) ?: [:]

        return reconcileEntity(ec, referenceUrl, apiKey, null, null, entityName, gitEntityMap, [:], autoApply)
    }

    static Map<String, Object> reconcileEntity(
            ExecutionContext ec,
            String referenceUrl,
            String apiKey,
            String entityName,
            Map<String, Map<String, String>> gitEntityMap,
            Map<String, String> entityPkOrigins,
            boolean autoApply) {
        return reconcileEntity(ec, referenceUrl, apiKey, null, null, entityName, gitEntityMap, entityPkOrigins, autoApply)
    }

    static Map<String, Object> reconcileEntity(
            ExecutionContext ec,
            String referenceUrl,
            String apiKey,
            String targetUrl,
            String targetApiKey,
            String entityName,
            Map<String, Map<String, String>> gitEntityMap,
            Map<String, String> entityPkOrigins,
            boolean autoApply) {

        long startTime = System.currentTimeMillis()
        Map<String, Object> result = [
            entityName: entityName,
            matchingCount: 0,
            criticalMissingList: [],
            environmentMissingList: [],
            autoAppliedCount: 0,
            syncXmlPayload: "",
            executionTimeMs: 0
        ]

        // 1. Fetch Remote Reference (Source) Data
        List<Map<String, Object>> sourceRows = RemoteInstanceClient.fetchRemoteEntity(referenceUrl, apiKey, entityName)
        if (!sourceRows) {
            logger.warn("No source records fetched for ${entityName} from ${referenceUrl}")
        }

        // 2. Determine Primary Key(s)
        String pkField = getPkFieldName(entityName)

        // 3. Determine Target (Remote Instance vs Local DB)
        boolean isTargetRemote = targetUrl && !targetUrl.equalsIgnoreCase("local") && (targetUrl.startsWith("http://") || targetUrl.startsWith("https://"))
        Set<String> targetPkSet = new HashSet<>()

        if (isTargetRemote) {
            List<Map<String, Object>> targetRows = RemoteInstanceClient.fetchRemoteEntity(targetUrl, targetApiKey, entityName)
            for (Map<String, Object> tr : targetRows) {
                String tpk = tr.get(pkField) as String
                if (tpk) targetPkSet.add(tpk)
            }
            logger.info("ReconcileEngine: Comparing ${entityName} - Source (${referenceUrl}): ${sourceRows.size()} records, Target (${targetUrl}): ${targetPkSet.size()} records")
        }

        // 4. Compare Source vs Target
        List<Map<String, Object>> criticalMissing = []
        List<Map<String, Object>> environmentMissing = []
        int matching = 0

        for (Map<String, Object> sourceRecord : sourceRows) {
            String pkValue = sourceRecord.get(pkField) as String
            if (!pkValue) continue

            boolean existsInTarget = false
            if (isTargetRemote) {
                existsInTarget = targetPkSet.contains(pkValue)
            } else {
                EntityValue localVal = ec.entity.find(entityName).condition(pkField, pkValue).one()
                existsInTarget = (localVal != null)
            }

            if (existsInTarget) {
                matching++
            } else {
                // Record is MISSING in target! Check if introduced in Git Diff
                boolean isFromGit = gitEntityMap.containsKey(pkValue)
                Map<String, Object> cleanRecord = sanitizeRecord(sourceRecord)

                if (isFromGit) {
                    if (entityPkOrigins && entityPkOrigins.containsKey("${entityName}::${pkValue}")) {
                        cleanRecord.put("_introducedBy", entityPkOrigins.get("${entityName}::${pkValue}"))
                    }
                    criticalMissing.add(cleanRecord)
                } else {
                    environmentMissing.add(cleanRecord)
                }
            }
        }

        result.matchingCount = matching
        result.criticalMissingList = criticalMissing
        result.environmentMissingList = environmentMissing

        // 5. Generate Moqui XML Payload ONLY for critical missing release records
        StringBuilder xml = new StringBuilder()
        for (Map<String, Object> rec : criticalMissing) {
            xml.append("  <${entityName}")
            for (Map.Entry<String, Object> entry : rec.entrySet()) {
                if (entry.key.startsWith("_")) continue
                if (entry.value != null && !entry.value.toString().isEmpty()) {
                    String escapedVal = entry.value.toString().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
                    xml.append(" ${entry.key}=\"${escapedVal}\"")
                }
            }
            xml.append("/>\n")
        }
        result.syncXmlPayload = xml.toString()

        // 6. Auto-Apply if requested
        int applied = 0
        if (autoApply && criticalMissing) {
            if (isTargetRemote) {
                // Remote auto-apply via HTTP DataImport
                int uploaded = RemoteInstanceClient.uploadRemoteXml(targetUrl, targetApiKey, result.syncXmlPayload)
                if (uploaded > 0) applied = criticalMissing.size()
            } else {
                // Local DB atomic transaction commit
                boolean beganTx = ec.transaction.begin(300)
                try {
                    for (Map<String, Object> rec : criticalMissing) {
                        EntityValue ev = ec.entity.makeValue(entityName)
                        Map<String, Object> dbMap = [:]
                        for (Map.Entry<String, Object> e : rec.entrySet()) {
                            if (!e.key.startsWith("_")) dbMap.put(e.key, e.value)
                        }
                        ev.setAll(dbMap)
                        ev.createOrUpdate()
                        applied++
                    }
                    ec.transaction.commit(beganTx)
                    logger.info("ReconcileEngine: Successfully auto-applied ${applied} critical records for ${entityName} to local database")
                } catch (Exception e) {
                    ec.transaction.rollback(beganTx, "Failed to auto-apply reconciliation records", e)
                    logger.error("Error auto-applying ${entityName} records: ${e.message}", e)
                }
            }
        }
        result.autoAppliedCount = applied
        result.executionTimeMs = System.currentTimeMillis() - startTime

        return result
    }

    private static Map<String, Object> sanitizeRecord(Map<String, Object> original) {
        Map<String, Object> clean = [:]
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            if (!AUDIT_FIELDS.contains(entry.key) && entry.value != null) {
                clean.put(entry.key, entry.value)
            }
        }
        return clean
    }

    private static String getPkFieldName(String entityName) {
        if (entityName.endsWith("Enumeration")) return "enumId"
        if (entityName.endsWith("EnumerationType")) return "enumTypeId"
        if (entityName.endsWith("StatusItem")) return "statusId"
        if (entityName.endsWith("StatusType")) return "statusTypeId"
        if (entityName.endsWith("SystemMessageType")) return "systemMessageTypeId"
        if (entityName.endsWith("Uom")) return "uomId"
        if (entityName.endsWith("ProductFeatureType")) return "productFeatureTypeId"
        if (entityName.endsWith("FacilityType")) return "facilityTypeId"
        return "id"
    }
}
