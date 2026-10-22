package co.hotwax.reconciler

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * DockerConfigReleaseResolver
 * Resolves component version shifts by analyzing changes in maarg-docker-config
 * (myaddons.xml and Dockerfiles) between two release tags directly from GitHub.
 */
class DockerConfigReleaseResolver {
    private static final Logger logger = LoggerFactory.getLogger(DockerConfigReleaseResolver.class)

    private static final Pattern COMPONENT_PATTERN = Pattern.compile(/^([+-])\s*<component\s+([^>]+)\/>/)
    private static final Pattern ATTR_PATTERN = Pattern.compile(/([a-zA-Z0-9_-]+)="([^"]*)"/)
    private static final Pattern DOCKERFILE_CLONE_PATTERN = Pattern.compile(/^([+-])\s*RUN\s+git\s+clone\s+.*-b\s+"([^"]+)"\s+https:\/\/github\.com\/([^\/]+)\/([^.]+)\.git/)

    /**
     * Represents a single component whose version changed in the release.
     */
    static class ComponentShift {
        String name
        String repo         // e.g. "hotwax/mantle-shopify-connector"
        String fromVersion
        String toVersion
        String fromTag      // e.g. "v4.4.1"
        String toTag        // e.g. "v4.4.2"
        String source       // "myaddons.xml" or "Dockerfile"

        @Override
        String toString() {
            return "${name} (${repo}): ${fromTag} -> ${toTag} [${source}]"
        }
    }

    /**
     * Resolves all components that shifted version between fromTag and toTag
     * of maarg-docker-config.
     */
    static List<ComponentShift> resolveShifts(
            String dockerConfigRepo, String fromDockerTag, String toDockerTag, String githubToken) {

        List<ComponentShift> shifts = []
        if (!fromDockerTag || !toDockerTag) return shifts

        String repo = dockerConfigRepo ?: "hotwax/hotwax-maarg-docker-config"
        logger.info("Resolving component shifts from ${repo} between ${fromDockerTag} and ${toDockerTag}")

        String token = GitDiffScanner.resolveGithubToken(githubToken)

        // 1. Fetch diff from GitHub Compare API
        BufferedReader reader = null
        HttpURLConnection conn = null

        try {
            String apiUrl = "https://api.github.com/repos/${repo}/compare/${fromDockerTag}...${toDockerTag}"
            logger.info("DockerConfigReleaseResolver: Querying GitHub Compare API: ${apiUrl}")

            conn = (HttpURLConnection) new URL(apiUrl).openConnection()
            conn.setRequestMethod("GET")
            conn.setRequestProperty("Accept", "application/vnd.github.v3.diff")
            conn.setRequestProperty("User-Agent", "Moqui-Data-Reconciler")
            if (token) {
                conn.setRequestProperty("Authorization", token.startsWith("token ") || token.startsWith("Bearer ") ? token : "token " + token)
            }
            conn.setConnectTimeout(30000)
            conn.setReadTimeout(120000)

            int code = conn.getResponseCode()
            if (code == 200) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))
                shifts = parseDiff(reader)
            } else {
                logger.warn("GitHub API returned HTTP ${code} for ${apiUrl}. Trying local fallback if available.")
                shifts = tryLocalFallback(fromDockerTag, toDockerTag)
            }
        } catch (Exception e) {
            logger.error("Error fetching docker-config diff from GitHub: ${e.message}. Trying local fallback.", e)
            shifts = tryLocalFallback(fromDockerTag, toDockerTag)
        } finally {
            if (reader != null) try { reader.close() } catch (Exception ignored) {}
            if (conn != null) conn.disconnect()
        }

        logger.info("Resolved ${shifts.size()} shifted components from ${fromDockerTag}..${toDockerTag}: ${shifts}")
        return shifts
    }

    static List<ComponentShift> parseDiff(BufferedReader reader) {
        Map<String, Map<String, String>> addonComponents = [:]
        Map<String, Map<String, String>> dockerfileComponents = [:]

        String line
        while ((line = reader.readLine()) != null) {
            // Ignore XML comments
            if (line.contains("<!--") || line.contains("-->")) continue

            // 1. Check myaddons.xml <component .../>
            Matcher mComp = COMPONENT_PATTERN.matcher(line.trim())
            if (mComp.find()) {
                String sign = mComp.group(1)
                String rawAttrs = mComp.group(2)
                Map<String, String> attrs = [:]
                Matcher mAttr = ATTR_PATTERN.matcher(rawAttrs)
                while (mAttr.find()) {
                    attrs.put(mAttr.group(1), mAttr.group(2))
                }

                String name = attrs.get("name")
                String version = attrs.get("version")
                if (name && version) {
                    Map<String, String> comp = addonComponents.computeIfAbsent(name, { k ->
                        [
                            name: name,
                            group: attrs.get("group") ?: "hotwax",
                            repoName: attrs.get("repository-name") ?: name,
                            fromVersion: null,
                            toVersion: null
                        ]
                    })
                    if (sign == "-") {
                        comp.fromVersion = version
                    } else if (sign == "+") {
                        comp.toVersion = version
                    }
                }
                continue
            }

            // 2. Check Dockerfile clone commands
            Matcher mDocker = DOCKERFILE_CLONE_PATTERN.matcher(line.trim())
            if (mDocker.find()) {
                String sign = mDocker.group(1)
                String ver = mDocker.group(2)
                String grp = mDocker.group(3)
                String repoName = mDocker.group(4)

                Map<String, String> comp = dockerfileComponents.computeIfAbsent(repoName, { k ->
                    [
                        name: repoName,
                        group: grp,
                        repoName: repoName,
                        fromVersion: null,
                        toVersion: null
                    ]
                })
                if (sign == "-") {
                    comp.fromVersion = ver
                } else if (sign == "+") {
                    comp.toVersion = ver
                }
            }
        }

        List<ComponentShift> result = []

        // Process myaddons.xml shifts
        for (Map<String, String> comp : addonComponents.values()) {
            String fromVer = comp.fromVersion
            String toVer = comp.toVersion
            if (toVer && fromVer != toVer) {
                ComponentShift shift = new ComponentShift()
                shift.name = comp.name
                shift.repo = comp.group + "/" + comp.repoName
                shift.fromVersion = fromVer
                shift.toVersion = toVer
                shift.fromTag = formatTag(fromVer)
                shift.toTag = formatTag(toVer)
                shift.source = "myaddons.xml"
                result.add(shift)
            }
        }

        // Process Dockerfile shifts (avoid duplicate if already in myaddons)
        Set<String> existingRepos = result.collect { it.repo } as Set
        for (Map<String, String> comp : dockerfileComponents.values()) {
            String fullRepo = comp.group + "/" + comp.repoName
            if (existingRepos.contains(fullRepo)) continue
            String fromVer = comp.fromVersion
            String toVer = comp.toVersion
            if (toVer && fromVer != toVer) {
                ComponentShift shift = new ComponentShift()
                shift.name = comp.name
                shift.repo = fullRepo
                shift.fromVersion = fromVer
                shift.toVersion = toVer
                shift.fromTag = formatTag(fromVer)
                shift.toTag = formatTag(toVer)
                shift.source = "Dockerfile"
                result.add(shift)
            }
        }

        return result
    }

    private static String formatTag(String version) {
        if (!version) return null
        return version.startsWith("v") ? version : "v" + version
    }

    private static List<ComponentShift> tryLocalFallback(String fromTag, String toTag) {
        File localDir = new File("runtime/component/maarg-docker-config")
        if (!localDir.exists()) {
            localDir = new File("../runtime/component/maarg-docker-config")
        }
        if (!localDir.exists() || !new File(localDir, ".git").exists()) {
            return []
        }

        try {
            logger.info("Executing local git diff fallback in ${localDir.absolutePath}")
            Process proc = ["git", "diff", "${fromTag}..${toTag}"].execute([], localDir)
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))
            List<ComponentShift> shifts = parseDiff(reader)
            reader.close()
            proc.waitFor()
            return shifts
        } catch (Exception e) {
            logger.warn("Local fallback git diff failed: ${e.message}")
            return []
        }
    }
}
