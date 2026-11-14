package com.example.webapp.applications;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** Persistence model: ownership and audit fields are assigned by the server. */
@Document("applications")
public record JobApplication(@Id String id, String userId, String company, String role,
        String postingUrl, String source, String location, String jobDescription,
        ApplicationStatus status, LocalDate appliedOn, Instant createdAt, Instant updatedAt,
        @Version Long version) {}
