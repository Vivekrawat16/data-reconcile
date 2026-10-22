import co.hotwax.reconciler.ReconcileCoordinator

if (!ec.user.hasPermission('DATA_RECONCILE_ADMIN')) {
    ec.message.addError('DATA_RECONCILE_ADMIN permission is required to run release metadata reconciliation')
    return
}

if (!"false".equalsIgnoreCase(System.getProperty("data.reconciler.requestCredentialsEnabled"))) {
    System.setProperty("data.reconciler.requestCredentialsEnabled", "true")
}

return ReconcileCoordinator.reconcile(ec, context)

