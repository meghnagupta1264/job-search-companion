package com.example.webapp.account;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AccountService implements UserDetailsService {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;

    public AccountService(AccountRepository accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    public boolean setupRequired() { return !accounts.existsById(Account.OWNER_ID); }

    public void createOwner(SetupRequest request) {
        if (!setupRequired()) throw new AccountExistsException();
        // BCrypt limits bytes, not Java characters; reject oversized Unicode passwords.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must be at most 72 UTF-8 bytes.");
        }
        try { ZoneId.of(request.timezone()); }
        catch (DateTimeException exception) {
            throw new IllegalArgumentException("Choose a valid timezone.");
        }
        Account account = new Account(Account.OWNER_ID, normalize(request.username()),
                passwords.encode(request.password()), request.timezone(), Instant.now());
        try {
            // insert (never save/upsert) prevents replacing an existing account in a race.
            accounts.insert(account);
        } catch (DuplicateKeyException exception) {
            throw new AccountExistsException();
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return accounts.findById(Account.OWNER_ID)
                .filter(account -> account.username().equals(normalize(username)))
                .map(AccountPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password."));
    }

    private static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public static class AccountExistsException extends RuntimeException {}
}
