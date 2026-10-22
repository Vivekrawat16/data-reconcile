package co.hotwax.reconciler

import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Coordinates a reconciliation run and owns the fail-closed status model.
 */
class ReconcileCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(ReconcileCoordinator.class)

    static Map<String, Object> reconcile(ExecutionContext ec, Map input) {
        Map<String, Object> output = initialOutput()
        List<String> errors = (List<String>) output.errors
        List<String> warnings = (List<String>) output.warnings

        try {
            List<String> requestedEntities = (input.entityList ?: "").toString().split(",")
                    .collect { it.trim() }.findAll { it }
            List<String> entities = ReconcileEngine.validateEntityList(ec, requestedEntities)
            String fromTag = input.fromTag as String
            String toTag = input.toTag as String
            String token = GitDiffScanner.resolveGithubToken(
                    resolveManagedCredential(input.githubToken, "GITHUB_TOKEN"))
            String referenceCredential = resolveManagedCredential(
                    input.referenceApiKey, "DATA_RECONCILER_REFERENCE_TOKEN")
            String targetCredential = resolveManagedCredential(
                    input.targetApiKey, "DATA_RECONCILER_TARGET_TOKEN")

            Map<String, Map<String, Map<String, String>>> allGitAdditions = [:]
            Map<String, String> entityPkOrigins = [:]
            entities.each { String entity -> allGitAdditions.put(entity, [:]) }
            List<Map> rawCodeReferences = []
            ServiceContractScanner.ServiceDiffContext serviceContext = ServiceContractScanner.createContext()
            List shifts = []

            if (input.githubRepo || input.repoPath) {
                String localRepoPath = input.repoPath ?
                        validateLocalRepositoryPath(ec, input.repoPath as String) : null
                GitDiffScanner.DiffScanResult direct
                String componentName = input.githubRepo ? input.githubRepo.toString().tokenize("/").last() :
                        new File(localRepoPath).name
                if (input.githubRepo) {
                    direct = GitDiffScanner.scanGitHubDiffDetailed(
                            input.githubRepo as String, token, fromTag, toTag, entities,
                            componentName, rawCodeReferences, serviceContext)
                } else {
                    direct = GitDiffScanner.scanLocalGitDiffDetailed(
                            localRepoPath, fromTag, toTag, entities,
                            componentName, rawCodeReferences, serviceContext)
                }
                mergeAdditions(allGitAdditions, entityPkOrigins, direct, entities,
                        componentName, "${fromTag} -> ${toTag}", errors)
                shifts.add([name: componentName, repo: input.githubRepo ?: localRepoPath,
                            fromTag: fromTag, toTag: toTag])
            } else {
                DockerConfigReleaseResolver.ResolutionResult resolution =
                        DockerConfigReleaseResolver.resolveShiftsDetailed(
                                input.dockerConfigRepo as String, fromTag, toTag, token)
                errors.addAll(resolution.errors)
                warnings.addAll(resolution.warnings)
                if (resolution.success) {
                    shifts.addAll(resolution.shifts)
                    for (DockerConfigReleaseResolver.ComponentShift shift : resolution.shifts) {
                        GitDiffScanner.DiffScanResult scan = GitDiffScanner.scanGitHubDiffDetailed(
                                shift.repo, token, shift.fromTag, shift.toTag, entities,
                                shift.name, rawCodeReferences, serviceContext)
                        mergeAdditions(allGitAdditions, entityPkOrigins, scan, entities,
                                shift.name, "${shift.fromTag} -> ${shift.toTag}", errors)
                    }
                }
            }

            ServiceContractScanner.finalizeContext(serviceContext)
            if (serviceContext.unresolvedChanges) {
                errors.add("Service diff audit is incomplete for ${serviceContext.unresolvedChanges.size()} parameter changes")
            }

            if (errors) return finishIncomplete(output, errors, warnings, shifts)

            Map<String, Map<String, Object>> resultsByEntity = [:]
            int totalCritical = 0
            int totalEnvironment = 0
            int totalConflicts = 0
            StringBuilder combinedXml = new StringBuilder()

            for (String entity : entities) {
                Map<String, Object> entityResult = ReconcileEngine.reconcileEntity(
                        ec, input.referenceUrl as String, referenceCredential,
                        input.targetUrl as String, targetCredential,
                        entity, allGitAdditions.get(entity), entityPkOrigins)
                resultsByEntity.put(entity, entityResult)
                if (entityResult.success != true) errors.addAll((List<String>) entityResult.errors)
                totalCritical += ((List) entityResult.criticalMissingList).size()
                totalEnvironment += ((List) entityResult.environmentMissingList).size()
                totalConflicts += ((List) entityResult.conflictList).size()
                if (entityResult.syncXmlPayload) combinedXml.append(entityResult.syncXmlPayload)
            }

            boolean remoteTarget = input.targetUrl && !"local".equalsIgnoreCase(input.targetUrl.toString())
            errors.addAll(ReconcileEngine.validateDependencies(ec, resultsByEntity, remoteTarget))

            List<Map<String, Object>> omissions = []
            if (remoteTarget) {
                warnings.add("Developer omission verification is local-database scoped and was skipped for a remote target")
            } else {
                omissions = CodeReferenceScanner.verifyOmissions(rawCodeReferences, allGitAdditions, ec)
            }

            ServiceContractScanner.verifyServiceDefinitions(ec, serviceContext)
            ServiceIntegrityValidator.Phase2AuditResult integrity =
                    ServiceIntegrityValidator.runAudit(ec, serviceContext)
            errors.addAll(integrity.errors)

            int applied = 0
            if (input.autoApply == true) {
                if (errors) {
                    errors.add("Auto-apply was blocked because the audit is incomplete")
                } else if (!"true".equalsIgnoreCase(System.getProperty("data.reconciler.autoApplyEnabled"))) {
                    errors.add("Auto-apply is disabled; set -Ddata.reconciler.autoApplyEnabled=true after operational approval")
                } else {
                    Map<String, Object> applyResult
                    if (remoteTarget) {
                        if (!"true".equalsIgnoreCase(System.getProperty("data.reconciler.remoteApplyEnabled"))) {
                            errors.add("Remote auto-apply is disabled; set -Ddata.reconciler.remoteApplyEnabled=true after operational approval")
                            applyResult = null
                        } else {
                            applyResult = ReconcileEngine.applyRemoteCreateOnly(
                                    input.targetUrl as String, targetCredential, resultsByEntity)
                        }
                    } else {
                        applyResult = ReconcileEngine.applyLocalCreateOnly(ec, resultsByEntity)
                    }
                    if (applyResult != null) {
                        if (applyResult.success == true) applied = applyResult.applied as int
                        else errors.add("Create-only apply failed: ${applyResult.error}")
                    }
                }
            }

            output.auditComplete = errors.isEmpty()
            output.errors = errors
            output.warnings = warnings
            output.totalCriticalMissing = totalCritical
            output.totalEnvironmentMissing = totalEnvironment
            output.totalConflicts = totalConflicts
            output.totalAutoApplied = applied
            output.shiftedComponentsCount = shifts.size()
            output.totalOmissionAlerts = omissions.size()
            output.omissionAlerts = omissions
            output.totalServiceBreakingChanges = serviceContext.breakingChanges.size()
            output.serviceBreakingChanges = serviceContext.breakingChanges
            output.newServicesIntroduced = serviceContext.newServices
            output.totalAtRiskScheduledJobs = integrity.brokenScheduledJobAlerts.size()
            output.atRiskScheduledJobs = integrity.brokenScheduledJobAlerts
            output.scriptLocationAlerts = integrity.scriptLocationAlerts
            output.resultsByEntity = resultsByEntity
            output.combinedSyncXml = combinedXml.length() ?
                    "<entity-facade-xml>\n${combinedXml}</entity-facade-xml>" : ""
            output.overallStatus = determineStatus(output, serviceContext, integrity, input.autoApply == true)
            output.summaryReport = buildReport(output, fromTag, toTag, input.referenceUrl as String,
                    input.targetUrl as String)
            return output
        } catch (Throwable t) {
            logger.error("Reconciliation failed", t)
            errors.add("${t.class.simpleName}: ${t.message}")
            return finishIncomplete(output, errors, warnings, [])
        }
    }

    private static void mergeAdditions(
            Map<String, Map<String, Map<String, String>>> target,
            Map<String, String> origins,
            GitDiffScanner.DiffScanResult scan,
            List<String> entities,
            String componentName,
            String tagShift,
            List<String> errors) {
        errors.addAll(scan.errors)
        if (!scan.success) return
        for (String entity : entities) {
            Map<String, Map<String, String>> incoming = scan.additions.get(entity) ?: [:]
            incoming.each { String key, Map<String, String> record ->
                Map<String, String> existing = target.get(entity).get(key)
                if (existing != null && existing != record) {
                    errors.add("Conflicting release definitions for ${entity} [${key}]")
                } else {
                    target.get(entity).put(key, record)
                    origins.put("${entity}::${key}", "${componentName} (${tagShift})")
                }
            }
        }
    }

    private static Map<String, Object> finishIncomplete(
            Map<String, Object> output, List<String> errors, List<String> warnings, List shifts) {
        output.auditComplete = false
        output.overallStatus = "AUDIT_INCOMPLETE"
        output.errors = errors.unique()
        output.warnings = warnings.unique()
        output.shiftedComponentsCount = shifts?.size() ?: 0
        output.summaryReport = buildReport(output, null, null, null, null)
        return output
    }

    private static String determineStatus(
            Map<String, Object> output,
            ServiceContractScanner.ServiceDiffContext services,
            ServiceIntegrityValidator.Phase2AuditResult integrity,
            boolean autoApplyRequested) {
        if (output.errors) return "AUDIT_INCOMPLETE"
        if (integrity.brokenScheduledJobAlerts) return "CRITICAL_SCHEDULED_JOBS_AT_RISK"
        if (integrity.scriptLocationAlerts) return "SERVICE_SCRIPTS_MISSING"
        if (services.breakingChanges) return "BREAKING_SERVICE_CHANGES_FOUND"
        if ((output.totalCriticalMissing as int) > 0) {
            if (autoApplyRequested && (output.totalAutoApplied as int) == (output.totalCriticalMissing as int)) {
                return "AUTO_REPAIRED_CREATE_ONLY"
            }
            return "CRITICAL_MISSING_FOUND"
        }
        if ((output.totalConflicts as int) > 0) return "DATA_CONFLICTS_FOUND"
        if ((output.totalOmissionAlerts as int) > 0) return "DEVELOPER_OMISSION_DETECTED"
        return "IN_SYNC"
    }

    private static String buildReport(
            Map<String, Object> output, String fromTag, String toTag,
            String referenceUrl, String targetUrl) {
        StringBuilder report = new StringBuilder()
        report.append("RELEASE METADATA RECONCILIATION\n")
        report.append("Status: ${output.overallStatus}\n")
        report.append("Audit complete: ${output.auditComplete}\n")
        if (fromTag || toTag) report.append("Release: ${fromTag} -> ${toTag}\n")
        if (referenceUrl) report.append("Reference: ${referenceUrl}\n")
        if (targetUrl) report.append("Target: ${targetUrl}\n")
        report.append("Critical missing: ${output.totalCriticalMissing}\n")
        report.append("Environment-only missing: ${output.totalEnvironmentMissing}\n")
        report.append("Field conflicts: ${output.totalConflicts}\n")
        report.append("Create-only applied: ${output.totalAutoApplied}\n")
        if (output.errors) {
            report.append("Errors:\n")
            ((List) output.errors).each { report.append("  - ${it}\n") }
        }
        if (output.warnings) {
            report.append("Warnings:\n")
            ((List) output.warnings).each { report.append("  - ${it}\n") }
        }
        return report.toString()
    }

    private static String validateLocalRepositoryPath(ExecutionContext ec, String requestedPath) {
        File componentRoot = new File(ec.factory.runtimePath, "component").canonicalFile
        File candidate = new File(requestedPath).canonicalFile
        if (!candidate.path.startsWith(componentRoot.path + File.separator)) {
            throw new SecurityException("Local repository must be inside the runtime component directory")
        }
        if (!candidate.isDirectory() || !new File(candidate, ".git").isDirectory()) {
            throw new IllegalArgumentException("Local repository path is not a Git component checkout")
        }
        return candidate.path
    }

    static String resolveManagedCredential(Object suppliedCredential, String environmentName) {
        String supplied = suppliedCredential?.toString()?.trim()
        if (supplied && !"true".equalsIgnoreCase(
                System.getProperty("data.reconciler.requestCredentialsEnabled"))) {
            throw new SecurityException(
                    "Request-supplied credentials are disabled; configure ${environmentName} on the server")
        }
        return supplied ?: System.getenv(environmentName)
    }

    private static Map<String, Object> initialOutput() {
        return [
                overallStatus: "AUDIT_INCOMPLETE", auditComplete: false,
                errors: [], warnings: [],
                totalCriticalMissing: 0, totalEnvironmentMissing: 0, totalConflicts: 0,
                totalAutoApplied: 0, shiftedComponentsCount: 0,
                totalOmissionAlerts: 0, omissionAlerts: [],
                totalServiceBreakingChanges: 0, serviceBreakingChanges: [],
                newServicesIntroduced: [], totalAtRiskScheduledJobs: 0,
                atRiskScheduledJobs: [], scriptLocationAlerts: [],
                resultsByEntity: [:], combinedSyncXml: "", summaryReport: ""
        ]
    }
}
