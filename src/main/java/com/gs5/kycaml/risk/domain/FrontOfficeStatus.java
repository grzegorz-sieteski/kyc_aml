package com.gs5.kycaml.risk.domain;

/**
 * The only two things a front-office employee may be shown (BR-40, A-28). Yellow, Orange and Red
 * all produce {@link #VERIFICATION_PENDING}, so they cannot be told apart.
 */
public enum FrontOfficeStatus {

    ACTIVE("Account active - no operational restrictions."),
    VERIFICATION_PENDING("Standard administrative verification of documentation is in progress.");

    private final String message;

    FrontOfficeStatus(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
