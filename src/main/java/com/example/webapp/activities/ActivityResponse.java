package com.example.webapp.activities;

import com.example.webapp.applications.ApplicationStatus;
import java.time.Instant;

/** HTTP DTO hides ownership while retaining the immutable event snapshot. */
public record ActivityResponse(String id, Activity.Type type, String content,
        ApplicationStatus previousStatus, ApplicationStatus status, Instant createdAt,
        Activity.ActionSnapshot beforeAction, Activity.ActionSnapshot afterAction) {
    public static ActivityResponse from(Activity event) {
        return new ActivityResponse(event.id(), event.type(), event.content(), event.previousStatus(), event.status(), event.createdAt(),
                event.beforeAction(),event.afterAction());
    }
}
