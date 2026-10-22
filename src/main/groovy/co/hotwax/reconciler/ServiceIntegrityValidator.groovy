package co.hotwax.reconciler

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.resource.ResourceReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Validates script locations and active scheduled-job compatibility for changed services.
 */
class ServiceIntegrityValidator {
    private static final Logger logger = LoggerFactory.getLogger(ServiceIntegrityValidator.class)

    static class Phase2AuditResult {
        List<Map<String, Object>> scriptLocationAlerts = []
        List<Map<String, Object>> brokenScheduledJobAlerts = []
        int totalScriptsVerified
        int totalScheduledJobsAudited
        List<String> errors = []
    }

    static Phase2AuditResult runAudit(
            ExecutionContext ec, ServiceContractScanner.ServiceDiffContext context) {
        Phase2AuditResult result = new Phase2AuditResult()
        if (!ec || !context) return result
        ServiceContractScanner.finalizeContext(context)
        auditScriptLocations(ec, context, result)
        auditScheduledJobs(ec, context, result)
        return result
    }

    private static void auditScriptLocations(
            ExecutionContext ec, ServiceContractScanner.ServiceDiffContext context,
            Phase2AuditResult result) {
        Set<String> checked = []
        List<Map<String, Object>> services = []
        services.addAll(context.newServices)
        services.addAll(context.modifiedServices)
        for (Map<String, Object> service : services) {
            String location = (String) service.location
            if (!location || !checked.add(location)) continue
            result.totalScriptsVerified++
            try {
                ResourceReference reference = ec.resource.getLocationReference(location)
                if (reference == null || !reference.exists) {
                    result.scriptLocationAlerts.add([
                            serviceName: service.serviceName, location: location,
                            component: service.component, severity: "CRITICAL",
                            message: "Referenced service script does not exist"
                    ])
                }
            } catch (Exception e) {
                result.scriptLocationAlerts.add([
                        serviceName: service.serviceName, location: location,
                        component: service.component, severity: "HIGH",
                        message: "Script location validation failed: ${e.message}"
                ])
            }
        }
    }

    private static void auditScheduledJobs(
            ExecutionContext ec, ServiceContractScanner.ServiceDiffContext context,
            Phase2AuditResult result) {
        List<Map<String, Object>> requiredChanges =
                context.breakingChanges.findAll { it.changeType in ["NEW_REQUIRED_IN_PARAM", "PARAM_BECAME_REQUIRED"] }
        if (!requiredChanges) return

        Map<String, List<Map<String, Object>>> byService = requiredChanges.groupBy { it.serviceName }
        try {
            EntityList jobs = ec.entity.find("moqui.service.job.ServiceJob").list()
            Date now = new Date()
            for (EntityValue job : jobs) {
                if (!isActiveScheduledJob(job, now)) continue
                String serviceName = (String) job.serviceName
                List<Map<String, Object>> changes = byService.get(serviceName)
                if (!changes) continue

                result.totalScheduledJobsAudited++
                String jobName = (String) job.jobName
                EntityList parameters = ec.entity.find("moqui.service.job.ServiceJobParameter")
                        .condition("jobName", jobName).list()
                Set<String> configured = parameters.collect { it.parameterName as String } as Set
                for (Map<String, Object> change : changes) {
                    String requiredName = (String) change.paramName
                    if (configured.contains(requiredName)) continue
                    result.brokenScheduledJobAlerts.add([
                            jobName: jobName, serviceName: serviceName,
                            missingParam: requiredName, paused: job.paused ?: "N",
                            severity: "CRITICAL", component: change.component,
                            message: "Active scheduled job lacks new required parameter [${requiredName}]"
                    ])
                }
            }
        } catch (Exception e) {
            result.errors.add("Scheduled-job audit failed: ${e.message}")
            logger.warn("Scheduled-job audit failed: ${e.message}")
        }
    }

    static boolean isActiveScheduledJob(Map job, Date now = new Date()) {
        if (!job?.cronExpression) return false
        if ("Y".equalsIgnoreCase(job.paused as String)) return false
        Date fromDate = job.fromDate as Date
        Date thruDate = job.thruDate as Date
        if (fromDate && fromDate.after(now)) return false
        if (thruDate && !thruDate.after(now)) return false
        return true
    }
}
