package com.example.webapp.support;

import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Check isolation before fixtures run, then remove the complete database after the class. */
public abstract class IsolatedMongoIntegrationTest {
    private static final Set<String> ALLOWED = Set.of(
            "job_search_companion_it", "job_search_companion_auth_it",
            "job_search_companion_applications_it", "job_search_companion_activity_it", "job_search_companion_actions_it");

    static void requireTestDatabase(String name) {
        // An exact allowlist prevents an overridden URI from deleting personal/system data.
        if (!ALLOWED.contains(name)) {
            throw new IllegalStateException("Refusing test cleanup outside an approved test database: " + name);
        }
    }

    @BeforeAll
    protected static void verifyIsolation(@Autowired MongoTemplate mongo) {
        requireTestDatabase(mongo.getDb().getName());
    }

    @AfterAll
    protected static void removeTestDatabase(@Autowired MongoTemplate mongo) {
        String name = mongo.getDb().getName();
        requireTestDatabase(name);
        // Removing documents alone leaves collections/indexes visible in Compass.
        mongo.getDb().drop();
    }
}
