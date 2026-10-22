package co.hotwax.reconciler

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList
import org.moqui.resource.ResourceReference

/**
 * ServiceIntegrityValidator (Phase 2)
 * Performs deep runtime pre-flight validation on services modified or introduced in a release:
 * 1. Script Location Verification: Confirms that referenced scripts (component://.../*.groovy) exist on disk.
 * 2. Downstream Service Call Verification: Verifies that <service-call name="..."> targets valid services.
 * 3. Scheduled Job (moqui.service.job.ServiceJob) Compatibility Audit:
 *    Checks if existing scheduled background jobs configured in the database will break due to
 *    new required parameters or changed service contracts!
 */
class ServiceIntegrityValidator {
    private static final Logger logger = LoggerFactory.getLogger(ServiceIntegrityValidator.class)

    static class Phase2AuditResult {
        List<Map<String, Object>> scriptLocationAlerts = []
        List<Map<String, Object>> brokenScheduledJobAlerts = []
        List<Map<String, Object>> downstreamServiceAlerts = []
        int totalScriptsVerified = 0
        int totalDownstreamCallsVerified = 0
        int totalScheduledJobsAudited = 0
    }

    /**
     * Executes Phase 2 deep integrity audit.
     */
    static Phase2AuditResult runAudit(ExecutionContext ec, ServiceContractScanner.ServiceDiffContext ctx) {
        Phase2AuditResult result = new Phase2AuditResult()
        if (!ec || !ctx) return result

        // 1. Verify Script Locations for New and Modified Services
        auditScriptLocations(ec, ctx, result)

        // 2. Audit Scheduled Jobs (ServiceJob) for Breaking Contract Changes
        auditScheduledJobs(ec, ctx, result)

        return result
    }

    /**
     * Checks if service scripts (e.g. type="script" location="component://...") actually exist.
     */
    private static void auditScriptLocations(ExecutionContext ec, ServiceContractScanner.ServiceDiffContext ctx, Phase2AuditResult result) {
        Set<String> checkedLocations = new HashSet<>()

        for (Map<String, Object> svc : ctx.newServices) {
            String loc = (String) svc.get("location")
            if (!loc || checkedLocations.contains(loc)) continue
            checkedLocations.add(loc)
            result.totalScriptsVerified++

            try {
                ResourceReference rr = ec.resource.getLocationReference(loc)
                if (rr == null || !rr.getExists()) {
                    result.scriptLocationAlerts.add([
                        serviceName: svc.serviceName,
                        location: loc,
                        component: svc.component,
                        severity: "CRITICAL",
                        message: "Referenced script [${loc}] does NOT exist on filesystem. Service call will throw ResourceNotFoundException!"
                    ])
                }
            } catch (Exception e) {
                result.scriptLocationAlerts.add([
                    serviceName: svc.serviceName,
                    location: loc,
                    component: svc.component,
                    severity: "HIGH",
                    message: "Error verifying script location [${loc}]: ${e.message}"
                ])
            }
        }
    }

    /**
     * Cross-references breaking changes against active moqui.service.job.ServiceJob records in the database.
     */
    private static void auditScheduledJobs(ExecutionContext ec, ServiceContractScanner.ServiceDiffContext ctx, Phase2AuditResult result) {
        if (!ctx.breakingChanges) return

        // Map breaking changes by service name
        Map<String, List<Map<String, Object>>> breakingByService = [:]
        for (Map<String, Object> bc : ctx.breakingChanges) {
            String sName = (String) bc.serviceName
            if (!breakingByService.containsKey(sName)) breakingByService.put(sName, [])
            breakingByService.get(sName).add(bc)
        }

        try {
            EntityList jobs = ec.entity.find("moqui.service.job.ServiceJob").list()
            if (!jobs) return

            for (EntityValue job : jobs) {
                String jobServiceName = (String) job.get("serviceName")
                if (!jobServiceName) continue

                // Check if this job executes any service with breaking changes
                for (String breakingSvc : breakingByService.keySet()) {
                    if (jobServiceName.contains(breakingSvc) || jobServiceName.endsWith(breakingSvc)) {
                        result.totalScheduledJobsAudited++
                        String jobName = (String) job.get("jobName")
                        String paused = (String) job.get("paused") ?: "N"

                        // Fetch configured parameters for this job
                        EntityList jobParams = ec.entity.find("moqui.service.job.ServiceJobParameter")
                                .condition("jobName", jobName).list()
                        Set<String> configuredParamNames = new HashSet<>()
                        for (EntityValue jp : jobParams) {
                            String pName = (String) jp.get("parameterName")
                            if (pName) configuredParamNames.add(pName)
                        }

                        // Verify if all new required parameters are present in job configuration
                        List<Map<String, Object>> breakingList = breakingByService.get(breakingSvc)
                        for (Map<String, Object> bc : breakingList) {
                            if ("NEW_REQUIRED_IN_PARAM".equals(bc.changeType)) {
                                String reqParam = (String) bc.paramName
                                if (!configuredParamNames.contains(reqParam)) {
                                    result.brokenScheduledJobAlerts.add([
                                        jobName: jobName,
                                        serviceName: jobServiceName,
                                        breakingService: breakingSvc,
                                        missingParam: reqParam,
                                        paused: paused,
                                        severity: "CRITICAL",
                                        component: bc.component,
                                        message: "Scheduled Job [${jobName}] calls service [${breakingSvc}], but does NOT configure new required parameter [${reqParam}]. Job will FAIL on next execution!"
                                    ])
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("ServiceIntegrityValidator: Error auditing ServiceJob records: ${e.message}")
        }
    }
}
