package com.example.webapp.applications;

import java.time.Instant;
import java.time.LocalDate;

/** Public DTO deliberately omits the internal userId; clients cannot choose an owner. */
public record ApplicationResponse(String id, String company, String role, String postingUrl,
        String source, String location, String jobDescription, ApplicationStatus status,
        LocalDate appliedOn, Instant createdAt, Instant updatedAt, Long version) {
    public static ApplicationResponse from(JobApplication app) {
        return new ApplicationResponse(app.id(), app.company(), app.role(), app.postingUrl(),
                app.source(), app.location(), app.jobDescription(), app.status(), app.appliedOn(),
                app.createdAt(), app.updatedAt(), app.version());
    }
}
