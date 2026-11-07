package com.gs5.kycaml.shared.kernel;

/**
 * Authorisation role carried by {@link ActorContext}.
 *
 * <p>Deliberately a plain enum with no framework annotation: it is read inside {@code domain/},
 * which must stay pure Java.
 */
public enum ActorRole {

    /** May read, never decides. */
    VIEWER,

    /** May decide within their own authority limit. */
    UNDERWRITER,

    /** May decide without an authority limit. */
    SENIOR_UNDERWRITER;

    public boolean canDecide() {
        return this == UNDERWRITER || this == SENIOR_UNDERWRITER;
    }
}
