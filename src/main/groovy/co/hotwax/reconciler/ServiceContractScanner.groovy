package co.hotwax.reconciler

import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Tracks service contract changes using full Moqui service names and explicit unresolved diff findings.
 */
class ServiceContractScanner {
    private static final Logger logger = LoggerFactory.getLogger(ServiceContractScanner.class)
    private static final Pattern SERVICE_PATTERN = Pattern.compile(/^[+ \-]?\s*<service\s+([^>]+)>/)
    private static final Pattern PARAM_PATTERN = Pattern.compile(/^[+ \-]\s*<parameter\s+([^>]+)>/)
    private static final Pattern ATTR_PATTERN = Pattern.compile(/([a-zA-Z0-9_-]+)\s*=\s*(["'])(.*?)\2/)

    static class ServiceDiffContext {
        String currentFile = ""
        String repo = ""
        String component = ""
        String tagShift = ""
        String currentServiceName
        String currentSection

        Map<String, Map<String, Object>> addedDeclarations = [:]
        Map<String, Map<String, Object>> removedDeclarations = [:]
        List<Map<String, Object>> parameterChanges = []
        List<Map<String, Object>> unresolvedChanges = []

        List<Map<String, Object>> breakingChanges = []
        List<Map<String, Object>> newServices = []
        List<Map<String, Object>> deletedServices = []
        List<Map<String, Object>> modifiedServices = []
        Map<String, Map<String, Object>> serviceCatalog = [:]
        boolean finalized = false
    }

    static ServiceDiffContext createContext() { new ServiceDiffContext() }

    static void processLine(
            String line, String filePath, String repo, String component, String tagShift,
            ServiceDiffContext context) {
        if (!line || !filePath || context == null) return
        context.finalized = false

        if (context.currentFile != filePath) {
            context.currentFile = filePath
            context.currentServiceName = null
            context.currentSection = null
        }
        context.repo = repo
        context.component = component
        context.tagShift = tagShift

        if (line.startsWith("@@")) {
            context.currentServiceName = null
            context.currentSection = null
            return
        }

        Matcher serviceMatcher = SERVICE_PATTERN.matcher(line)
        if (serviceMatcher.find()) {
            Map<String, String> attrs = parseAttributes(serviceMatcher.group(1))
            String verb = attrs.verb ?: ""
            String noun = attrs.noun ?: ""
            String fullName = buildFullServiceName(filePath, verb, noun)
            context.currentServiceName = fullName
            context.currentSection = null
            Map<String, Object> declaration = [
                    serviceName: fullName, verb: verb, noun: noun, component: component,
                    repo: repo, filePath: filePath, tagShift: tagShift,
                    type: attrs.type ?: "inline", location: attrs.location ?: ""
            ]
            if (isAdded(line)) context.addedDeclarations.put(fullName, declaration)
            else if (isRemoved(line)) context.removedDeclarations.put(fullName, declaration)
            else context.serviceCatalog.put(fullName, declaration)
            return
        }

        if (line.contains("<in-parameters>")) {
            context.currentSection = "IN"
            return
        }
        if (line.contains("</in-parameters>")) {
            context.currentSection = null
            return
        }
        if (line.contains("<out-parameters>")) {
            context.currentSection = "OUT"
            return
        }
        if (line.contains("</out-parameters>")) {
            context.currentSection = null
            return
        }

        if (!isAdded(line) && !isRemoved(line)) return
        Matcher parameterMatcher = PARAM_PATTERN.matcher(line)
        if (!parameterMatcher.find()) return

        if (!context.currentServiceName || !context.currentSection) {
            context.unresolvedChanges.add([
                    filePath: filePath, line: line.trim(), repo: repo, component: component,
                    reason: "Parameter change lacks service or in/out parameter context in the unified diff hunk"
            ])
            return
        }

        Map<String, String> attrs = parseAttributes(parameterMatcher.group(1))
        context.parameterChanges.add([
                serviceName: context.currentServiceName,
                section: context.currentSection,
                direction: isAdded(line) ? "ADDED" : "REMOVED",
                paramName: attrs.name,
                required: "true".equalsIgnoreCase(attrs.required),
                paramType: attrs.type ?: "String",
                component: component,
                repo: repo,
                filePath: filePath,
                tagShift: tagShift
        ])
    }

    static void finalizeContext(ServiceDiffContext context) {
        if (context == null || context.finalized) return
        context.breakingChanges.clear()
        context.newServices.clear()
        context.deletedServices.clear()
        context.modifiedServices.clear()

        Set<String> addedNames = context.addedDeclarations.keySet()
        Set<String> removedNames = context.removedDeclarations.keySet()
        Set<String> newNames = new LinkedHashSet<>(addedNames)
        newNames.removeAll(removedNames)
        Set<String> deletedNames = new LinkedHashSet<>(removedNames)
        deletedNames.removeAll(addedNames)
        Set<String> modifiedNames = new LinkedHashSet<>(addedNames)
        modifiedNames.retainAll(removedNames)

        newNames.each { String name ->
            Map<String, Object> value = context.addedDeclarations.get(name)
            context.newServices.add(value)
            context.serviceCatalog.put(name, value)
        }
        deletedNames.each { String name -> context.deletedServices.add(context.removedDeclarations.get(name)) }
        modifiedNames.each { String name ->
            Map<String, Object> value = context.addedDeclarations.get(name)
            context.modifiedServices.add(value)
            context.serviceCatalog.put(name, value)
        }

        Map<String, List<Map<String, Object>>> grouped = context.parameterChanges.groupBy {
            "${it.serviceName}::${it.section}::${it.paramName}"
        }
        grouped.each { String ignored, List<Map<String, Object>> changes ->
            Map<String, Object> added = changes.find { it.direction == "ADDED" }
            Map<String, Object> removed = changes.find { it.direction == "REMOVED" }
            String serviceName = (added ?: removed).serviceName
            if (newNames.contains(serviceName)) return

            if (added && added.section == "IN" && added.required == true &&
                    (!removed || removed.required != true)) {
                context.breakingChanges.add(changeAlert(
                        added, removed ? "PARAM_BECAME_REQUIRED" : "NEW_REQUIRED_IN_PARAM", "CRITICAL",
                        "Required in-parameter [${added.paramName}] was introduced for an existing service"))
            } else if (removed && !added) {
                String type = removed.section == "IN" ? "REMOVED_IN_PARAM" : "REMOVED_OUT_PARAM"
                context.breakingChanges.add(changeAlert(
                        removed, type, "HIGH",
                        "${removed.section == 'IN' ? 'In' : 'Out'}-parameter [${removed.paramName}] was removed"))
            } else if (added && removed && added.paramType != removed.paramType) {
                context.breakingChanges.add(changeAlert(
                        added, "PARAM_TYPE_CHANGED", "HIGH",
                        "Parameter [${added.paramName}] type changed from ${removed.paramType} to ${added.paramType}"))
            }
        }
        context.finalized = true
    }

    static void verifyServiceDefinitions(ExecutionContext ec, ServiceDiffContext context) {
        finalizeContext(context)
        for (Map<String, Object> service : context.newServices) {
            try {
                def definition = ec.service.getServiceDefinition((String) service.serviceName)
                service.loadStatus = definition != null ? "LOADED_OK" : "NOT_FOUND_IN_ACTIVE_FRAMEWORK"
            } catch (Exception e) {
                service.loadStatus = "LOAD_ERROR: ${e.message}"
                logger.warn("Service definition lookup failed for ${service.serviceName}: ${e.message}")
            }
        }
    }

    static String buildFullServiceName(String filePath, String verb, String noun) {
        String normalized = filePath.replace('\\', '/')
        int serviceIndex = normalized.indexOf("service/")
        if (serviceIndex >= 0) normalized = normalized.substring(serviceIndex + "service/".length())
        normalized = normalized.replaceAll(/\.xml$/, "").replace("/", ".")
        String localName = verb && noun ? "${verb}#${noun}" : (noun ?: verb)
        return normalized ? "${normalized}.${localName}" : localName
    }

    private static Map<String, Object> changeAlert(
            Map<String, Object> change, String changeType, String severity, String message) {
        return [
                serviceName: change.serviceName, paramName: change.paramName,
                changeType: changeType, severity: severity, paramType: change.paramType,
                component: change.component, repo: change.repo, filePath: change.filePath,
                tagShift: change.tagShift, message: message
        ]
    }

    private static boolean isAdded(String line) {
        return line.startsWith("+") && !line.startsWith("+++")
    }

    private static boolean isRemoved(String line) {
        return line.startsWith("-") && !line.startsWith("---")
    }

    private static Map<String, String> parseAttributes(String text) {
        Map<String, String> attrs = [:]
        Matcher matcher = ATTR_PATTERN.matcher(text)
        while (matcher.find()) attrs.put(matcher.group(1), matcher.group(3))
        return attrs
    }
}
