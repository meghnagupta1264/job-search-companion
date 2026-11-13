package com.example.webapp.account;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** Password verification and logout run in Spring Security filters, not here. */
@RestController
@RequestMapping("/api")
public class SessionController {
    private final AccountService accounts;
    public SessionController(AccountService accounts) { this.accounts = accounts; }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        // Accessing the deferred token creates the anonymous session when needed.
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/session/setup-status")
    public SetupStatus setupStatus() { return new SetupStatus(accounts.setupRequired()); }

    @PostMapping("/session/setup")
    @ResponseStatus(HttpStatus.CREATED)
    public void setup(@Valid @RequestBody SetupRequest request) { accounts.createOwner(request); }

    @GetMapping("/session")
    public SessionResponse session(@AuthenticationPrincipal AccountPrincipal principal) {
        // Only safe account fields cross the API boundary.
        return new SessionResponse(principal.accountId(), principal.username(), principal.timezone());
    }

    public record CsrfResponse(String headerName, String token) {}
    public record SetupStatus(boolean setupRequired) {}
    public record SessionResponse(String id, String username, String timezone) {}
}
