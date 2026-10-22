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
 * Resolves component version shifts from a docker-config compare without treating failures as an empty release.
 */
class DockerConfigReleaseResolver {
    private static final Logger logger = LoggerFactory.getLogger(DockerConfigReleaseResolver.class)
    private static final int MAX_DIFF_BYTES = 25 * 1024 * 1024
    private static final Pattern COMPONENT_PATTERN = Pattern.compile(/^([+-])\s*<component\s+([^>]+)\/>/)
    private static final Pattern ATTR_PATTERN = Pattern.compile(/([a-zA-Z0-9_-]+)=["']([^"']*)["']/)
    private static final Pattern DOCKERFILE_CLONE_PATTERN = Pattern.compile(
            /^([+-])\s*RUN\s+git\s+clone\s+.*?(?:-b|--branch)\s+["']?([^"'\s]+)["']?\s+https:\/\/github\.com\/([^\/\s]+)\/([^\s]+?)(?:\.git)?(?:\s|$)/)

    static final Set<String> EXCLUDED_COMPONENTS = [
            "moqui-framework", "moqui-runtime", "hotwax-maarg-docker-config", "maarg-docker-config",
            "hotwax-maargsetup", "maargsetup"
    ] as Set

    static class ComponentShift {
        String name
        String repo
        String fromVersion
        String toVersion
        String fromTag
        String toTag
        String source

        String toString() { "${name} (${repo}): ${fromTag} -> ${toTag} [${source}]" }
    }

    static class ResolutionResult {
        boolean success
        List<ComponentShift> shifts = []
        List<String> errors = []
        List<String> warnings = []
        Integer statusCode
    }

    static boolean isExcluded(String name, String repoName) {
        String n = (name ?: "").toLowerCase(Locale.ROOT).trim()
        String r = (repoName ?: "").toLowerCase(Locale.ROOT).trim()
        return EXCLUDED_COMPONENTS.contains(n) || EXCLUDED_COMPONENTS.contains(r) ||
                n.contains("moqui-framework") || r.contains("moqui-framework") ||
                n.contains("moqui-runtime") || r.contains("moqui-runtime") ||
                n.contains("docker-config") || r.contains("docker-config") ||
                n.contains("maargsetup") || r.contains("maargsetup")
    }

    static ResolutionResult resolveShiftsDetailed(
            String dockerConfigRepo, String fromDockerTag, String toDockerTag, String githubToken,
            String localRepoPath = null) {
        ResolutionResult result = new ResolutionResult()
        if (!validRepo(dockerConfigRepo) || !validRef(fromDockerTag) || !validRef(toDockerTag)) {
            result.errors.add("Invalid docker-config repository or release tag syntax")
            return result
        }

        HttpURLConnection conn = null
        try {
            String encodedFrom = encodePath(fromDockerTag)
            String encodedTo = encodePath(toDockerTag)
            URL url = new URL("https://api.github.com/repos/${dockerConfigRepo}/compare/${encodedFrom}...${encodedTo}")
            conn = (HttpURLConnection) url.openConnection()
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github.v3.diff")
            conn.setRequestProperty("User-Agent", "Moqui-Data-Reconciler/2.0")
            String token = GitDiffScanner.resolveGithubToken(githubToken)
            if (token) conn.setRequestProperty("Authorization", normalizeGithubAuthorization(token))
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000

            int status = conn.responseCode
            result.statusCode = status
            if (status != 200) {
                if (localRepoPath) return resolveLocal(localRepoPath, fromDockerTag, toDockerTag)
                result.errors.add("GitHub compare failed for ${dockerConfigRepo}: HTTP ${status}")
                return result
            }

            String diff = readBounded(conn.inputStream, MAX_DIFF_BYTES)
            result.shifts = parseDiff(new BufferedReader(new StringReader(diff)))
            List<ComponentShift> incomplete = result.shifts.findAll { !it.fromTag || !it.toTag }
            if (incomplete) {
                result.warnings.add("New components require an explicit baseline and were not scanned: " +
                        incomplete.collect { it.name }.join(", "))
                result.shifts = result.shifts.findAll { it.fromTag && it.toTag }
            }
            result.success = true
            return result
        } catch (Exception e) {
            if (localRepoPath) return resolveLocal(localRepoPath, fromDockerTag, toDockerTag)
            result.errors.add("${e.class.simpleName}: ${e.message}")
            logger.warn("Docker config resolution failed: ${e.message}")
            return result
        } finally {
            conn?.disconnect()
        }
    }

    /** Compatibility wrapper. */
    static List<ComponentShift> resolveShifts(
            String dockerConfigRepo, String fromDockerTag, String toDockerTag, String githubToken) {
        ResolutionResult result = resolveShiftsDetailed(dockerConfigRepo, fromDockerTag, toDockerTag, githubToken)
        return result.success ? result.shifts : []
    }

