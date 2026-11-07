package com.gs5.kycaml.risk.adapters.access;

import com.gs5.kycaml.shared.kernel.ActorRole;
import com.gs5.kycaml.shared.kernel.Result;

import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a caller may perform one operation, and refuses with the reason it names.
 *
 * <p>Permissions are values, declared once in {@link RiskPermissions}. The evaluation logic lives
 * here, so no resource and no use case contains a role comparison (BR-02).
 */
public interface Permission {

    /** The caller, if the permission admits it, or the refusal. An absent caller is never admitted. */
    Result<AuthenticatedCaller, AccessRefusal> check(Optional<AuthenticatedCaller> caller);

    /** Any identified caller; no role is consulted, so the only refusal is "not identified". */
    static Permission anyIdentifiedCaller() {
        return Permission::identified;
    }

    /**
     * The caller must be identified and its role must be one of {@code admitted}; any other role
     * is refused as {@link AccessRefusal.PermissionDenied} carrying that role, never the caller.
     */
    static Permission rolesAllowed(Set<ActorRole> admitted) {
        return caller -> identified(caller)
                .ensure(admittedCaller -> admitted.contains(admittedCaller.role()),
                        refused -> new AccessRefusal.PermissionDenied(refused.role()));
    }

    /** BR-01, fail-closed: an absent caller is refused before any role is consulted. */
    private static Result<AuthenticatedCaller, AccessRefusal> identified(Optional<AuthenticatedCaller> caller) {
        return Result.fromOptional(caller, AccessRefusal.CallerNotIdentified::new);
    }
}
