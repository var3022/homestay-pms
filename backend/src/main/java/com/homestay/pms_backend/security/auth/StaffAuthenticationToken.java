package com.homestay.pms_backend.security.auth;

import com.homestay.pms_backend.security.AuthenticatedStaffPrincipal;
import com.homestay.pms_backend.security.jwt.StaffJwtClaims;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.Collections;

/**
 * Verified staff authentication token populated into Spring's SecurityContext.
 *
 * Characteristics:
 * 1. Holds the verified AuthenticatedStaffPrincipal and validated StaffJwtClaims.
 * 2. Granted authorities are strictly empty: a valid JWT proves staff identity,
 *    not administrative permissions or property access.
 * 3. Credentials are null to prevent retention of sensitive data in memory.
 */
public class StaffAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthenticatedStaffPrincipal principal;
    private final StaffJwtClaims claims;

    public StaffAuthenticationToken(AuthenticatedStaffPrincipal principal, StaffJwtClaims claims) {
        super(Collections.emptyList());
        this.principal = principal;
        this.claims = claims;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public AuthenticatedStaffPrincipal getPrincipal() {
        return principal;
    }

    public StaffJwtClaims getClaims() {
        return claims;
    }
}
