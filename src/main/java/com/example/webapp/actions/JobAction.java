package com.example.webapp.actions;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** Actions are separate records so pending work can later be queried across applications. */
@Document("actions")
public record JobAction(@Id String id, String userId, String applicationId, String title,
        Type type, LocalDate dueOn, Instant scheduledAt, String details, Status status,
        Instant completedAt, Instant cancelledAt, String cancellationReason,
        Instant createdAt, Instant updatedAt, @Version Long version) {
    public enum Type { FOLLOW_UP, INTERVIEW, PREPARATION, ASSIGNMENT, OTHER }
    public enum Status { PENDING, COMPLETED, CANCELLED }
}
