package com.example.webapp.actions;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

/** Interview time is local to the account timezone; the server resolves it into UTC. */
public record ActionRequest(@NotBlank @Size(max=160) String title, @NotNull JobAction.Type type,
        @NotNull LocalDate dueOn, @Pattern(regexp="^$|(?:[01][0-9]|2[0-3]):[0-5][0-9]", message="Use a time in HH:mm format") String scheduledTime,
        @Size(max=10000) String details, @PositiveOrZero Long version) {}
