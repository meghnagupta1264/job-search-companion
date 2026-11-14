package com.example.webapp.applications;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

/** A full form submission; PATCH replaces editable fields and requires the displayed version. */
public record ApplicationRequest(
        @NotBlank @Size(max = 160) String company,
        @NotBlank @Size(max = 160) String role,
        @Size(max = 2048) String postingUrl,
        @Size(max = 80) String source,
        @Size(max = 160) String location,
        @Size(max = 30000) String jobDescription,
        @NotNull ApplicationStatus status,
        LocalDate appliedOn,
        @PositiveOrZero Long version) {}
