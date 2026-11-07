package com.gs5.kycaml.shared.kernel;

/**
 * Authorisation role carried by {@link ActorContext}: the five roles of assumption A-02 in
 * {@code docs/specs/customer-risk-scoring-spec.md}, confirmed by the product owner (D-07).
 *
 * <p>Deliberately a plain enum with no behaviour and no framework annotation: it is read inside
 * {@code domain/}, which must stay pure Java. Who may do what is a table in the access adapter,
 * not a method here.
 */
public enum ActorRole {

    /** The automated onboarding process; evaluates customer risk. */
    ONBOARDING_SYSTEM,

    /** First-line AML analyst. */
    AML_ANALYST_L1,

    /** Second-line AML analyst; verifies enhanced due diligence. */
    AML_ANALYST_L2,

    /** Money Laundering Reporting Officer. */
    MLRO,

    /** Branch and call-centre staff; sees the neutral front-office status only. */
    FRONT_OFFICE
}
