import co.hotwax.reconciler.ReconcileCoordinator

if (!ec.user.hasPermission('DATA_RECONCILE_ADMIN')) {
    ec.message.addError('DATA_RECONCILE_ADMIN permission is required to run release metadata reconciliation')
    return
}

return ReconcileCoordinator.reconcile(ec, context)
