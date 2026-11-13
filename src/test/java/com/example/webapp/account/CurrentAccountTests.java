package com.example.webapp.account;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

/** Ownership must come from a trusted principal, including in future service calls. */
class CurrentAccountTests {
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void anonymousCannotResolveAnOwner() {
        assertThrows(AccessDeniedException.class, () -> new CurrentAccount().id());
    }
    @Test void resolvesTheServerPrincipalId() {
        var principal = new AccountPrincipal("owner", "meghna", "unused", "UTC");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        assertEquals("owner", new CurrentAccount().id());
    }

    @Test void unauthenticatedPrincipalCannotResolveAnOwner() {
        // Merely carrying an account-shaped object must not grant ownership.
        var principal = new AccountPrincipal("owner", "meghna", "unused", "UTC");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated(principal, null));
        assertThrows(AccessDeniedException.class, () -> new CurrentAccount().id());
    }
}
