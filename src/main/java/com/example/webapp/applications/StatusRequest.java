package com.example.webapp.applications;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;

/** Board moves carry only a target status, known version, and a date if one is missing. */
public record StatusRequest(@NotNull ApplicationStatus status, @NotNull @PositiveOrZero Long version,
                            LocalDate appliedOn) {}