    static List<ComponentShift> parseDiff(BufferedReader reader) {
        Map<String, Map<String, String>> addonComponents = [:]
        Map<String, Map<String, String>> dockerfileComponents = [:]
        boolean inComment = false
        String line
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim()
            if (trimmed.contains("<!--")) inComment = true
            if (!inComment) {
                Matcher componentMatcher = COMPONENT_PATTERN.matcher(trimmed)
                if (componentMatcher.find()) {
                    String sign = componentMatcher.group(1)
                    Map<String, String> attrs = parseAttributes(componentMatcher.group(2))
                    String name = attrs.name
                    String version = attrs.version
                    if (name && version) {
                        Map<String, String> component = addonComponents.computeIfAbsent(name) {
                            [name: name, group: attrs.group ?: "hotwax",
                             repoName: attrs.get("repository-name") ?: name,
                             fromVersion: null, toVersion: null]
                        }
                        if (sign == "-") component.fromVersion = version
                        if (sign == "+") component.toVersion = version
                    }
                } else {
                    Matcher dockerMatcher = DOCKERFILE_CLONE_PATTERN.matcher(trimmed)
                    if (dockerMatcher.find()) {
                        String sign = dockerMatcher.group(1)
                        String repoName = dockerMatcher.group(4).replaceAll(/\.git$/, "")
                        Map<String, String> component = dockerfileComponents.computeIfAbsent(repoName) {
                            [name: repoName, group: dockerMatcher.group(3), repoName: repoName,
                             fromVersion: null, toVersion: null]
                        }
                        if (sign == "-") component.fromVersion = dockerMatcher.group(2)
                        if (sign == "+") component.toVersion = dockerMatcher.group(2)
                    }
                }
            }
            if (trimmed.contains("-->")) inComment = false
        }

        List<ComponentShift> shifts = []
        appendShifts(addonComponents.values(), "myaddons.xml", shifts)
        Set<String> existing = shifts.collect { it.repo } as Set
        List<ComponentShift> dockerShifts = []
        appendShifts(dockerfileComponents.values(), "Dockerfile", dockerShifts)
        shifts.addAll(dockerShifts.findAll { !existing.contains(it.repo) })
        return shifts
    }

    private static void appendShifts(Collection<Map<String, String>> components, String source,
                                     List<ComponentShift> destination) {
        for (Map<String, String> component : components) {
            if (isExcluded(component.name, component.repoName)) continue
            if (!component.toVersion || component.fromVersion == component.toVersion) continue
            destination.add(new ComponentShift(
                    name: component.name,
                    repo: component.group + "/" + component.repoName,
                    fromVersion: component.fromVersion,
                    toVersion: component.toVersion,
                    fromTag: formatTag(component.fromVersion),
                    toTag: formatTag(component.toVersion),
                    source: source
            ))
        }
    }

    private static ResolutionResult resolveLocal(String repoPath, String fromTag, String toTag) {
        ResolutionResult result = new ResolutionResult()
        File root = new File(repoPath)
        try {
            File canonical = root.canonicalFile
            if (!canonical.isDirectory() || !new File(canonical, ".git").isDirectory()) {
                result.errors.add("Local docker-config path is not a Git repository")
                return result
            }
            Process process = new ProcessBuilder("git", "diff", "--no-ext-diff", "${fromTag}..${toTag}")
                    .directory(canonical).redirectErrorStream(true).start()
            String diff = readBounded(process.inputStream, MAX_DIFF_BYTES)
            int exit = process.waitFor()
            if (exit != 0) {
                result.errors.add("Local docker-config git diff failed with exit ${exit}: ${diff.take(1000)}")
                return result
            }
            result.shifts = parseDiff(new BufferedReader(new StringReader(diff)))
            List<ComponentShift> incomplete = result.shifts.findAll { !it.fromTag || !it.toTag }
            if (incomplete) {
                result.warnings.add("Local diff contains components without a baseline version: " +
                        incomplete.collect { it.name }.join(", "))
                result.shifts = result.shifts.findAll { it.fromTag && it.toTag }
            }
            result.success = true
            return result
        } catch (Exception e) {
            result.errors.add("${e.class.simpleName}: ${e.message}")
            return result
        }
    }

    private static Map<String, String> parseAttributes(String text) {
        Map<String, String> attrs = [:]
        Matcher matcher = ATTR_PATTERN.matcher(text)
        while (matcher.find()) attrs.put(matcher.group(1), matcher.group(2))
        return attrs
    }

    private static String formatTag(String version) {
        if (!version) return null
        if (version.startsWith("v") || !(version ==~ /^\d+(?:\.\d+)*(?:[-+].+)?$/)) return version
        return "v" + version
    }

    private static boolean validRepo(String repo) {
        return repo ==~ /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/
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
}
