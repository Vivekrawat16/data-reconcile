package co.hotwax.reconciler

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * GitDiffScanner
 * Fetches release diffs between two tags directly from GitHub REST API
 * (or optional local git fallback), extracts newly added entity XML definitions,
 * and catalogs the delta of metadata introduced by the release.
 */
class GitDiffScanner {
    private static final Logger logger = LoggerFactory.getLogger(GitDiffScanner.class)

    private static final Pattern ATTR_PATTERN = Pattern.compile(/([a-zA-Z0-9_]+)="([^"]*)"/)

    /**
     * Primary entry point: Scans GitHub API for tag diffs, or falls back to local git.
     */
    static Map<String, Map<String, Map<String, String>>> scanDiff(
            String githubRepo, String githubToken, String repoRoot,
            String fromTag, String toTag, List<String> targetEntities) {

        if (githubRepo) {
            return scanGitHubDiff(githubRepo, githubToken, fromTag, toTag, targetEntities)
        } else if (repoRoot) {
            return scanLocalGitDiff(repoRoot, fromTag, toTag, targetEntities)
        } else {
            logger.warn("Neither githubRepo nor repoRoot specified for GitDiffScanner.")
            Map<String, Map<String, Map<String, String>>> result = [:]
            for (String entity : targetEntities) result[entity] = [:]
            return result
        }
    }

    /**
     * Fetches diff directly from GitHub REST API:
     * GET https://api.github.com/repos/{owner}/{repo}/compare/{fromTag}...{toTag}
     * with header 'Accept: application/vnd.github.v3.diff'
     */
    static Map<String, Map<String, Map<String, String>>> scanGitHubDiff(
            String githubRepos, String githubToken, String fromTag, String toTag, List<String> targetEntities,
            String compName = null, List<Map> codeRefs = null, ServiceContractScanner.ServiceDiffContext serviceCtx = null) {

        Map<String, Map<String, Map<String, String>>> result = [:]
        for (String entity : targetEntities) result[entity] = [:]

        if (!fromTag || !toTag || !githubRepos) {
            logger.warn("fromTag, toTag, or githubRepo not provided for GitHub diff scan.")
            return result
        }

        String token = resolveGithubToken(githubToken)

        // Support multiple comma-separated repos, e.g. "hotwax/oms,hotwax/mantle-shopify-connector"
        List<String> repoList = githubRepos.split(",").collect { it.trim() }.findAll { !it.isEmpty() }

        for (String repo : repoList) {
            try {
                String apiUrl = "https://api.github.com/repos/${repo}/compare/${fromTag}...${toTag}"
                logger.info("GitDiffScanner: Querying GitHub Compare API: ${apiUrl}")

                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection()
                conn.setRequestMethod("GET")
                conn.setRequestProperty("Accept", "application/vnd.github.v3.diff")
                conn.setRequestProperty("User-Agent", "Moqui-Data-Reconciler")
                if (token) {
                    if (token.startsWith("token ") || token.startsWith("Bearer ")) {
                        conn.setRequestProperty("Authorization", token)
                    } else {
                        conn.setRequestProperty("Authorization", "token " + token)
                    }
                }
                conn.setConnectTimeout(30000)
                conn.setReadTimeout(120000)

                int responseCode = conn.getResponseCode()
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))
                    parseDiffStream(reader, targetEntities, result, repo, compName, "${fromTag}➔${toTag}", codeRefs, serviceCtx)
                    reader.close()
                    logger.info("GitDiffScanner: Successfully parsed GitHub diff from ${repo} for ${fromTag}..${toTag}")
                } else if (responseCode == 404) {
                    logger.warn("GitDiffScanner: Repository or tags not found on GitHub (${responseCode}): ${apiUrl}")
                } else {
                    logger.warn("GitDiffScanner: GitHub API returned HTTP ${responseCode} for ${apiUrl}")
                }
            } catch (Exception e) {
                logger.error("Error fetching GitHub diff for ${repo}: ${e.message}", e)
            }
        }

        return result
    }

    /**
     * Local git diff execution fallback.
     */
    static Map<String, Map<String, Map<String, String>>> scanLocalGitDiff(
            String repoRoot, String fromTag, String toTag, List<String> targetEntities,
            String compName = null, List<Map> codeRefs = null, ServiceContractScanner.ServiceDiffContext serviceCtx = null) {

        Map<String, Map<String, Map<String, String>>> result = [:]
        for (String entity : targetEntities) result[entity] = [:]

        File rootDir = new File(repoRoot)
        if (!rootDir.exists() || !rootDir.isDirectory()) return result

        try {
            List<String> command = ["git", "diff", "${fromTag}..${toTag}"]
            ProcessBuilder pb = new ProcessBuilder(command)
            pb.directory(rootDir)
            pb.redirectErrorStream(true)
            Process proc = pb.start()

            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.inputStream))
            parseDiffStream(reader, targetEntities, result, repoRoot, compName, "${fromTag}➔${toTag}", codeRefs, serviceCtx)
            reader.close()
            proc.waitFor()
        } catch (Exception e) {
            logger.error("Error executing local git diff in ${repoRoot}: ${e.message}", e)
        }

        return result
    }

    private static void parseDiffStream(
            BufferedReader reader, List<String> targetEntities, Map<String, Map<String, Map<String, String>>> result,
            String repo = null, String compName = null, String tagShift = null, List<Map> codeRefs = null,
            ServiceContractScanner.ServiceDiffContext serviceCtx = null) {

        String line
        String currentFile = ""
        boolean inDataXml = false
        boolean inCodeFile = false
        boolean isServiceXml = false

        while ((line = reader.readLine()) != null) {
            // Check file header to identify current file path and type
            if (line.startsWith("diff --git a/")) {
                String[] parts = line.split(" ")
                if (parts.length >= 4) {
                    currentFile = parts[3].startsWith("b/") ? parts[3].substring(2) : parts[3]
                } else {
                    currentFile = line
                }
                String lower = currentFile.toLowerCase()
                inDataXml = lower.contains("/data/") || lower.contains("/upgrade/") || lower.endsWith("data.xml")
                isServiceXml = lower.contains("/service/") && lower.endsWith(".xml")
                inCodeFile = !inDataXml && !isServiceXml && (lower.endsWith(".groovy") || lower.endsWith(".java") || lower.endsWith(".xml") || lower.endsWith(".js") || lower.endsWith(".ts"))
            }

            // 1. Service Contract & Breaking Change Scanner
            if (isServiceXml && serviceCtx != null) {
                try {
                    ServiceContractScanner.processLine(line, currentFile, repo, compName, tagShift, serviceCtx)
                } catch (Exception e) {
                    logger.debug("Error in ServiceContractScanner: ${e.message}")
                }
            }

            // Lines added in the diff (starting with + but not ++)
            if (line.startsWith("+") && !line.startsWith("++")) {
                String cleanLine = line.substring(1).trim()
                if (cleanLine.isEmpty()) continue

                // 2. Data XML entity tag parsing
                if (inDataXml) {
                    for (String entity : targetEntities) {
                        String shortName = entity.contains(".") ? entity.substring(entity.lastIndexOf('.') + 1) : entity
                        if (cleanLine.contains("<${entity}") || cleanLine.contains("<${shortName}")) {
                            Map<String, String> attrs = parseAttributes(cleanLine)
                            String pk = extractPrimaryKey(entity, attrs)
                            if (pk) {
                                result[entity][pk] = attrs
                            }
                        }
                    }
                }

                // 3. Code file scanning for ghost/orphan enum & status references
                if (inCodeFile && codeRefs != null) {
                    try {
                        CodeReferenceScanner.scanLineForReferences(cleanLine, currentFile, repo, compName, tagShift, codeRefs)
                    } catch (Exception e) {
                        logger.debug("Error scanning code line: ${e.message}")
                    }
                }
            }
        }
    }

    private static Map<String, String> parseAttributes(String xmlTag) {
        Map<String, String> attrs = [:]
        Matcher m = ATTR_PATTERN.matcher(xmlTag)
        while (m.find()) {
            attrs[m.group(1)] = m.group(2)
        }
        return attrs
    }

    private static String extractPrimaryKey(String entityName, Map<String, String> attrs) {
        if (entityName.endsWith("Enumeration")) return attrs.get("enumId")
        if (entityName.endsWith("EnumerationType")) return attrs.get("enumTypeId")
        if (entityName.endsWith("StatusItem")) return attrs.get("statusId")
        if (entityName.endsWith("StatusType")) return attrs.get("statusTypeId")
        if (entityName.endsWith("SystemMessageType")) return attrs.get("systemMessageTypeId")
        if (entityName.endsWith("Uom")) return attrs.get("uomId")
        if (entityName.endsWith("ProductFeatureType")) return attrs.get("productFeatureTypeId")
        if (entityName.endsWith("FacilityType")) return attrs.get("facilityTypeId")
        for (String k : attrs.keySet()) {
            if (k.toLowerCase().endsWith("id")) return attrs.get(k)
        }
        return attrs.values() ? attrs.values().iterator().next() : null
    }

    static String resolveGithubToken(String explicitToken) {
        if (explicitToken?.trim()) return explicitToken.trim()

        String envToken = System.getenv("GITHUB_TOKEN") ?: System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN") ?: System.getProperty("github.token")
        if (envToken?.trim()) return envToken.trim()

        // 1. Check ~/.git-credentials
        try {
            File gitCreds = new File(System.getProperty("user.home") + "/.git-credentials")
            if (gitCreds.exists()) {
                for (String line : gitCreds.readLines()) {
                    Matcher m = Pattern.compile(/https:\/\/[^:]+:(ghp_[a-zA-Z0-9_]+)@github\.com/).matcher(line)
                    if (m.find()) return m.group(1)
                    Matcher m2 = Pattern.compile(/https:\/\/[^:]+:([^@]+)@github\.com/).matcher(line)
                    if (m2.find() && m2.group(1).startsWith("gh")) return m2.group(1)
                }
            }
        } catch (Exception e) {
            logger.debug("Could not read ~/.git-credentials: ${e.message}")
        }

        // 2. Check mcp_config.json
        try {
            File mcpConfig = new File(System.getProperty("user.home") + "/.gemini/config/mcp_config.json")
            if (mcpConfig.exists()) {
                def json = new groovy.json.JsonSlurper().parse(mcpConfig)
                String mcpToken = json?.mcpServers?.github?.env?.GITHUB_PERSONAL_ACCESS_TOKEN
                if (mcpToken?.trim()) return mcpToken.trim()
            }
        } catch (Exception e) {
            logger.debug("Could not read mcp_config.json for github token: ${e.message}")
        }

        return null
    }
}

