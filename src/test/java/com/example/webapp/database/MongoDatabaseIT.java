package com.example.webapp.database;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

// Opt-in integration test: ./mvnw verify -Pmongo-integration
// A separate database prevents verification from touching personal application records.
@SpringBootTest(properties = "spring.mongodb.uri=mongodb://localhost:27017/job_search_companion_it?replicaSet=rs0&serverSelectionTimeoutMS=5000")
class MongoDatabaseIT {
    @Autowired MongoTemplate mongo;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void commitsAndRollsBackAcrossCollections() {
        String applications = "transaction_probe_applications";
        String activities = "transaction_probe_activities";
        // Create collections before opening a transaction to avoid implicit DDL.
        mongo.dropCollection(applications);
        mongo.dropCollection(activities);
        mongo.createCollection(applications);
        mongo.createCollection(activities);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            transaction.executeWithoutResult(status -> {
                mongo.insert(new Document("_id", "committed"), applications);
                mongo.insert(new Document("_id", "committed"), activities);
            });
            assertNotNull(mongo.findById("committed", Document.class, applications));
            assertNotNull(mongo.findById("committed", Document.class, activities));

            // A simulated service failure must leave neither partial record behind.
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                mongo.insert(new Document("_id", "rolled-back"), applications);
                mongo.insert(new Document("_id", "rolled-back"), activities);
                throw new IllegalStateException("Simulated failure");
            }));
            assertNull(mongo.findById("rolled-back", Document.class, applications));
            assertNull(mongo.findById("rolled-back", Document.class, activities));
        } finally {
            // Remove only the dedicated integration-test collections.
            mongo.dropCollection(applications);
            mongo.dropCollection(activities);
        }
    }
}
