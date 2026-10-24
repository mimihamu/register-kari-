package jp.co.tenposinfo.register

/** A PIN-free login is an explicit per-operator opt-in for limited cashier duties only. */
internal object OperatorPinOptionalPolicyV144 {
    fun canSaveWithoutPin(role: OperatorRole, permissions: Set<RegisterPermission>): Boolean =
        role == OperatorRole.CASHIER &&
            permissions.all { it in OperatorPermissionPolicy.defaults(OperatorRole.CASHIER) }

    fun canLoginWithoutPin(
        role: OperatorRole,
        permissions: Set<RegisterPermission>,
        pinRequired: Boolean,
    ): Boolean = !pinRequired && canSaveWithoutPin(role, permissions)
}
