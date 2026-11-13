package com.example.webapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Password hashing is separate from the HTTP security configuration for reuse in setup. */
@Configuration
public class PasswordConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        // A unique salt is generated for every hash; plaintext is never persisted.
        return new BCryptPasswordEncoder(12);
    }
}
