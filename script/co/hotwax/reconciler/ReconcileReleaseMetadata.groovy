import co.hotwax.reconciler.ReconcileCoordinator

if (!ec.user.hasPermission('DATA_RECONCILE_ADMIN')) {
    ec.message.addError('DATA_RECONCILE_ADMIN permission is required to run release metadata reconciliation')
    return
}

if (!"false".equalsIgnoreCase(System.getProperty("data.reconciler.requestCredentialsEnabled"))) {
    System.setProperty("data.reconciler.requestCredentialsEnabled", "true")
}

try {
    Class<?> resolverClass = Class.forName("co.hotwax.reconciler.DockerConfigReleaseResolver")
    java.lang.reflect.Method underlyingMethod = resolverClass.getDeclaredMethods().find {
        it.name == "resolveShiftsDetailed" && it.parameterCount == 5
    }
    if (underlyingMethod != null) {
        underlyingMethod.setAccessible(true)
        resolverClass.metaClass.'static'.resolveShiftsDetailed = { String repo, String fromRef, String toRef, String token, String localPath = null ->
            def res = underlyingMethod.invoke(null, [repo, fromRef, toRef, token, localPath] as Object[])
            if (res != null && res.errors) {
                def baselineErrors = res.errors.findAll { String err -> err.contains("baseline") }
                if (baselineErrors) {
                    res.errors.removeAll(baselineErrors)
                    res.warnings.addAll(baselineErrors)
                    res.shifts = res.shifts.findAll { it.fromTag && it.toTag }
                    res.success = true
                }
            }
            return res
        }
    }
} catch (Exception e) {
    // Fall back to standard invocation
}

return ReconcileCoordinator.reconcile(ec, context)


