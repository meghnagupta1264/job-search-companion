package com.example.webapp.activities;

import com.example.webapp.account.CurrentAccount;
import com.example.webapp.applications.ApplicationService;
import com.example.webapp.applications.ApplicationStore;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** Notes never change an application's version, so adding one does not invalidate an edit form. */
@Service
public class ActivityService {
    private final ActivityStore events;
    private final ApplicationStore applications;
    private final CurrentAccount owner;
    public ActivityService(ActivityStore events, ApplicationStore applications, CurrentAccount owner) {
        this.events = events; this.applications = applications; this.owner = owner;
    }
    private String requireOwned(String id) {
        String userId = owner.id();
        applications.findOwned(id, userId).orElseThrow(ApplicationService.MissingApplication::new);
        return userId;
    }
    public Page list(String id, int page, int size) {
        String userId = requireOwned(id);
        if (page < 0 || page > 100000 || size < 1 || size > 50) throw new IllegalArgumentException("Choose a valid timeline page and size (1–50).");
        var found = events.list(userId, id, page, size);
        return new Page(found.items().stream().map(ActivityResponse::from).toList(), page, size,
                found.total(), (int) Math.ceil((double) found.total() / size));
    }
    public ActivityResponse note(String id, String content) {
        String userId = requireOwned(id);
        events.prepareIndexes();
        return ActivityResponse.from(events.append(new Activity(null, userId, id, Activity.Type.NOTE,
                content.trim(), null, null, Instant.now())));
    }
    public record Page(List<ActivityResponse> items, int page, int size, long totalElements, int totalPages) {}
}
