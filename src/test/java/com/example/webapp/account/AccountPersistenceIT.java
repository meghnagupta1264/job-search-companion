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
class AccountPersistenceIT {
    @Autowired AccountRepository accounts;
    @Autowired AccountService service;
    @Autowired PasswordEncoder passwords;

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
