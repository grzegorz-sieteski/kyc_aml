package com.gs5.kycaml.risk.adapters.access;

import com.gs5.kycaml.risk.domain.DecisionKind;
import com.gs5.kycaml.shared.kernel.ActorRole;

import java.util.Set;

/**
 * The permission table of the {@code risk} module (A-02): who may do what, as data, declared in
 * exactly one place so it can be read and reviewed in one place.
 */
public final class RiskPermissions {

    private static final Set<ActorRole> COMPLIANCE_STAFF =
            Set.of(ActorRole.AML_ANALYST_L1, ActorRole.AML_ANALYST_L2, ActorRole.MLRO);
    private static final Set<ActorRole> SECOND_LINE_AND_MLRO = Set.of(ActorRole.AML_ANALYST_L2, ActorRole.MLRO);

    /** Evaluate customer risk: the onboarding system only. */
    public static final Permission EVALUATE = Permission.rolesAllowed(Set.of(ActorRole.ONBOARDING_SYSTEM));

    /** Compliance view: the AML analysts and the MLRO. */
    public static final Permission COMPLIANCE_VIEW = Permission.rolesAllowed(COMPLIANCE_STAFF);

    /** Front-office status: every identified caller (BR-40). */
    public static final Permission FRONT_OFFICE_STATUS = Permission.anyIdentifiedCaller();

    private static final Permission ESCALATE = Permission.rolesAllowed(COMPLIANCE_STAFF);
    private static final Permission FREEZE_OR_CLEAR = Permission.rolesAllowed(SECOND_LINE_AND_MLRO);

    private RiskPermissions() {
    }

    /** The permission for one kind of decision: freezing and clearing are narrower than escalating. */
    public static Permission forDecision(DecisionKind kind) {
        return switch (kind) {
            case ESCALATE_EDD -> ESCALATE;
            case FREEZE_ASSETS, CLEAR_FALSE_POSITIVE -> FREEZE_OR_CLEAR;
        };
    }
}
