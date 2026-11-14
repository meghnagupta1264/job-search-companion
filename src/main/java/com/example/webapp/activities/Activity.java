package com.example.webapp.activities;

import com.example.webapp.applications.ApplicationStatus;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.example.webapp.actions.JobAction;
import java.time.LocalDate;

/** Append-only timeline entries preserve the status at the moment of each event. */
@Document("activities")
public record Activity(@Id String id, String userId, String applicationId, Type type,
        String content, ApplicationStatus previousStatus, ApplicationStatus status, Instant createdAt,
        ActionSnapshot beforeAction, ActionSnapshot afterAction) {
    // Keep existing application/note calls and previously stored documents compatible.
    public Activity(String id, String userId, String applicationId, Type type, String content,
                    ApplicationStatus previousStatus, ApplicationStatus status, Instant createdAt) {
        this(id,userId,applicationId,type,content,previousStatus,status,createdAt,null,null);
    }
    public enum Type { APPLICATION_CREATED, STATUS_CHANGED, NOTE, ACTION_CREATED, ACTION_UPDATED, ACTION_COMPLETED, ACTION_CANCELLED }

    public static Activity action(Type type, JobAction before, JobAction after) {
        return new Activity(null,after.userId(),after.applicationId(),type,"",null,null,after.updatedAt(),
                before==null?null:ActionSnapshot.from(before),ActionSnapshot.from(after));
    }
    /** Copies retain what changed even if the current action is edited later. */
    public record ActionSnapshot(String id,String title,JobAction.Type type,JobAction.Status status,
            LocalDate dueOn,Instant scheduledAt,String details,String cancellationReason) {
        static ActionSnapshot from(JobAction action) {
            return new ActionSnapshot(action.id(),action.title(),action.type(),action.status(),action.dueOn(),
                    action.scheduledAt(),action.details(),action.cancellationReason());
        }
    }
}
