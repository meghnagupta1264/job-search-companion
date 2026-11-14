package com.example.webapp.actions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Overdue is derived at read time in the account timezone, never persisted as a flag. */
public record ActionResponse(String id, String applicationId, String title, JobAction.Type type,
        LocalDate dueOn, Instant scheduledAt, String scheduledTime, String timezone, String details,
        JobAction.Status status, Instant completedAt, Instant cancelledAt, String cancellationReason,
        Instant createdAt, Instant updatedAt, Long version, boolean overdue) {
    public static ActionResponse from(JobAction action, ZoneId zone) {
        String localTime = action.scheduledAt() == null ? "" : action.scheduledAt().atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"));
        return new ActionResponse(action.id(), action.applicationId(), action.title(), action.type(), action.dueOn(),
                action.scheduledAt(), localTime, zone.getId(), action.details(), action.status(), action.completedAt(),
                action.cancelledAt(), action.cancellationReason(), action.createdAt(), action.updatedAt(), action.version(),
                action.status() == JobAction.Status.PENDING && action.dueOn().isBefore(LocalDate.now(zone)));
    }
}
