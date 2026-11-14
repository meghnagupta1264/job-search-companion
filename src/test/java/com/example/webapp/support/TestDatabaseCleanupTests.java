package com.example.webapp.support;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Destructive cleanup must reject both real databases and unapproved similar names. */
class TestDatabaseCleanupTests {
    @Test void protectsPersonalAndSystemDatabases() {
        for (String name : new String[]{"job_search_companion", "admin", "local", "config", "job_search_companion_unknown_it"}) {
            assertThrows(IllegalStateException.class, () -> IsolatedMongoIntegrationTest.requireTestDatabase(name));
        }
    }
    @Test void acceptsOnlyTheKnownIntegrationDatabases() {
        for (String name : new String[]{"job_search_companion_it", "job_search_companion_auth_it",
                "job_search_companion_applications_it", "job_search_companion_activity_it", "job_search_companion_actions_it"}) {
            assertDoesNotThrow(() -> IsolatedMongoIntegrationTest.requireTestDatabase(name));
        }
    }
}
