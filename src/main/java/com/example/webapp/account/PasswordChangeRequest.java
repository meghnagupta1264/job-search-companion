package com.example.webapp.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Credentials are request-only and never included in response DTOs. */
public record PasswordChangeRequest(
        @NotBlank @Size(max=72) String currentPassword,
        @NotBlank @Size(min=12,max=72,message="Use between 12 and 72 characters") String newPassword,
        @NotBlank @Size(max=72) String confirmation) {}
