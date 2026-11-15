package com.example.webapp.config;

import com.example.webapp.common.ApiError;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    // Track sessions so credential changes can revoke previously authenticated browsers.
    @Bean
    org.springframework.security.core.session.SessionRegistry sessionRegistry() {
        return new org.springframework.security.core.session.SessionRegistryImpl();
    }
    @Bean
    org.springframework.security.web.session.HttpSessionEventPublisher sessionEvents() {
        return new org.springframework.security.web.session.HttpSessionEventPublisher();
    }
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, org.springframework.security.core.session.SessionRegistry registry) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        // Setup is public only until the fixed owner document exists.
                        .requestMatchers("/api/health", "/api/csrf", "/api/session/setup-status", "/api/session/setup").permitAll()
                        .anyRequest().authenticated())
                // The standard login filter handles session fixation protection and persistence.
                .formLogin(form -> form.loginProcessingUrl("/api/session/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) -> {
                            if (exception instanceof org.springframework.security.authentication.InternalAuthenticationServiceException) {
                                ApiError.write(response, 503, "LOGIN_UNAVAILABLE", "Cannot sign in right now. Check that MongoDB is running.");
                            } else {
                                ApiError.write(response, 401, "INVALID_CREDENTIALS", "Invalid username or password.");
                            }
                        }).permitAll())
                .logout(logout -> logout.logoutUrl("/api/session/logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                // Rotate an existing anonymous session ID when it becomes authenticated.
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId())
                        .maximumSessions(-1).sessionRegistry(registry)
                        .expiredSessionStrategy(event -> ApiError.write(event.getResponse(),401,"SESSION_EXPIRED","Your session expired. Sign in again.")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                ApiError.write(response, 401, "UNAUTHENTICATED", "Sign in to continue."))
                        .accessDeniedHandler((request, response, exception) -> {
                            boolean csrf = exception instanceof org.springframework.security.web.csrf.CsrfException;
                            ApiError.write(response, 403, csrf ? "CSRF_INVALID" : "FORBIDDEN",
                                    csrf ? "Your security token expired. Please try again." : "You cannot access this resource.");
                        }))
                // REST clients do not need saved-request redirects; CSRF stays enabled.
                .requestCache(cache -> cache.disable())
                .build();
    }
}
