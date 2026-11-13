package com.example.webapp.account;

import com.example.webapp.common.ApiExceptionHandler;
import com.example.webapp.config.SecurityConfiguration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercise the real authentication filters, session, and CSRF lifecycle without a database. */
@WebMvcTest(SessionController.class)
@Import({SecurityConfiguration.class, AccountService.class, ApiExceptionHandler.class, SessionControllerTests.Passwords.class})
class SessionControllerTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @MockitoBean AccountRepository accounts;
    private static final String PASSWORD = "a-test-only-passphrase";

    @TestConfiguration
    static class Passwords {
        // Low cost keeps tests fast; the application configuration uses cost 12.
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(4); }
    }

    @BeforeEach
    void existingOwner() {
        when(accounts.existsById(Account.OWNER_ID)).thenReturn(true);
        when(accounts.findById(Account.OWNER_ID)).thenReturn(Optional.of(new Account(
                Account.OWNER_ID, "meghna", passwords.encode(PASSWORD), "Asia/Kolkata", Instant.now())));
    }

    private TokenSession token(MockHttpSession session) throws Exception {
        var request = get("/api/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        return new TokenSession((MockHttpSession) result.getRequest().getSession(),
                body.get("headerName").asText(), body.get("token").asText());
    }
    private record TokenSession(MockHttpSession session, String header, String token) {}

    @Test
    void loginRestoresSessionRotatesTokenAndLogoutInvalidatesIt() throws Exception {
        var anonymous = token(null);
        String anonymousId = anonymous.session().getId();
        mvc.perform(post("/api/session/login").session(anonymous.session())
                // MockMvc does not infer a valid browser cookie from .session(...).
                .with(request -> {
                    request.setRequestedSessionId(anonymousId);
                    request.setRequestedSessionIdValid(true);
                    return request;
                })
                .header(anonymous.header(), anonymous.token())
                .param("username", "MEGHNA").param("password", PASSWORD)).andExpect(status().isNoContent());
        // Spring changes the ID on login to prevent session fixation.
        assertNotEquals(anonymousId, anonymous.session().getId());
        mvc.perform(get("/api/session").session(anonymous.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("meghna"))
                .andExpect(jsonPath("$.id").value(Account.OWNER_ID))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(post("/api/session/logout").session(anonymous.session())
                .header(anonymous.header(), anonymous.token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        var authenticated = token(anonymous.session());
        mvc.perform(post("/api/session/logout").session(authenticated.session())
                .header(authenticated.header(), authenticated.token())).andExpect(status().isNoContent());
        assertTrue(authenticated.session().isInvalid());
        mvc.perform(get("/api/session")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void invalidCredentialsDoNotCreateAnAuthenticatedSession() throws Exception {
        mvc.perform(post("/api/session/login").with(csrf()).param("username", "meghna").param("password", "incorrect"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void csrfIsRequiredForLoginAndSetup() throws Exception {
        mvc.perform(post("/api/session/login").param("username", "meghna").param("password", PASSWORD))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/session/setup").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verify(accounts, never()).insert(any(Account.class));
    }

    @Test
    void completedSetupCannotReplaceTheOwner() throws Exception {
        mvc.perform(get("/api/session/setup-status")).andExpect(jsonPath("$.setupRequired").value(false));
        mvc.perform(post("/api/session/setup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"someone\",\"password\":\"another-long-password\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETUP_CLOSED"));
        verify(accounts, never()).insert(any(Account.class));
    }

    @Test
    void firstRunStoresAHashAndNormalizesUsername() throws Exception {
        when(accounts.existsById(Account.OWNER_ID)).thenReturn(false);
        when(accounts.insert(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        mvc.perform(get("/api/session/setup-status")).andExpect(jsonPath("$.setupRequired").value(true));
        mvc.perform(post("/api/session/setup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"Meghna\",\"password\":\"a-test-only-passphrase\",\"timezone\":\"Asia/Kolkata\"}"))
                .andExpect(status().isCreated());
        var saved = org.mockito.ArgumentCaptor.forClass(Account.class);
        verify(accounts).insert(saved.capture());
        assertEquals("meghna", saved.getValue().username());
        assertNotEquals(PASSWORD, saved.getValue().passwordHash());
        assertTrue(passwords.matches(PASSWORD, saved.getValue().passwordHash()));
    }

    @Test
    void invalidSetupReturnsFieldErrorsWithoutSaving() throws Exception {
        when(accounts.existsById(Account.OWNER_ID)).thenReturn(false);
        mvc.perform(post("/api/session/setup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\",\"password\":\"short\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.username").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
        verify(accounts, never()).insert(any(Account.class));
    }

    @Test
    void invalidTimezoneAndOversizedUnicodePasswordsAreRejected() throws Exception {
        when(accounts.existsById(Account.OWNER_ID)).thenReturn(false);
        mvc.perform(post("/api/session/setup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new SetupRequest("meghna", PASSWORD, "Not/AZone"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(post("/api/session/setup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new SetupRequest("meghna", "界".repeat(25), "UTC"))))
                .andExpect(status().isBadRequest());
        verify(accounts, never()).insert(any(Account.class));
    }

    @Test
    void databaseFailureIsAnActionableServiceUnavailableError() throws Exception {
        when(accounts.existsById(Account.OWNER_ID)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("internal details"));
        mvc.perform(get("/api/session/setup-status")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
    }
}
