package com.example.webapp.account;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

/** Verify real MongoDB persistence and its single-owner constraint in an isolated DB. */
@SpringBootTest(properties = "spring.mongodb.uri=mongodb://localhost:27017/job_search_companion_auth_it?replicaSet=rs0&serverSelectionTimeoutMS=5000")
// Shared lifecycle drops only the explicitly approved isolated database after this class.
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class AccountPersistenceIT extends com.example.webapp.support.IsolatedMongoIntegrationTest {
    @Autowired AccountRepository accounts;
    @Autowired AccountService service;
    @Autowired PasswordEncoder passwords;

    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired tools.jackson.databind.ObjectMapper json;
    private org.springframework.mock.web.MockHttpSession login(String password) throws Exception {
        var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/session/login")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("username","tester").param("password",password))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent()).andReturn();
        return (org.springframework.mock.web.MockHttpSession)result.getRequest().getSession(false);
    }
    private org.springframework.test.web.servlet.ResultActions change(org.springframework.mock.web.MockHttpSession session, PasswordChangeRequest input) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/account/password")
                .session(session).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)));
    }
    @Test void changesPasswordPersistsHashAndRevokesExistingSessions() throws Exception {
        accounts.deleteById(Account.OWNER_ID);
        try {
            service.createOwner(new SetupRequest("tester","original-password","Asia/Kolkata"));
            var first=login("original-password");var second=login("original-password");
            var hash=accounts.findById(Account.OWNER_ID).orElseThrow().passwordHash();
            change(first,new PasswordChangeRequest("original-password","replacement-password","replacement-password"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
            var updated=accounts.findById(Account.OWNER_ID).orElseThrow();
            assertNotEquals(hash,updated.passwordHash());assertTrue(passwords.matches("replacement-password",updated.passwordHash()));
            assertEquals("tester",updated.username());assertEquals("Asia/Kolkata",updated.timezone());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/session").session(second))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/session/login")
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                    .param("username","tester").param("password","original-password"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            assertNotNull(login("replacement-password"));
        } finally {accounts.deleteById(Account.OWNER_ID);}
    }
    @Test void rejectsInvalidPasswordChangesWithoutChangingStoredHash() throws Exception {
        accounts.deleteById(Account.OWNER_ID);
        try {
            service.createOwner(new SetupRequest("tester","original-password","UTC"));var session=login("original-password");
            var hash=accounts.findById(Account.OWNER_ID).orElseThrow().passwordHash();
            for(var input:java.util.List.of(
                    new PasswordChangeRequest("incorrect","replacement-password","replacement-password"),
                    new PasswordChangeRequest("original-password","replacement-password","not-matching"),
                    new PasswordChangeRequest("original-password","original-password","original-password"),
                    new PasswordChangeRequest("original-password","short","short"),
                    new PasswordChangeRequest("original-password","é".repeat(40),"é".repeat(40))))
                change(session,input).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            assertEquals(hash,accounts.findById(Account.OWNER_ID).orElseThrow().passwordHash());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/account/password").session(session)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(new PasswordChangeRequest("original-password","replacement-password","replacement-password"))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/account/password")
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(new PasswordChangeRequest("original-password","replacement-password","replacement-password"))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        } finally {accounts.deleteById(Account.OWNER_ID);}
    }

    @Test void persistsOneOwnerAndRejectsReplacement() {
        accounts.deleteById(Account.OWNER_ID);
        try {
            service.createOwner(new SetupRequest("Meghna", "integration-only-password", "Asia/Kolkata"));
            var stored = accounts.findById(Account.OWNER_ID).orElseThrow();
            assertTrue(passwords.matches("integration-only-password", stored.passwordHash()));
            assertEquals("meghna", stored.username());
            assertFalse(service.setupRequired());
            assertThrows(AccountService.AccountExistsException.class, () -> service.createOwner(
                    new SetupRequest("another", "different-test-password", "UTC")));
            // Even simultaneous inserts cannot bypass MongoDB's unique _id index.
            assertThrows(DuplicateKeyException.class, () -> accounts.insert(new Account(
                    Account.OWNER_ID, "another", "unused", "UTC", Instant.now())));
            assertEquals("meghna", accounts.findById(Account.OWNER_ID).orElseThrow().username());
            assertEquals(Account.OWNER_ID, ((AccountPrincipal) service.loadUserByUsername("MEGHNA")).accountId());
        } finally {
            // Never touch the actual job_search_companion database or its owner.
            accounts.deleteById(Account.OWNER_ID);
        }
    }
}
