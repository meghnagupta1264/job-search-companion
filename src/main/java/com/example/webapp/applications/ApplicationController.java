package com.example.webapp.applications;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** HTTP boundary: clients send editable fields, never ownership or audit timestamps. */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {
    private final ApplicationService service;
    public ApplicationController(ApplicationService service) { this.service = service; }

    @GetMapping
    public ApplicationService.ApplicationPage list(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "updatedAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.list(search, status, source, page, size, sort, direction);
    }

    @GetMapping("/sources") public List<String> sources() { return service.sources(); }
    @GetMapping("/{id}") public ApplicationResponse get(@PathVariable String id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<ApplicationResponse> create(@Valid @RequestBody ApplicationRequest request) {
        var created = service.create(request);
        return ResponseEntity.created(URI.create("/api/applications/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    public ApplicationResponse update(@PathVariable String id, @Valid @RequestBody ApplicationRequest request) {
        return service.update(id, request);
    }

    // Board moves and other status controls reuse exactly the same version/history rules.
    @PatchMapping("/{id}/status")
    public ApplicationResponse changeStatus(@PathVariable String id, @Valid @RequestBody StatusRequest request) {
        return service.changeStatus(id, request);
    }
}
