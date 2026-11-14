package com.example.webapp.account;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Future data services obtain ownership from the session, never a request userId. */
@Component
public class CurrentAccount {
    public String id() {
        return principal().accountId();
    }

    // Calendar dates and local interview times use the trusted account timezone.
    public java.time.ZoneId timezone() { return java.time.ZoneId.of(principal().timezone()); }

    private AccountPrincipal principal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
            throw new AccessDeniedException("Sign in to continue.");
        }
        return principal;
    }
}
