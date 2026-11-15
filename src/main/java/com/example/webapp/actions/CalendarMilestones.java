package com.example.webapp.actions;

import com.example.webapp.account.CurrentAccount;
import com.example.webapp.activities.Activity;
import com.example.webapp.applications.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.*;

/** Calendar outcomes are read from known application dates and immutable history, never guessed. */
@RestController
@RequestMapping("/api/calendar/milestones")
public class CalendarMilestones {
    private final MongoTemplate mongo;
    private final CurrentAccount owner;
    private final ApplicationStore applications;
    public CalendarMilestones(MongoTemplate mongo, CurrentAccount owner, ApplicationStore applications) {
        this.mongo=mongo;this.owner=owner;this.applications=applications;
    }
    public record Milestone(String id, String applicationId, String company, String role,
                            String label, LocalDate date) {}
    @GetMapping
    public List<Milestone> list(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        if (from.isAfter(to) || java.time.temporal.ChronoUnit.DAYS.between(from,to)>6)
            throw new IllegalArgumentException("Choose a calendar range of at most seven days.");
        var result = new ArrayList<Milestone>();
        // appliedOn remains the factual application date even after later status transitions.
        var applied = mongo.find(Query.query(Criteria.where("userId").is(owner.id())
                .and("appliedOn").gte(from).lte(to).and("status").ne(ApplicationStatus.SAVED)), JobApplication.class);
        for (var app : applied) result.add(new Milestone("applied-"+app.id(),app.id(),app.company(),app.role(),"Applied",app.appliedOn()));
        var interviews = mongo.find(Query.query(Criteria.where("userId").is(owner.id())
                .and("type").is(Activity.Type.ACTION_COMPLETED).and("afterAction.type").is(JobAction.Type.INTERVIEW)
                .and("afterAction.dueOn").gte(from).lte(to)), Activity.class);
        // Outcomes use the status-event timestamp in the account timezone. Interviewing is
        // deliberately excluded: entering that stage does not prove an interview happened.
        var outcomes = mongo.find(Query.query(Criteria.where("userId").is(owner.id())
                .and("type").in(Activity.Type.APPLICATION_CREATED,Activity.Type.STATUS_CHANGED)
                .and("status").in(ApplicationStatus.OFFER,ApplicationStatus.ACCEPTED,ApplicationStatus.REJECTED,ApplicationStatus.WITHDRAWN)
                .and("createdAt").gte(from.atStartOfDay(owner.timezone()).toInstant())
                .lt(to.plusDays(1).atStartOfDay(owner.timezone()).toInstant())), Activity.class);
        var history = new ArrayList<Activity>(interviews);history.addAll(outcomes);
        var parents = applications.findOwnedBatch(history.stream().map(Activity::applicationId).distinct().toList(),owner.id())
                .stream().collect(java.util.stream.Collectors.toMap(JobApplication::id,app->app));
        for(var event:history) {
            var app=parents.get(event.applicationId());if(app==null)continue;
            boolean interview=event.type()==Activity.Type.ACTION_COMPLETED;
            String label=interview?"Interviewed":switch(event.status()) {
                case OFFER -> "Offer received";case ACCEPTED -> "Accepted";case REJECTED -> "Rejected";case WITHDRAWN -> "Withdrawn";
                default -> throw new IllegalStateException("Unexpected calendar outcome");
            };
            result.add(new Milestone("history-"+event.id(),app.id(),app.company(),app.role(),label,
                    interview?event.afterAction().dueOn():event.createdAt().atZone(owner.timezone()).toLocalDate()));
        }
        result.sort(Comparator.comparing(Milestone::date).thenComparing(Milestone::id));
        return result;
    }
}
