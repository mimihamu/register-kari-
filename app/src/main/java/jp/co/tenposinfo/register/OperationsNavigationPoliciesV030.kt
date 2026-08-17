package jp.co.tenposinfo.register

/**
 * v0.30の営業開始導線判定。
 * v1.35では営業開始をZ精算から分離し、新規UI・認可判定はBUSINESS_STARTだけを参照する。
 * 旧SETTLEMENTは互換レイヤーでBUSINESS_START/X/Zへ展開される。
 */
object BusinessStartNavigationPolicyV030 {
    fun canOpenBusinessStart(permissions: Set<RegisterPermission>): Boolean =
        RegisterPermission.BUSINESS_START in permissions
}

/** レジ管理メニューを表示できる権限の共通判定。 */
object ManagementNavigationPolicyV030 {
    private val managementPermissions = setOf(
        RegisterPermission.VIEW_SALES,
        RegisterPermission.CASH_MOVEMENT,
        RegisterPermission.BUSINESS_START,
        RegisterPermission.X_INSPECTION,
        RegisterPermission.Z_SETTLEMENT,
        RegisterPermission.REVERSAL,
    )

    fun canOpenManagement(permissions: Set<RegisterPermission>): Boolean =
        permissions.any(managementPermissions::contains)
}

/** OperationsActivity入口と個別画面の認可を画面実装から分離する。 */
object OperationsAccessPolicyV030 {
    fun canEnter(permissions: Set<RegisterPermission>): Boolean =
        ManagementNavigationPolicyV030.canOpenManagement(permissions)

    fun canOpenBusinessStart(permissions: Set<RegisterPermission>): Boolean =
        BusinessStartNavigationPolicyV030.canOpenBusinessStart(permissions)
}

/** OperationsActivityへ初期表示画面を明示するIntent契約。 */
object OperationsNavigationContractV030 {
    const val EXTRA_INITIAL_SCREEN = "jp.co.tenposinfo.register.extra.OPERATIONS_INITIAL_SCREEN"
    const val OPEN_BUSINESS_START = "OPEN_BUSINESS_START"

    fun requestsBusinessStart(value: String?): Boolean = value == OPEN_BUSINESS_START
}
