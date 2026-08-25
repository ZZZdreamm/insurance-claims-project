package com.kmultan.claims.api;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.auth.Role;
import com.kmultan.claims.infrastructure.security.AuthenticatedUser;
import org.springframework.security.access.AccessDeniedException;

/** Ownership rules that URL patterns cannot express. */
final class ClaimAccessPolicy {

    private ClaimAccessPolicy() {}

    static void assertCanRead(Claim claim, AuthenticatedUser user) {
        if (user.isStaff() || claim.isOwnedBy(user.id())) {
            return;
        }
        throw new AccessDeniedException("Not your claim");
    }

    static void assertCanWithdraw(Claim claim, AuthenticatedUser user) {
        if (user.hasAnyRole(Role.ADJUSTER, Role.ADMIN) || claim.isOwnedBy(user.id())) {
            return;
        }
        throw new AccessDeniedException("Not your claim");
    }

    static void assertHoldsReview(Claim claim, AuthenticatedUser user) {
        if (user.hasAnyRole(Role.ADMIN)) {
            return;
        }
        if (claim.getReviewAssignee() == null) {
            throw new AccessDeniedException("Claim the review first");
        }
        if (!claim.getReviewAssignee().equals(user.username())) {
            throw new AccessDeniedException("Review is held by " + claim.getReviewAssignee());
        }
    }
}
