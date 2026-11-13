package com.example.webapp.account;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Carries the trusted owner ID in the server session, not in browser input. */
public record AccountPrincipal(String accountId, String username, String passwordHash, String timezone) implements UserDetails {
    public static AccountPrincipal from(Account account) {
        return new AccountPrincipal(account.id(), account.username(), account.passwordHash(), account.timezone());
    }

    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_OWNER"));
    }
}
