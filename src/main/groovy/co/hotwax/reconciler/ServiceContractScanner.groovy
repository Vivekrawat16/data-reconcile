package co.hotwax.reconciler

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.moqui.context.ExecutionContext

/**
 * ServiceContractScanner (Phase 1)
 * Scans release git diffs across shifted components for Moqui service XML files (service/** /*.xml).
 * Detects:
 * 1. Breaking In-Parameter Changes: Existing services adding new required="true" parameters.
 * 2. Removed In/Out Parameters from existing services.
 * 3. Deleted or Renamed Services.
 * 4. New Services introduced in the release.
 * 5. Pre-flight Definition & Syntax Verification via Moqui's Service Facade.
 */
class ServiceContractScanner {
    private static final Logger logger = LoggerFactory.getLogger(ServiceContractScanner.class)

    private static final Pattern PATTERN_SERVICE = Pattern.compile(/^[+ \-]?\s*<service\s+([^>]+)>/)
    private static final Pattern PATTERN_PARAM = Pattern.compile(/^[+ \-]\s*<parameter\s+([^>]+)>/)
    private static final Pattern PATTERN_ATTR = Pattern.compile(/([a-zA-Z0-9_\-]+)="([^"]*)"/)

    static class ServiceDiffContext {
        String currentFile = ""
        String repo = ""
        String component = ""
        String tagShift = ""
        String currentServiceName = null
        String currentServiceVerb = null
        String currentServiceNoun = null
        boolean isNewService = false
        boolean inInParams = false
        boolean inOutParams = false

        List<Map<String, Object>> breakingChanges = []
        List<Map<String, Object>> newServices = []
        List<Map<String, Object>> deletedServices = []
        List<Map<String, Object>> modifiedServices = []
        Map<String, Map<String, Object>> serviceCatalog = [:]
    }

    /**
     * Creates a new scanning context.
     */
    static ServiceDiffContext createContext() {
        return new ServiceDiffContext()
    }

    /**
     * Processes a single diff line from a service XML file.
     */
    static void processLine(String line, String filePath, String repo, String component, String tagShift, ServiceDiffContext ctx) {
        if (!line || !filePath) return

        if (ctx.currentFile != filePath) {
            ctx.currentFile = filePath
            ctx.repo = repo
            ctx.component = component
            ctx.tagShift = tagShift
            ctx.currentServiceName = null
            ctx.isNewService = false
            ctx.inInParams = false
            ctx.inOutParams = false
        }

        // 1. Detect <service ...> declaration
        Matcher sm = PATTERN_SERVICE.matcher(line)
        if (sm.find()) {
            Map<String, String> attrs = parseAttributes(sm.group(1))
            String verb = attrs.get("verb") ?: ""
            String noun = attrs.get("noun") ?: ""
            String serviceName = verb && noun ? "${verb}#${noun}" : (noun ?: verb)

            ctx.currentServiceVerb = verb
            ctx.currentServiceNoun = noun
            ctx.currentServiceName = serviceName
            ctx.isNewService = line.startsWith("+")
            ctx.inInParams = false
            ctx.inOutParams = false

            if (line.startsWith("-") && !line.startsWith("---")) {
                ctx.deletedServices.add([
                    serviceName: serviceName,
                    component: component,
                    repo: repo,
                    filePath: filePath,
                    tagShift: tagShift
                ])
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                if (!ctx.serviceCatalog.containsKey(serviceName)) {
                    Map<String, Object> newSvc = [
                        serviceName: serviceName,
                        verb: verb,
                        noun: noun,
                        component: component,
                        repo: repo,
                        filePath: filePath,
                        tagShift: tagShift,
                        type: attrs.get("type") ?: "inline",
                        location: attrs.get("location") ?: ""
                    ]
                    ctx.newServices.add(newSvc)
                    ctx.serviceCatalog.put(serviceName, newSvc)
                }
            } else {
                // Existing service modified
                if (!ctx.serviceCatalog.containsKey(serviceName)) {
                    ctx.serviceCatalog.put(serviceName, [
                        serviceName: serviceName,
                        verb: verb,
                        noun: noun,
                        component: component,
                        repo: repo,
                        filePath: filePath,
                        tagShift: tagShift
                    ])
                }
            }
            return
        }

        // 2. Track parameter blocks
        if (line.contains("<in-parameters>")) {
            ctx.inInParams = true
            ctx.inOutParams = false
            return
        }
        if (line.contains("</in-parameters>")) {
            ctx.inInParams = false
            return
        }
        if (line.contains("<out-parameters>")) {
            ctx.inOutParams = true
            ctx.inInParams = false
            return
        }
        if (line.contains("</out-parameters>")) {
            ctx.inOutParams = false
            return
        }

        // 3. Inspect parameters inside <in-parameters> or <out-parameters>
        if ((ctx.inInParams || ctx.inOutParams) && ctx.currentServiceName) {
            boolean isAdded = line.startsWith("+") && !line.startsWith("++")
            boolean isRemoved = line.startsWith("-") && !line.startsWith("--")

            if (isAdded || isRemoved) {
                Matcher pm = PATTERN_PARAM.matcher(line)
                if (pm.find()) {
                    Map<String, String> pAttrs = parseAttributes(pm.group(1))
                    String paramName = pAttrs.get("name")
                    boolean isRequired = "true".equalsIgnoreCase(pAttrs.get("required"))
                    String paramType = pAttrs.get("type") ?: "String"

                    if (isAdded && ctx.inInParams) {
                        // If added to an EXISTING service and required="true" -> CRITICAL BREAKING CHANGE!
                        if (!ctx.isNewService && isRequired) {
                            ctx.breakingChanges.add([
                                serviceName: ctx.currentServiceName,
                                paramName: paramName,
                                changeType: "NEW_REQUIRED_IN_PARAM",
                                severity: "CRITICAL",
                                paramType: paramType,
                                component: ctx.component,
                                repo: ctx.repo,
                                filePath: ctx.filePath,
                                tagShift: ctx.tagShift,
                                message: "Added mandatory in-parameter [${paramName}] to existing service. Existing callers will FAIL!"
                            ])
                        }
                    } else if (isRemoved) {
                        // Parameter deleted from an existing service
                        if (!ctx.isNewService) {
                            ctx.breakingChanges.add([
                                serviceName: ctx.currentServiceName,
                                paramName: paramName,
                                changeType: ctx.inInParams ? "REMOVED_IN_PARAM" : "REMOVED_OUT_PARAM",
                                severity: "HIGH",
                                paramType: paramType,
                                component: ctx.component,
                                repo: ctx.repo,
                                filePath: ctx.filePath,
                                tagShift: ctx.tagShift,
                                message: "${ctx.inInParams ? 'In-parameter' : 'Out-parameter'} [${paramName}] was removed from existing service."
                            ])
                        }
                    }
                }
            }
        }
    }

    private static Map<String, String> parseAttributes(String text) {
        Map<String, String> attrs = [:]
        Matcher m = PATTERN_ATTR.matcher(text)
        while (m.find()) {
            attrs.put(m.group(1), m.group(2))
        }
        return attrs
    }

    /**
     * Verifies that each new or modified service definition can be loaded and compiled by Moqui.
     */
    static void verifyServiceDefinitions(ExecutionContext ec, ServiceDiffContext ctx) {
        if (!ec || !ctx) return

        for (Map<String, Object> svc : ctx.newServices) {
            String sName = (String) svc.serviceName
            try {
                def sDef = ec.service.getServiceDefinition(sName)
                if (sDef != null) {
                    svc.put("loadStatus", "LOADED_OK")
                } else {
                    svc.put("loadStatus", "NOT_FOUND_IN_ACTIVE_FRAMEWORK")
                }
            } catch (Exception e) {
                svc.put("loadStatus", "COMPILATION_ERROR: " + e.message)
                logger.warn("ServiceContractScanner: Error resolving service ${sName}: ${e.message}")
            }
        }
    }
}
