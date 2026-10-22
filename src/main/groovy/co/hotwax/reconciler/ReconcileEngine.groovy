package co.hotwax.reconciler

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Compares source and target configuration data and identifies missing or conflicting metadata.
 */
class ReconcileEngine {
    private static final Logger logger = LoggerFactory.getLogger(ReconcileEngine.class)

    static final List<String> ENTITY_ORDER = [
            "moqui.basic.EnumerationType",
            "moqui.basic.Enumeration",
            "moqui.basic.StatusType",
            "moqui.basic.StatusItem",
            "moqui.service.message.SystemMessageType",
            "moqui.basic.Uom",
            "mantle.product.feature.ProductFeatureType",
            "mantle.facility.FacilityType"
    ]
    static final Set<String> SUPPORTED_ENTITIES = ENTITY_ORDER as Set

    private static final List<Map<String, String>> REQUIRED_DEPENDENCIES = [
            [child: "moqui.basic.Enumeration", field: "enumTypeId",
             parent: "moqui.basic.EnumerationType", parentPk: "enumTypeId"],
            [child: "moqui.basic.StatusItem", field: "statusTypeId",
             parent: "moqui.basic.StatusType", parentPk: "statusTypeId"],
            [child: "moqui.basic.StatusType", field: "parentTypeId",
             parent: "moqui.basic.StatusType", parentPk: "statusTypeId"],
            [child: "moqui.basic.EnumerationType", field: "parentTypeId",
             parent: "moqui.basic.EnumerationType", parentPk: "enumTypeId"],
            [child: "moqui.service.message.SystemMessageType", field: "parentTypeId",
             parent: "moqui.service.message.SystemMessageType", parentPk: "systemMessageTypeId"]
    ]

    private static final Set<String> AUDIT_FIELDS = [
            "lastUpdatedStamp", "createdStamp", "lastUpdatedTxStamp", "createdTxStamp", "_isMulti"
    ] as Set

    static List<String> validateEntityList(ExecutionContext ec, Collection<String> requested) {
        List<String> valid = []
        for (String entityName : requested ?: []) {
            String name = entityName?.trim()
            if (!name) continue
            if (!SUPPORTED_ENTITIES.contains(name)) {
                throw new IllegalArgumentException("Entity [${name}] is not in the reconciler metadata allowlist")
            }
            if (ec.entity.getEntityDefinition(name) == null) {
                throw new IllegalArgumentException("Entity [${name}] is not defined in this runtime")
            }
            valid.add(name)
        }
        if (valid.isEmpty()) throw new IllegalArgumentException("At least one supported entity is required")
        Set<String> requestedSet = valid as Set
        REQUIRED_DEPENDENCIES.findAll { it.child != it.parent }.each { Map<String, String> dependency ->
            if (requestedSet.contains(dependency.child) && !requestedSet.contains(dependency.parent)) {
                throw new IllegalArgumentException(
                        "Entity [${dependency.child}] requires parent entity [${dependency.parent}] in entityList")
            }
        }
        return ENTITY_ORDER.findAll { requestedSet.contains(it) }
    }

