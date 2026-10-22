package co.hotwax.reconciler

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.moqui.context.ExecutionContext

/**
 * CodeReferenceScanner
 * Scans code diffs (.groovy, .java, .xml services/screens) across release shifts to detect
 * "Ghost / Orphan Enums and Statuses" — i.e. where a developer wrote code referencing an
 * enumId or statusId, but forgot to add the corresponding SeedData/UpgradeData XML,
 * and the record does NOT exist in the Moqui database.
 */
class CodeReferenceScanner {
    private static final Logger logger = LoggerFactory.getLogger(CodeReferenceScanner.class)

    private static final Set<String> EXCLUSIONS = [
        "true", "false", "null", "y", "n", "string", "integer", "long", "bigdecimal",
        "double", "date", "timestamp", "map", "list", "set", "object", "boolean",
        "create", "update", "delete", "get", "set", "find", "run", "all", "none",
        "and", "or", "not", "value", "field", "name", "type", "item", "header",
        "order", "status", "enum", "statusid", "enumid", "typeid", "purposeid"
    ] as Set

    private static final Pattern PATTERN_CONDITION_FWD =
        Pattern.compile(/(?i)\b([a-zA-Z0-9_]*?(?:enumId|statusId|typeId|purposeId|enumTypeId|statusTypeId))\s*(?:==|!=)\s*['"]([A-Za-z0-9_]{3,60})['"]/)

    private static final Pattern PATTERN_CONDITION_REV =
        Pattern.compile(/(?i)['"]([A-Za-z0-9_]{3,60})['"]\s*(?:==|!=)\s*\b([a-zA-Z0-9_]*?(?:enumId|statusId|typeId|purposeId|enumTypeId|statusTypeId))\b/)

    private static final Pattern PATTERN_ASSIGNMENT =
        Pattern.compile(/(?i)\b([a-zA-Z0-9_]*?(?:enumId|statusId|typeId|purposeId|enumTypeId|statusTypeId))\s*[:=]\s*['"]([A-Za-z0-9_]{3,60})['"]/)

    private static final Pattern PATTERN_XML_SET_1 =
        Pattern.compile(/(?i)<set\s+[^>]*field=['"][^'"]*?(?:enumId|statusId|typeId|purposeId)['"][^>]*value=['"]([A-Za-z0-9_]{3,60})['"]/)

    private static final Pattern PATTERN_XML_SET_2 =
        Pattern.compile(/(?i)<set\s+[^>]*value=['"]([A-Za-z0-9_]{3,60})['"][^>]*field=['"][^'"]*?(?:enumId|statusId|typeId|purposeId)['"]/)

    private static final Pattern PATTERN_XML_ECONDITION_1 =
        Pattern.compile(/(?i)<econdition\s+[^>]*field-name=['"][^'"]*?(?:enumId|statusId|typeId|purposeId)['"][^>]*value=['"]([A-Za-z0-9_]{3,60})['"]/)

    private static final Pattern PATTERN_XML_ECONDITION_2 =
        Pattern.compile(/(?i)<econdition\s+[^>]*value=['"]([A-Za-z0-9_]{3,60})['"][^>]*field-name=['"][^'"]*?(?:enumId|statusId|typeId|purposeId)['"]/)

    private static final Pattern PATTERN_XML_COMPARE =
        Pattern.compile(/(?i)<compare\s+[^>]*field=['"][^'"]*?(?:enumId|statusId|typeId|purposeId)['"][^>]*value=['"]([A-Za-z0-9_]{3,60})['"]/)

    private static final Pattern PATTERN_API_CALL =
        Pattern.compile(/(?i)\.(?:condition|set)\s*\(\s*['"]([a-zA-Z0-9_]*?(?:enumId|statusId|typeId|purposeId))['"]\s*,\s*['"]([A-Za-z0-9_]{3,60})['"]\s*\)/)

    private static final Pattern PATTERN_IN_LIST =
        Pattern.compile(/(?i)\b([a-zA-Z0-9_]*?(?:enumId|statusId|typeId|purposeId))\b\s+in\s+\[([^\]]+)\]/)

    private static final Pattern TOKEN_EXTRACTOR = Pattern.compile(/['"]([A-Za-z0-9_]{3,60})['"]/)

    /**
     * Inspects a single added code line from a git diff and collects candidate enum/status references.
     */
    static void scanLineForReferences(
            String line, String filePath, String repo, String component, String tagShift, List<Map> codeRefs) {

        if (!line || !codeRefs) return

        // 1. Condition: field == 'ID'
        Matcher m1 = PATTERN_CONDITION_FWD.matcher(line)
        while (m1.find()) {
            addCandidate(m1.group(1), m1.group(2), line, filePath, repo, component, tagShift, codeRefs)
        }

        // 2. Condition reverse: 'ID' == field
        Matcher m2 = PATTERN_CONDITION_REV.matcher(line)
        while (m2.find()) {
            addCandidate(m2.group(2), m2.group(1), line, filePath, repo, component, tagShift, codeRefs)
        }

        // 3. Assignment / Map: field: 'ID' or field = 'ID'
        Matcher m3 = PATTERN_ASSIGNMENT.matcher(line)
        while (m3.find()) {
            addCandidate(m3.group(1), m3.group(2), line, filePath, repo, component, tagShift, codeRefs)
        }

        // 4. XML <set .../>
        Matcher m4a = PATTERN_XML_SET_1.matcher(line)
        while (m4a.find()) {
            addCandidate("setField", m4a.group(1), line, filePath, repo, component, tagShift, codeRefs)
        }
        Matcher m4b = PATTERN_XML_SET_2.matcher(line)
        while (m4b.find()) {
            addCandidate("setField", m4b.group(1), line, filePath, repo, component, tagShift, codeRefs)
        }

        // 5. XML <econdition .../>
        Matcher m5a = PATTERN_XML_ECONDITION_1.matcher(line)
        while (m5a.find()) {
            addCandidate("econdition", m5a.group(1), line, filePath, repo, component, tagShift, codeRefs)
        }
        Matcher m5b = PATTERN_XML_ECONDITION_2.matcher(line)
        while (m5b.find()) {
            addCandidate("econdition", m5b.group(1), line, filePath, repo, component, tagShift, codeRefs)
        }

        // 6. XML <compare .../>
        Matcher m6 = PATTERN_XML_COMPARE.matcher(line)
        while (m6.find()) {
            addCandidate("compare", m6.group(1), line, filePath, repo, component, tagShift, codeRefs)
        }

        // 7. Moqui API condition/set calls
        Matcher m7 = PATTERN_API_CALL.matcher(line)
        while (m7.find()) {
            addCandidate(m7.group(1), m7.group(2), line, filePath, repo, component, tagShift, codeRefs)
        }

        // 8. in list: field in ['ID1', 'ID2']
        Matcher m8 = PATTERN_IN_LIST.matcher(line)
        while (m8.find()) {
            String field = m8.group(1)
            String listContent = m8.group(2)
            Matcher mTokens = TOKEN_EXTRACTOR.matcher(listContent)
            while (mTokens.find()) {
                addCandidate(field, mTokens.group(1), line, filePath, repo, component, tagShift, codeRefs)
            }
        }
    }

    private static void addCandidate(
            String fieldName, String candidateId, String line, String filePath,
            String repo, String component, String tagShift, List<Map> codeRefs) {

        if (!isValidCandidate(candidateId)) return

        String probableEntity = resolveProbableEntity(fieldName, candidateId)

        codeRefs.add([
            candidateId: candidateId,
            fieldName: fieldName,
            probableEntity: probableEntity,
            filePath: filePath,
            repo: repo,
            component: component,
            tagShift: tagShift,
            codeSnippet: line.trim()
        ])
    }

    private static boolean isValidCandidate(String id) {
        if (!id || id.length() < 3 || id.length() > 60) return false
        if (EXCLUSIONS.contains(id.toLowerCase())) return false
        // Must contain at least one uppercase letter (Moqui enum/status IDs are uppercase or camelCase)
        if (!id.matches(/.*[A-Z].*/)) return false
        // Must not be purely digits or punctuation
        if (id.matches(/^\d+$/)) return false
        return true
    }

    private static String resolveProbableEntity(String fieldName, String id) {
        String lowerField = (fieldName ?: "").toLowerCase()
        if (lowerField.contains("status")) {
            return "moqui.basic.StatusItem"
        }
        return "moqui.basic.Enumeration"
    }

    /**
     * Analyzes candidate code references against:
     * 1. Release Git XML additions (allGitAdditions)
     * 2. Local Moqui database (ec.entity)
     * 3. Reference instance (remote existing records)
     *
     * Returns a list of OmissionAlert maps for any candidate missing from both release XML data and local DB.
     */
    static List<Map<String, Object>> verifyOmissions(
            List<Map> rawCodeRefs,
            Map<String, Map<String, Map<String, String>>> allGitAdditions,
            ExecutionContext ec,
            String referenceUrl = null) {

        List<Map<String, Object>> alerts = []
        if (!rawCodeRefs) return alerts

        Set<String> processedKeys = new HashSet<>()

        for (Map ref : rawCodeRefs) {
            String candidateId = (String) ref.candidateId
            String probableEntity = (String) ref.probableEntity ?: "moqui.basic.Enumeration"
            String key = "${probableEntity}::${candidateId}"

            if (processedKeys.contains(key)) continue
            processedKeys.add(key)

            // 1. Check if defined in any Git XML file added in this release
            boolean inGitAdditions = false
            for (String entity : allGitAdditions.keySet()) {
                if (allGitAdditions[entity]?.containsKey(candidateId)) {
                    inGitAdditions = true
                    break
                }
            }
            if (inGitAdditions) continue // Properly defined in UpgradeData / SeedData!

            // 2. Check if present in the local Moqui DB
            boolean inLocalDb = checkLocalDbExists(ec, candidateId, probableEntity)
            if (inLocalDb) continue // Already exists in local database!

            // 3. Omission found! Formulate diagnosis and generated XML stub
            String severity = "CRITICAL"
            String diagnosis = "Ghost Reference: Code condition or assignment references '${candidateId}', but it does NOT exist in Seed/Upgrade Data or the database. Code execution may fail or behave incorrectly!"

            String suggestedXml
            if (probableEntity.contains("Status")) {
                suggestedXml = "<moqui.basic.StatusItem statusId=\"${candidateId}\" description=\"Auto-detected from code: ${candidateId}\" statusTypeId=\"ORDER_ITEM_STATUS\"/>"
            } else {
                suggestedXml = "<moqui.basic.Enumeration enumId=\"${candidateId}\" description=\"Auto-detected from code: ${candidateId}\" enumTypeId=\"AutoEnum\"/>"
            }

            alerts.add([
                candidateId: candidateId,
                probableEntity: probableEntity,
                fieldName: ref.fieldName,
                severity: severity,
                diagnosis: diagnosis,
                component: ref.component ?: "Unknown Component",
                repo: ref.repo ?: "Unknown Repo",
                filePath: ref.filePath ?: "Unknown File",
                codeSnippet: ref.codeSnippet ?: "",
                tagShift: ref.tagShift ?: "",
                suggestedXml: suggestedXml
            ])
        }

        return alerts
    }

    private static boolean checkLocalDbExists(ExecutionContext ec, String id, String probableEntity) {
        if (!ec || !id) return false
        try {
            if (probableEntity.contains("Status")) {
                if (ec.entity.find("moqui.basic.StatusItem").condition("statusId", id).count() > 0) return true
                if (ec.entity.find("moqui.basic.StatusType").condition("statusTypeId", id).count() > 0) return true
            } else {
                if (ec.entity.find("moqui.basic.Enumeration").condition("enumId", id).count() > 0) return true
                if (ec.entity.find("moqui.basic.EnumerationType").condition("enumTypeId", id).count() > 0) return true
            }
            // Cross-entity fallback
            if (ec.entity.find("moqui.basic.Enumeration").condition("enumId", id).count() > 0) return true
            if (ec.entity.find("moqui.basic.StatusItem").condition("statusId", id).count() > 0) return true
            if (ec.entity.find("moqui.basic.EnumerationType").condition("enumTypeId", id).count() > 0) return true
            if (ec.entity.find("moqui.basic.StatusType").condition("statusTypeId", id).count() > 0) return true
        } catch (Exception e) {
            logger.debug("Error checking DB for ${id}: ${e.message}")
        }
        return false
    }
}
