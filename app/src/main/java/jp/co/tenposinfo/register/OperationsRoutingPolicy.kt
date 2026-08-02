package jp.co.tenposinfo.register

object OperationsRoutingPolicy {
    const val CANONICAL_ACTIVITY = "jp.co.tenposinfo.register.OperationsActivity"
    const val LEGACY_ACTIVITY = "jp.co.tenposinfo.register.AdvancedOperationsActivity"

    fun isCanonical(className: String): Boolean = className == CANONICAL_ACTIVITY

    fun hasLegacyRoute(
        declaredActivities: Set<String>,
        aliasTargets: Set<String>,
    ): Boolean =
        LEGACY_ACTIVITY in declaredActivities || LEGACY_ACTIVITY in aliasTargets
}