    static Map<String, Object> reconcileEntity(
            ExecutionContext ec,
            String referenceUrl,
            String apiKey,
            String targetUrl,
            String targetApiKey,
            String entityName,
            Map<String, Map<String, String>> gitEntityMap,
            Map<String, String> entityPkOrigins) {

        long start = System.currentTimeMillis()
        Map<String, Object> result = [
                success: false,
                entityName: entityName,
                primaryKeyFields: [],
                sourceCount: 0,
                targetCount: 0,
                matchingCount: 0,
                criticalMissingList: [],
                environmentMissingList: [],
                conflictList: [],
                targetPrimaryKeys: [],
                candidatePrimaryKeys: [],
                syncXmlPayload: "",
                executionTimeMs: 0,
                errors: []
        ]

        List<String> pkFields = getPkFieldNames(ec, entityName)
        result.primaryKeyFields = pkFields

        RemoteInstanceClient.FetchResult sourceResult =
                RemoteInstanceClient.fetchRemoteEntityResult(referenceUrl, apiKey, entityName)
        if (!sourceResult.success) {
            result.errors.add("Reference fetch failed for ${entityName}: ${sourceResult.error}")
            result.executionTimeMs = System.currentTimeMillis() - start
            return result
        }
        result.sourceCount = sourceResult.records.size()

        boolean remoteTarget = isRemoteTarget(targetUrl)
        Map<String, Map<String, Object>> targetByKey = [:]
        if (remoteTarget) {
            RemoteInstanceClient.FetchResult targetResult =
                    RemoteInstanceClient.fetchRemoteEntityResult(targetUrl, targetApiKey, entityName)
            if (!targetResult.success) {
                result.errors.add("Target fetch failed for ${entityName}: ${targetResult.error}")
                result.executionTimeMs = System.currentTimeMillis() - start
                return result
            }
            for (Map<String, Object> targetRow : targetResult.records) {
                String key = primaryKey(pkFields, targetRow)
                if (key) targetByKey.put(key, targetRow)
            }
            result.targetCount = targetByKey.size()
        }

        List<Map<String, Object>> criticalMissing = []
        List<Map<String, Object>> environmentMissing = []
        List<Map<String, Object>> conflicts = []
        int matching = 0

        for (Map<String, Object> sourceRow : sourceResult.records) {
            Map<String, Object> cleanSource = sanitizeRecord(ec, entityName, sourceRow)
            String key = primaryKey(pkFields, cleanSource)
            if (!key) {
                result.errors.add("Source row for ${entityName} is missing primary key fields ${pkFields}")
                continue
            }

            Map<String, Object> targetRow
            if (remoteTarget) {
                targetRow = targetByKey.get(key)
            } else {
                Map<String, Object> condition = pkFields.collectEntries { String pk -> [(pk): cleanSource.get(pk)] }
                EntityValue value = ec.entity.find(entityName).condition(condition).one()
                if (value != null) {
                    targetRow = [:]
                    cleanSource.keySet().each { String field -> targetRow.put(field, value.get(field)) }
                    targetByKey.put(key, targetRow)
                }
            }

            if (targetRow == null) {
                String legacyKey = pkFields.size() == 1 ? normalize(cleanSource.get(pkFields.first())) : key
                boolean releaseRecord = gitEntityMap?.containsKey(key) || gitEntityMap?.containsKey(legacyKey)
                if (releaseRecord) {
                    String originKey = "${entityName}::${legacyKey}"
                    if (entityPkOrigins?.containsKey(originKey)) {
                        cleanSource.put("_introducedBy", entityPkOrigins.get(originKey))
                    }
                    criticalMissing.add(cleanSource)
                } else {
                    environmentMissing.add(cleanSource)
                }
                continue
            }

            Map<String, Map<String, Object>> fieldDiffs = compareFields(cleanSource, targetRow, pkFields)
            if (fieldDiffs.isEmpty()) {
                matching++
            } else {
                conflicts.add([
                        primaryKey: pkFields.collectEntries { String pk -> [(pk): cleanSource.get(pk)] },
                        differences: fieldDiffs,
                        source: cleanSource,
                        target: targetRow
                ])
            }
        }

        result.matchingCount = matching
        result.targetCount = remoteTarget ? result.targetCount : targetByKey.size()
        result.criticalMissingList = criticalMissing
        result.environmentMissingList = environmentMissing
        result.conflictList = conflicts
        result.targetPrimaryKeys = targetByKey.keySet().toList()
        result.candidatePrimaryKeys = criticalMissing.collect { primaryKey(pkFields, it) }.findAll { it }
        result.syncXmlPayload = buildXmlPayload(entityName, criticalMissing)
        result.success = result.errors.isEmpty()
        result.executionTimeMs = System.currentTimeMillis() - start
        return result
    }
    /**
     * Validates that when a child entity record is missing on target, its required parent
     * is present on target or in the candidate critical missing list.
     */
    static List<String> validateDependencies(
            ExecutionContext ec, Map<String, Map<String, Object>> resultsByEntity, boolean remoteTarget) {
        List<String> errors = []
        for (Map<String, String> dependency : REQUIRED_DEPENDENCIES) {
            Map<String, Object> childResult = resultsByEntity.get(dependency.child)
            if (childResult == null || childResult.success != true) continue
            Map<String, Object> parentResult = resultsByEntity.get(dependency.parent)

            Set<String> availableKeys = [] as Set
            if (parentResult != null) {
                availableKeys.addAll((Collection<String>) (parentResult.targetPrimaryKeys ?: []))
                availableKeys.addAll((Collection<String>) (parentResult.candidatePrimaryKeys ?: []))
            }

            for (Map<String, Object> record : (List<Map<String, Object>>) childResult.criticalMissingList) {
                String parentId = normalize(record.get(dependency.field))
                if (!parentId) continue
                String parentKey = primaryKey([dependency.parentPk], [(dependency.parentPk): parentId])
                boolean found = availableKeys.contains(parentKey)
                if (!found && !remoteTarget) {
                    found = ec.entity.find(dependency.parent)
                            .condition(dependency.parentPk, parentId).one() != null
                }
                if (!found) {
                    String childKey = primaryKey((List<String>) childResult.primaryKeyFields, record)
                    errors.add("Missing required parent ${dependency.parent} [${parentId}] for " +
                            "${dependency.child} [${childKey}]")
                }
            }
        }
        return errors.unique()
    }

