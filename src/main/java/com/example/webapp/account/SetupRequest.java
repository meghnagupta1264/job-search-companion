package com.example.webapp.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Validate on the backend even when the React form already checks input. */
public record SetupRequest(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,40}",
                message = "Use 3–40 letters, numbers, dots, underscores, or hyphens") String username,
        @NotBlank @Size(min = 12, max = 72,
                message = "Use between 12 and 72 characters") String password,
        @NotBlank @Size(max = 100) String timezone) {}
