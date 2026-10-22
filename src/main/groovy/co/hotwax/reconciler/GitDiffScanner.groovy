package co.hotwax.reconciler

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Bounded Git diff scanner that reports incomplete scans instead of returning an empty successful result.
 */
class GitDiffScanner {
    private static final Logger logger = LoggerFactory.getLogger(GitDiffScanner.class)
    private static final int MAX_DIFF_BYTES = 25 * 1024 * 1024
    private static final Pattern ATTR_PATTERN =
            Pattern.compile(/([a-zA-Z0-9_-]+)\s*=\s*(["'])(.*?)\2/)

    static class DiffScanResult {
        boolean success
        Map<String, Map<String, Map<String, String>>> additions = [:]
        List<String> errors = []
        List<String> warnings = []
        Integer statusCode
    }

    static DiffScanResult scanGitHubDiffDetailed(
            String githubRepos, String githubToken, String fromTag, String toTag, List<String> targetEntities,
            String componentName = null, List<Map> codeRefs = null,
            ServiceContractScanner.ServiceDiffContext serviceContext = null) {
        DiffScanResult aggregate = emptyResult(targetEntities)
        if (!validRef(fromTag) || !validRef(toTag)) {
            aggregate.errors.add("Invalid Git reference syntax for ${fromTag}..${toTag}")
            return aggregate
        }

        List<String> repositories = githubRepos?.split(",")?.collect { it.trim() }?.findAll { it } ?: []
        if (!repositories || repositories.any { !(it ==~ /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/) }) {
            aggregate.errors.add("Invalid GitHub repository syntax")
            return aggregate
        }

        String token = resolveGithubToken(githubToken)
        for (String repository : repositories) {
            HttpURLConnection conn = null
            try {
                String encodedFrom = encodePath(fromTag)
                String encodedTo = encodePath(toTag)
                URL url = new URL("https://api.github.com/repos/${repository}/compare/${encodedFrom}...${encodedTo}")
                conn = (HttpURLConnection) url.openConnection()
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3.diff")
                conn.setRequestProperty("User-Agent", "Moqui-Data-Reconciler/2.0")
                if (token) conn.setRequestProperty("Authorization", normalizeGithubAuthorization(token))
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000

                int status = conn.responseCode
                aggregate.statusCode = status
                if (status != 200) {
                    aggregate.errors.add("GitHub compare failed for ${repository} ${fromTag}..${toTag}: HTTP ${status}")
                    continue
                }

                String diff = readBounded(conn.inputStream, MAX_DIFF_BYTES)
                DiffScanResult parsed = parseDiffText(
                        diff, targetEntities, repository, componentName, "${fromTag}->${toTag}", codeRefs, serviceContext)
                merge(aggregate, parsed)
            } catch (Exception e) {
                aggregate.errors.add("${repository}: ${e.class.simpleName}: ${e.message}")
                logger.warn("GitHub diff scan failed for ${repository}: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        aggregate.success = aggregate.errors.isEmpty()
        return aggregate
    }

    static DiffScanResult scanLocalGitDiffDetailed(
            String repoRoot, String fromTag, String toTag, List<String> targetEntities,
            String componentName = null, List<Map> codeRefs = null,
            ServiceContractScanner.ServiceDiffContext serviceContext = null) {
        DiffScanResult result = emptyResult(targetEntities)
        try {
            if (!validRef(fromTag) || !validRef(toTag)) throw new IllegalArgumentException("Invalid Git reference syntax")
            File root = new File(repoRoot).canonicalFile
            if (!root.isDirectory() || !new File(root, ".git").isDirectory()) {
                throw new IllegalArgumentException("Local repository path is not a Git repository")
            }
            Process process = new ProcessBuilder("git", "diff", "--no-ext-diff", "${fromTag}..${toTag}")
                    .directory(root).redirectErrorStream(true).start()
            String diff = readBounded(process.inputStream, MAX_DIFF_BYTES)
            int exit = process.waitFor()
            if (exit != 0) {
                result.errors.add("Local git diff failed with exit ${exit}: ${diff.take(1000)}")
                return result
            }
            result = parseDiffText(diff, targetEntities, root.path, componentName,
                    "${fromTag}->${toTag}", codeRefs, serviceContext)
            return result
        } catch (Exception e) {
            result.errors.add("${e.class.simpleName}: ${e.message}")
            return result
        }
    }

    static DiffScanResult parseDiffText(
            String diff, List<String> targetEntities, String repo = null, String componentName = null,
            String tagShift = null, List<Map> codeRefs = null,
            ServiceContractScanner.ServiceDiffContext serviceContext = null) {
        DiffScanResult result = emptyResult(targetEntities)
        String currentFile = ""
        boolean dataFile = false
        boolean codeFile = false
        boolean serviceFile = false
        StringBuilder entityTag = null

        diff.eachLine { String line ->
            if (line.startsWith("diff --git ")) {
                if (entityTag != null) {
                    result.errors.add("Incomplete added XML tag in ${currentFile}")
                    entityTag = null
                }
                currentFile = extractCurrentFile(line)
                String lower = currentFile.toLowerCase(Locale.ROOT)
                dataFile = lower.startsWith("data/") || lower.contains("/data/") ||
                        lower.startsWith("upgrade/") || lower.contains("/upgrade/") ||
                        lower.endsWith("data.xml") || lower.endsWith("seed.xml")
                serviceFile = (lower.startsWith("service/") || lower.contains("/service/")) && lower.endsWith(".xml")
                codeFile = !dataFile && !serviceFile &&
                        (lower.endsWith(".groovy") || lower.endsWith(".java") || lower.endsWith(".xml") ||
                                lower.endsWith(".js") || lower.endsWith(".ts"))
            }

            if (serviceFile && serviceContext != null) {
                try {
                    ServiceContractScanner.processLine(
                            line, currentFile, repo, componentName, tagShift, serviceContext)
                } catch (Exception e) {
                    result.errors.add("Service diff parse failed in ${currentFile}: ${e.message}")
                }
            }

            boolean added = line.startsWith("+") && !line.startsWith("+++")
            if (!added) {
                if (entityTag != null && !line.startsWith("\\ No newline")) {
                    result.errors.add("Added XML tag in ${currentFile} is split across non-added diff context")
                    entityTag = null
                }
                return
            }

            String clean = line.substring(1).trim()
            if (!clean) return

            if (dataFile) {
                if (entityTag != null) {
                    entityTag.append(" ").append(clean)
                    if (clean.contains(">")) {
                        processEntityTag(entityTag.toString(), targetEntities, result.additions)
                        entityTag = null
                    }
                } else if (startsTargetEntityTag(clean, targetEntities)) {
                    if (clean.contains(">")) {
                        processEntityTag(clean, targetEntities, result.additions)
                    } else {
                        entityTag = new StringBuilder(clean)
                    }
                }
            }

            if (codeFile && codeRefs != null) {
                CodeReferenceScanner.scanLineForReferences(
                        clean, currentFile, repo, componentName, tagShift, codeRefs)
            }
        }

        if (entityTag != null) result.errors.add("Incomplete added XML tag in ${currentFile}")
        result.success = result.errors.isEmpty()
        return result
    }

    /** Compatibility entry point. Call detailed variants when correctness matters. */
    static Map<String, Map<String, Map<String, String>>> scanDiff(
            String githubRepo, String githubToken, String repoRoot,
            String fromTag, String toTag, List<String> targetEntities) {
        DiffScanResult result = githubRepo ?
                scanGitHubDiffDetailed(githubRepo, githubToken, fromTag, toTag, targetEntities) :
                scanLocalGitDiffDetailed(repoRoot, fromTag, toTag, targetEntities)
        return result.additions
    }

    static Map<String, Map<String, Map<String, String>>> scanGitHubDiff(
            String githubRepos, String githubToken, String fromTag, String toTag, List<String> targetEntities,
            String componentName = null, List<Map> codeRefs = null,
            ServiceContractScanner.ServiceDiffContext serviceContext = null) {
        return scanGitHubDiffDetailed(githubRepos, githubToken, fromTag, toTag, targetEntities,
                componentName, codeRefs, serviceContext).additions
    }

    private static DiffScanResult emptyResult(List<String> targetEntities) {
        DiffScanResult result = new DiffScanResult()
        for (String entity : targetEntities ?: []) result.additions.put(entity, [:])
        return result
    }

    private static void merge(DiffScanResult target, DiffScanResult source) {
        source.additions.each { String entity, Map<String, Map<String, String>> records ->
            target.additions.computeIfAbsent(entity) { [:] }.putAll(records)
        }
        target.errors.addAll(source.errors)
        target.warnings.addAll(source.warnings)
    }

    private static String extractCurrentFile(String header) {
        int marker = header.indexOf(" b/")
        if (marker < 0) return header
        String path = header.substring(marker + 3).trim()
        if (path.startsWith('"') && path.endsWith('"')) path = path.substring(1, path.length() - 1)
        return path
    }

    private static boolean startsTargetEntityTag(String line, List<String> targetEntities) {
        for (String entity : targetEntities) {
            String shortName = entity.substring(entity.lastIndexOf(".") + 1)
            if (line ==~ /.*<(?:${Pattern.quote(entity)}|${Pattern.quote(shortName)})(?:\s|\/?>|$).*/) return true
        }
        return false
    }

    private static void processEntityTag(
            String tag, List<String> targetEntities,
            Map<String, Map<String, Map<String, String>>> additions) {
        for (String entity : targetEntities) {
            String shortName = entity.substring(entity.lastIndexOf(".") + 1)
            Pattern tagPattern = Pattern.compile("<(?:" + Pattern.quote(entity) + "|" + Pattern.quote(shortName) + ")(?:\\s|/?>)")
            if (!tagPattern.matcher(tag).find()) continue
            Map<String, String> attrs = parseAttributes(tag)
            String key = extractPrimaryKey(entity, attrs)
            if (key) additions.get(entity).put(key, attrs)
        }
    }

    static Map<String, String> parseAttributes(String xmlTag) {
        Map<String, String> attrs = [:]
        Matcher matcher = ATTR_PATTERN.matcher(xmlTag)
        while (matcher.find()) attrs.put(matcher.group(1), unescapeXml(matcher.group(3)))
        return attrs
    }

    static String extractPrimaryKey(String entityName, Map<String, String> attrs) {
        Map<String, String> fields = [
                "moqui.basic.Enumeration": "enumId",
                "moqui.basic.EnumerationType": "enumTypeId",
                "moqui.basic.StatusItem": "statusId",
                "moqui.basic.StatusType": "statusTypeId",
                "moqui.service.message.SystemMessageType": "systemMessageTypeId",
                "moqui.basic.Uom": "uomId",
                "mantle.product.feature.ProductFeatureType": "productFeatureTypeId",
                "mantle.facility.FacilityType": "facilityTypeId"
        ]
        String field = fields.get(entityName)
        return field ? attrs.get(field) : null
    }

    static String resolveGithubToken(String explicitToken) {
        if (explicitToken?.trim()) return explicitToken.trim()
        String envToken = System.getenv("GITHUB_TOKEN") ?: System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN")
        String propertyToken = System.getProperty("github.token")
        return envToken?.trim() ?: propertyToken?.trim()
    }

    private static boolean validRef(String ref) {
        return ref ==~ /^[A-Za-z0-9._\/-]{1,120}$/
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    private static String normalizeGithubAuthorization(String token) {
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) return "Bearer " + token.substring(7).trim()
        if (token.regionMatches(true, 0, "token ", 0, 6)) return "token " + token.substring(6).trim()
        return "Bearer " + token.trim()
    }

    private static String readBounded(InputStream stream, int maxBytes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        byte[] buffer = new byte[8192]
        int total = 0
        stream.withCloseable {
            int read
            while ((read = it.read(buffer)) >= 0) {
                total += read
                if (total > maxBytes) throw new IOException("Diff exceeds ${maxBytes} bytes")
                output.write(buffer, 0, read)
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private static String unescapeXml(String value) {
        return value.replace("&quot;", '"').replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}

