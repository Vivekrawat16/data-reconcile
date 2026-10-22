package co.hotwax.reconciler

import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Finds added enumId/statusId references and verifies them against release data and the local target database.
 */
class CodeReferenceScanner {
    private static final Logger logger = LoggerFactory.getLogger(CodeReferenceScanner.class)
    private static final Set<String> EXCLUSIONS = [
            "true", "false", "null", "statusid", "enumid", "enumtypeid", "statustypeid"
    ] as Set
    private static final String FIELD = /[A-Za-z0-9_]*?(?:enumId|statusId|enumTypeId|statusTypeId)/
    private static final String VALUE = /[A-Za-z][A-Za-z0-9_]{2,59}/

    private static final Pattern CONDITION_FORWARD =
            Pattern.compile(/(?i)\b(${FIELD})\s*(?:==|!=)\s*['"](${VALUE})['"]/)
    private static final Pattern CONDITION_REVERSE =
            Pattern.compile(/(?i)['"](${VALUE})['"]\s*(?:==|!=)\s*\b(${FIELD})\b/)
    private static final Pattern ASSIGNMENT =
            Pattern.compile(/(?i)\b(${FIELD})\s*[:=]\s*['"](${VALUE})['"]/)
    private static final Pattern API_CALL =
            Pattern.compile(/(?i)\.(?:condition|set)\s*\(\s*['"](${FIELD})['"]\s*,\s*['"](${VALUE})['"]\s*\)/)
    private static final Pattern IN_LIST =
            Pattern.compile(/(?i)\b(${FIELD})\b\s+in\s+\[([^\]]+)\]/)
    private static final Pattern TOKEN = Pattern.compile(/['"](${VALUE})['"]/)

    static void scanLineForReferences(
            String line, String filePath, String repo, String component, String tagShift,
            List<Map> codeReferences) {
        if (!line || codeReferences == null) return
        collectMatches(CONDITION_FORWARD, line, 1, 2, filePath, repo, component, tagShift, codeReferences)
        collectMatches(CONDITION_REVERSE, line, 2, 1, filePath, repo, component, tagShift, codeReferences)
        collectMatches(ASSIGNMENT, line, 1, 2, filePath, repo, component, tagShift, codeReferences)
        collectMatches(API_CALL, line, 1, 2, filePath, repo, component, tagShift, codeReferences)

        Matcher listMatcher = IN_LIST.matcher(line)
        while (listMatcher.find()) {
            Matcher tokenMatcher = TOKEN.matcher(listMatcher.group(2))
            while (tokenMatcher.find()) {
                addCandidate(listMatcher.group(1), tokenMatcher.group(1), line, filePath,
                        repo, component, tagShift, codeReferences)
            }
        }
    }

    static List<Map<String, Object>> verifyOmissions(
            List<Map> rawCodeReferences,
            Map<String, Map<String, Map<String, String>>> allGitAdditions,
            ExecutionContext ec,
            String ignoredReferenceUrl = null) {
        List<Map<String, Object>> alerts = []
        Set<String> processed = []
        for (Map reference : rawCodeReferences ?: []) {
            String candidate = reference.candidateId as String
            String entity = reference.probableEntity as String
            String key = "${entity}::${candidate}"
            if (!processed.add(key)) continue
            if (allGitAdditions.values().any { it?.containsKey(candidate) }) continue

            Boolean exists = localRecordExists(ec, candidate, entity)
            if (exists == null) {
                logger.warn("Omission check skipped for ${candidate} because the database lookup failed")
                continue
            }
            if (exists) continue

            alerts.add([
                    candidateId: candidate,
                    probableEntity: entity,
                    fieldName: reference.fieldName,
                    severity: "HIGH",
                    diagnosis: "Code references [${candidate}], but it is absent from release data and the local target database",
                    component: reference.component ?: "Unknown Component",
                    repo: reference.repo ?: "Unknown Repo",
                    filePath: reference.filePath ?: "Unknown File",
                    codeSnippet: reference.codeSnippet ?: "",
                    tagShift: reference.tagShift ?: "",
                    suggestedXml: "",
                    requiredAction: "Confirm the owning enum/status type and add authoritative seed or upgrade data"
            ])
        }
        return alerts
    }

    private static void collectMatches(
            Pattern pattern, String line, int fieldGroup, int valueGroup,
            String filePath, String repo, String component, String tagShift, List<Map> output) {
        Matcher matcher = pattern.matcher(line)
        while (matcher.find()) {
            addCandidate(matcher.group(fieldGroup), matcher.group(valueGroup), line, filePath,
                    repo, component, tagShift, output)
        }
    }

    private static void addCandidate(
            String fieldName, String candidate, String line, String filePath,
            String repo, String component, String tagShift, List<Map> output) {
        if (!candidate || EXCLUSIONS.contains(candidate.toLowerCase(Locale.ROOT))) return
        if (!(candidate ==~ /.*[A-Z].*/)) return
        String entity = fieldName.toLowerCase(Locale.ROOT).contains("status") ?
                "moqui.basic.StatusItem" : "moqui.basic.Enumeration"
        output.add([
                candidateId: candidate, fieldName: fieldName, probableEntity: entity,
                filePath: filePath, repo: repo, component: component,
                tagShift: tagShift, codeSnippet: line.trim()
        ])
    }

    private static Boolean localRecordExists(
            ExecutionContext ec, String id, String probableEntity) {
        try {
            if (probableEntity == "moqui.basic.StatusItem") {
                return ec.entity.find("moqui.basic.StatusItem").condition("statusId", id).count() > 0 ||
                        ec.entity.find("moqui.basic.StatusType").condition("statusTypeId", id).count() > 0
            }
            return ec.entity.find("moqui.basic.Enumeration").condition("enumId", id).count() > 0 ||
                    ec.entity.find("moqui.basic.EnumerationType").condition("enumTypeId", id).count() > 0
        } catch (Exception e) {
            logger.warn("Database lookup failed while checking code reference ${id}: ${e.message}")
            return null
        }
    }
}
