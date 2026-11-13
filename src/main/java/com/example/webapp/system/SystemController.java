package com.example.webapp.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    @GetMapping("/api/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "Job Search Companion");
    }

    public record HealthResponse(String status, String application) {}
}
