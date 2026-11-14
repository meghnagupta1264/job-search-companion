package com.example.webapp.activities;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Timeline is owner-protected just like the parent application; there is no event editing API. */
@RestController
@RequestMapping("/api/applications/{id}")
public class ActivityController {
    private final ActivityService service;
    public ActivityController(ActivityService service) { this.service = service; }
    @GetMapping("/activities")
    public ActivityService.Page list(@PathVariable String id, @RequestParam(defaultValue="0") int page,
                                     @RequestParam(defaultValue="20") int size) {
        return service.list(id, page, size);
    }
    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityResponse note(@PathVariable String id, @Valid @RequestBody NoteRequest request) {
        return service.note(id, request.content());
    }
    public record NoteRequest(@NotBlank @Size(max=10000) String content) {}
}
