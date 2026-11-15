package com.example.webapp.actions;

import com.example.webapp.account.CurrentAccount;
import com.example.webapp.activities.Activity;
import com.example.webapp.activities.ActivityStore;
import com.example.webapp.applications.ApplicationService;
import com.example.webapp.applications.ApplicationStore;
import com.example.webapp.applications.JobApplication;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ActionService {
    private final ActionStore actions;
    private final ApplicationStore applications;
    private final ActivityStore events;
    private final CurrentAccount owner;
    private final TransactionTemplate transactions;
    public ActionService(ActionStore actions, ApplicationStore applications, ActivityStore events,
                         CurrentAccount owner, PlatformTransactionManager manager) {
        this.actions=actions;this.applications=applications;this.events=events;this.owner=owner;
        transactions=new TransactionTemplate(manager);
    }
    public ActionResponse get(String id) { return response(ownedAction(id)); }
    public Page list(String applicationId, JobAction.Status status, int page, int size) {
        if(applicationId!=null)ownedApplication(applicationId);
        if(page<0||page>100000||size<1||size>50)throw new IllegalArgumentException("Choose a valid action page and size (1–50).");
        var found=actions.list(owner.id(),applicationId,status,page,size);
        return new Page(found.items().stream().map(this::response).toList(),page,size,found.total(),(int)Math.ceil((double)found.total()/size));
    }
    public enum AgendaSection { OVERDUE, TODAY, UPCOMING }
    public record AgendaItem(ActionResponse action, String company, String role) {}
    public record AgendaPage(List<AgendaItem> items, int page, int totalPages, long totalElements,
                             LocalDate today, String timezone) {}
    public AgendaPage agenda(AgendaSection section, int page) {
        if (page < 0 || page > 100000) throw new IllegalArgumentException("Choose a valid dashboard page.");
        var today = LocalDate.now(owner.timezone());
        // Upcoming includes tomorrow through day seven; the exclusive upper bound avoids overlap.
        LocalDate from = switch (section) { case OVERDUE -> null; case TODAY -> today; case UPCOMING -> today.plusDays(1); };
        LocalDate until = switch (section) { case OVERDUE -> today; case TODAY -> today.plusDays(1); case UPCOMING -> today.plusDays(8); };
        var found = actions.agenda(owner.id(), from, until, page, 20);
        var parents = applications.findOwnedBatch(found.items().stream().map(JobAction::applicationId).distinct().toList(), owner.id())
                .stream().collect(java.util.stream.Collectors.toMap(JobApplication::id, app -> app));
        var items = found.items().stream().map(action -> {
            var app = parents.get(action.applicationId());
            return new AgendaItem(response(action), app == null ? "Unavailable application" : app.company(), app == null ? "" : app.role());
        }).toList();
        return new AgendaPage(items, page, (int) Math.ceil(found.total() / 20.0), found.total(), today, owner.timezone().getId());
    }
    public ActionResponse create(String applicationId, ActionRequest request) {
        active(ownedApplication(applicationId));
        Instant scheduled=scheduled(request);
        prepare();
        return transactions.execute(transaction->{
            var app=ownedApplication(applicationId);active(app);touch(app);
            Instant now=Instant.now();
            var saved=actions.insert(new JobAction(null,owner.id(),app.id(),text(request.title()),request.type(),request.dueOn(),scheduled,
                    text(request.details()),JobAction.Status.PENDING,null,null,"",now,now,null));
            events.append(Activity.action(Activity.Type.ACTION_CREATED,null,saved));
            return response(saved);
        });
    }
    public ActionResponse update(String id, ActionRequest request) {
        ownedAction(id);Instant scheduled=scheduled(request);prepare();
        return transactions.execute(transaction->{
            var current=ownedAction(id);version(current,request.version());pending(current);
            var app=ownedApplication(current.applicationId());active(app);
            if(current.title().equals(text(request.title()))&&current.type()==request.type()&&current.dueOn().equals(request.dueOn())
                    &&Objects.equals(current.scheduledAt(),scheduled)&&current.details().equals(text(request.details())))return response(current);
            touch(app);
            var saved=actions.save(new JobAction(current.id(),current.userId(),current.applicationId(),text(request.title()),request.type(),
                    request.dueOn(),scheduled,text(request.details()),current.status(),null,null,"",current.createdAt(),Instant.now(),current.version()));
            events.append(Activity.action(Activity.Type.ACTION_UPDATED,current,saved));return response(saved);
        });
    }
    public ActionResponse finish(String id, Long version, boolean cancel) {
        ownedAction(id);prepare();
        return transactions.execute(transaction->{
            var current=ownedAction(id);version(current,version);pending(current);
            var app=ownedApplication(current.applicationId());if(!cancel)active(app);touch(app);
            Instant now=Instant.now();
            var saved=actions.save(new JobAction(current.id(),current.userId(),current.applicationId(),current.title(),current.type(),current.dueOn(),
                    current.scheduledAt(),current.details(),cancel?JobAction.Status.CANCELLED:JobAction.Status.COMPLETED,
                    cancel?null:now,cancel?now:null,cancel?"Cancelled manually":"",current.createdAt(),now,current.version()));
            events.append(Activity.action(cancel?Activity.Type.ACTION_CANCELLED:Activity.Type.ACTION_COMPLETED,current,saved));
            return response(saved);
        });
    }
    private Instant scheduled(ActionRequest request) {
        String time=text(request.scheduledTime());
        if(time.isEmpty())return null;
        if(request.type()!=JobAction.Type.INTERVIEW)throw new InvalidAction(Map.of("scheduledTime","Only interviews can have an exact time."));
        LocalDateTime local=request.dueOn().atTime(LocalTime.parse(time));
        var offsets=owner.timezone().getRules().getValidOffsets(local);
        // Do not silently shift a DST gap or guess which occurrence of a repeated time was intended.
        if(offsets.size()!=1)throw new InvalidAction(Map.of("scheduledTime","This local time is missing or ambiguous in your timezone. Choose an unambiguous time."));
        return local.toInstant(offsets.getFirst());
    }
    private void prepare() { applications.prepareIndexes();actions.prepareIndexes();events.prepareIndexes(); }
    private JobApplication ownedApplication(String id) {
        return applications.findOwned(id,owner.id()).orElseThrow(ApplicationService.MissingApplication::new);
    }
    private JobAction ownedAction(String id) {
        return actions.owned(id,owner.id()).orElseThrow(MissingAction::new);
    }
    private void active(JobApplication app) {
        if(ActionLifecycle.closed(app.status()))throw new ActionConflict("This application is closed. Reopen it before adding or completing actions.");
    }
    private void pending(JobAction action) {
        if(action.status()!=JobAction.Status.PENDING)throw new ActionConflict("Only pending actions can be edited, completed, or cancelled.");
    }
    private void version(JobAction action,Long version) {
        if(version==null)throw new InvalidAction(Map.of("version","Reload the action before editing."));
        if(!version.equals(action.version()))throw new ActionConflict("This action changed in another tab. Reload actions before trying again.");
    }
    private void touch(JobApplication app) {
        // Every action write also updates the parent version. This write fence stops a
        // concurrent create/reschedule from committing after terminal-status cancellation.
        applications.save(new JobApplication(app.id(),app.userId(),app.company(),app.role(),app.postingUrl(),app.source(),app.location(),
                app.jobDescription(),app.status(),app.appliedOn(),app.createdAt(),Instant.now(),app.version()));
    }
    private ActionResponse response(JobAction action) { return ActionResponse.from(action,owner.timezone()); }
    private static String text(String value) { return value==null?"":value.trim(); }
    public record Page(List<ActionResponse> items,int page,int size,long totalElements,int totalPages) {}
    public static class MissingAction extends RuntimeException {}
    public static class ActionConflict extends RuntimeException { public ActionConflict(String message){super(message);} }
    public static class InvalidAction extends RuntimeException {
        public final Map<String,String> fields;public InvalidAction(Map<String,String> fields){this.fields=fields;}
    }
}
