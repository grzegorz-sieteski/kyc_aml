package com.gs5.kycaml.risk.adapters.access;

import com.gs5.kycaml.shared.kernel.ActorRole;

/**
 * Why the edge refused a caller before any input was examined.
 *
 * <p>Shared across operations: both refusals are decided from the caller and the operation alone,
 * never from the request body. Neither reveals anything about any organisation's data.
 */
public sealed interface AccessRefusal {

    /** BR-01 — no caller is known. */
    record CallerNotIdentified() implements AccessRefusal {
    }

    /**
     * BR-02 — the caller's role may not perform this operation. It carries the role, which
     * identifies nobody, and not the caller.
     */
    record PermissionDenied(ActorRole role) implements AccessRefusal {
    }
}