    static List<String> getPkFieldNames(ExecutionContext ec, String entityName) {
        def definition = ec.entity.getEntityDefinition(entityName)
        if (definition == null) throw new IllegalArgumentException("Unknown entity [${entityName}]")
        List<String> fields = (List<String>) definition.getPkFieldNames()
        if (!fields) throw new IllegalArgumentException("Entity [${entityName}] has no primary key metadata")
        return fields
    }

    static String primaryKey(List<String> pkFields, Map<String, ?> record) {
        if (!pkFields || record == null) return null
        List<String> parts = []
        for (String field : pkFields) {
            String value = normalize(record.get(field))
            if (!value) return null
            parts.add("${field.length()}:${field}${value.length()}:${value}")
        }
        return parts.join("|")
    }

    static String getPkFieldName(String entityName) {
        Map<String, String> known = [
                "moqui.basic.Enumeration": "enumId",
                "moqui.basic.EnumerationType": "enumTypeId",
                "moqui.basic.StatusItem": "statusId",
                "moqui.basic.StatusType": "statusTypeId",
                "moqui.service.message.SystemMessageType": "systemMessageTypeId",
                "moqui.basic.Uom": "uomId",
                "mantle.product.feature.ProductFeatureType": "productFeatureTypeId",
                "mantle.facility.FacilityType": "facilityTypeId"
        ]
        return known.get(entityName) ?: "id"
    }

    private static Map<String, Object> sanitizeRecord(ExecutionContext ec, String entityName, Map<String, Object> original) {
        def definition = ec.entity.getEntityDefinition(entityName)
        Map<String, Object> clean = new LinkedHashMap<>()
        original.each { String key, Object value ->
            String fieldName = key?.trim()
            if (fieldName && !AUDIT_FIELDS.contains(fieldName) && definition.isField(fieldName) && value != null) {
                clean.put(fieldName, value)
            }
        }
        return clean
    }

    private static Map<String, Map<String, Object>> compareFields(
            Map<String, Object> source, Map<String, Object> target, List<String> pkFields) {
        Map<String, Map<String, Object>> differences = [:]
        source.each { String field, Object sourceValue ->
            if (field.startsWith("_") || AUDIT_FIELDS.contains(field) || pkFields.contains(field)) return
            Object targetValue = target.get(field)
            if (normalize(sourceValue) != normalize(targetValue)) {
                differences.put(field, [source: sourceValue, target: targetValue])
            }
        }
        return differences
    }

    private static String buildXmlPayload(String entityName, List<Map<String, Object>> records) {
        StringBuilder xml = new StringBuilder()
        for (Map<String, Object> record : records) {
            xml.append("  <").append(entityName)
            record.each { String key, Object value ->
                if (!key.startsWith("_") && value != null && value.toString() != "") {
                    xml.append(" ").append(key).append('="').append(escapeXml(value.toString())).append('"')
                }
            }
            xml.append("/>\n")
        }
        return xml.toString()
    }

    private static boolean isRemoteTarget(String targetUrl) {
        return targetUrl && !"local".equalsIgnoreCase(targetUrl.trim())
    }

    private static String normalize(Object value) {
        return value == null ? "" : value.toString().trim()
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace('"', "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
