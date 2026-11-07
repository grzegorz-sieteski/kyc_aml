package com.gs5.kycaml.risk.adapters.access;

import com.gs5.kycaml.risk.domain.DecisionKind;
import com.gs5.kycaml.shared.kernel.ActorRole;
import com.gs5.kycaml.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may do what, for every role and every operation (A-02, BR-02). */
class PermissionMatrixTest {

    private static final Set<ActorRole> COMPLIANCE_STAFF =
            Set.of(ActorRole.AML_ANALYST_L1, ActorRole.AML_ANALYST_L2, ActorRole.MLRO);

    private static boolean admits(Permission permission, ActorRole role) {
        return permission.check(Callers.optionalOf(role)).isSuccess();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ActorRole.class)
    @DisplayName("only the onboarding system may evaluate customer risk")
    void shouldAdmitOnlyTheOnboardingSystemToEvaluate(ActorRole role) {
        assertThat(admits(RiskPermissions.EVALUATE, role)).isEqualTo(role == ActorRole.ONBOARDING_SYSTEM);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ActorRole.class)
    @DisplayName("the compliance view is open to the AML analysts and the MLRO only")
    void shouldAdmitOnlyComplianceStaffToTheComplianceView(ActorRole role) {
        assertThat(admits(RiskPermissions.COMPLIANCE_VIEW, role)).isEqualTo(COMPLIANCE_STAFF.contains(role));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ActorRole.class)
    @DisplayName("the front-office status is open to every identified caller, whatever the role (BR-40)")
    void shouldAdmitEveryIdentifiedCallerToTheFrontOfficeStatus(ActorRole role) {
        assertThat(admits(RiskPermissions.FRONT_OFFICE_STATUS, role)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ActorRole.class)
    @DisplayName("escalating is open to L1, L2 and the MLRO")
    void shouldAdmitAllComplianceStaffToEscalate(ActorRole role) {
        assertThat(admits(RiskPermissions.forDecision(DecisionKind.ESCALATE_EDD), role))
                .isEqualTo(COMPLIANCE_STAFF.contains(role));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ActorRole.class)
    @DisplayName("freezing assets is open to L2 and the MLRO, not to L1")
    void shouldAdmitOnlyL2AndMlroToFreeze(ActorRole role) {
        assertThat(admits(RiskPermissions.forDecision(DecisionKind.FREEZE_ASSETS), role))
                .isEqualTo(role == ActorRole.AML_ANALYST_L2 || role == ActorRole.MLRO);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ActorRole.class)
    @DisplayName("clearing as a false positive is open to L2 and the MLRO, not to L1")
    void shouldAdmitOnlyL2AndMlroToClear(ActorRole role) {
        assertThat(admits(RiskPermissions.forDecision(DecisionKind.CLEAR_FALSE_POSITIVE), role))
                .isEqualTo(role == ActorRole.AML_ANALYST_L2 || role == ActorRole.MLRO);
    }

    @Test
    @DisplayName("an absent caller is refused as not identified by every permission, before any role (BR-01)")
    void shouldRefuseAnAbsentCaller() {
        var notIdentified = Result.<AuthenticatedCaller, AccessRefusal>failure(new AccessRefusal.CallerNotIdentified());

        assertThat(RiskPermissions.EVALUATE.check(Optional.empty())).isEqualTo(notIdentified);
        assertThat(RiskPermissions.COMPLIANCE_VIEW.check(Optional.empty())).isEqualTo(notIdentified);
        assertThat(RiskPermissions.FRONT_OFFICE_STATUS.check(Optional.empty())).isEqualTo(notIdentified);
        for (var kind : DecisionKind.values()) {
            assertThat(RiskPermissions.forDecision(kind).check(Optional.empty())).isEqualTo(notIdentified);
        }
    }

    @Test
    @DisplayName("a refused role is named in the refusal, and the caller is not (BR-02)")
    void shouldNameTheRoleAndNotTheCaller() {
        var refusal = RiskPermissions.EVALUATE.check(Callers.optionalOf(ActorRole.FRONT_OFFICE));

        assertThat(refusal).isEqualTo(Result.failure(new AccessRefusal.PermissionDenied(ActorRole.FRONT_OFFICE)));
    }

    @Test
    @DisplayName("the front-office status never refuses a role: its only refusal is not identified (BR-41)")
    void shouldNeverRefuseARoleForTheFrontOfficeStatus() {
        for (var role : ActorRole.values()) {
            assertThat(RiskPermissions.FRONT_OFFICE_STATUS.check(Callers.optionalOf(role)).errorToOptional())
                    .isEmpty();
        }
    }
}
