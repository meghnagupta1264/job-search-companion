package com.example.webapp.actions;

import com.example.webapp.activities.Activity;
import com.example.webapp.activities.ActivityStore;
import com.example.webapp.applications.ApplicationStatus;
import com.example.webapp.applications.JobApplication;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Called inside the application's existing transaction, so closure and cancellations are atomic. */
@Component
public class ActionLifecycle {
    private static final Set<ApplicationStatus> CLOSED = Set.of(ApplicationStatus.ACCEPTED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN);
    private final ActionStore actions;
    private final ActivityStore events;
    public ActionLifecycle(ActionStore actions, ActivityStore events) { this.actions = actions; this.events = events; }
    public static boolean closed(ApplicationStatus status) { return CLOSED.contains(status); }
    public void prepareIndexes() { actions.prepareIndexes(); }
    public void cancelPending(JobApplication app) {
        if (!closed(app.status())) return;
        for (var before : actions.pending(app.userId(),app.id())) {
            Instant now = Instant.now();
            var after = actions.save(new JobAction(before.id(),before.userId(),before.applicationId(),before.title(),before.type(),
                    before.dueOn(),before.scheduledAt(),before.details(),JobAction.Status.CANCELLED,null,now,
                    "Application marked " + app.status().name().toLowerCase(),before.createdAt(),now,before.version()));
            events.append(Activity.action(Activity.Type.ACTION_CANCELLED,before,after));
        }
    }
}
