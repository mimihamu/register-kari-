package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V020OperationsRoutingTest {
    @Test
    fun canonicalActivityIsTheOnlyAcceptedManagementTarget() {
        assertTrue(OperationsRoutingPolicy.isCanonical(OperationsRoutingPolicy.CANONICAL_ACTIVITY))
        assertFalse(OperationsRoutingPolicy.isCanonical(OperationsRoutingPolicy.LEGACY_ACTIVITY))
    }

    @Test
    fun canonicalOnlyRegistrationHasNoLegacyRoute() {
        assertFalse(
            OperationsRoutingPolicy.hasLegacyRoute(
                declaredActivities = setOf(OperationsRoutingPolicy.CANONICAL_ACTIVITY),
                aliasTargets = emptySet(),
            ),
        )
    }

    @Test
    fun directLegacyActivityRegistrationIsRejected() {
        assertTrue(
            OperationsRoutingPolicy.hasLegacyRoute(
                declaredActivities = setOf(OperationsRoutingPolicy.LEGACY_ACTIVITY),
                aliasTargets = emptySet(),
            ),
        )
    }

    @Test
    fun legacyAliasTargetIsRejected() {
        assertTrue(
            OperationsRoutingPolicy.hasLegacyRoute(
                declaredActivities = setOf(OperationsRoutingPolicy.CANONICAL_ACTIVITY),
                aliasTargets = setOf(OperationsRoutingPolicy.LEGACY_ACTIVITY),
            ),
        )
    }
}
