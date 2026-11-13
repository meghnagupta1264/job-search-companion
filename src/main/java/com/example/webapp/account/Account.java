package com.example.webapp.account;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Internal database model; never return the password hash through an API. */
@Document("users")
public record Account(@Id String id, String username, String passwordHash, String timezone, Instant createdAt) {
    // MongoDB's unique _id index makes concurrent first-run requests safe.
    public static final String OWNER_ID = "owner";
}
