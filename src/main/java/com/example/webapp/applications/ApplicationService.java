package com.example.webapp.applications;

import com.example.webapp.account.CurrentAccount;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import com.example.webapp.activities.Activity;
import com.example.webapp.activities.ActivityStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Business rules stay independent of HTTP and use only the authenticated owner. */
@Service
public class ApplicationService {
    private final ApplicationStore store;
    private final CurrentAccount owner;
    private final ActivityStore events;
    private final com.example.webapp.actions.ActionLifecycle actionLifecycle;
    private final TransactionTemplate transactions;
    private static final Set<String> SORT_FIELDS = Set.of("updatedAt", "createdAt", "company", "role", "appliedOn", "status");
    public ApplicationService(ApplicationStore store, CurrentAccount owner, ActivityStore events,
                              PlatformTransactionManager manager, com.example.webapp.actions.ActionLifecycle actionLifecycle) {
        this.store = store; this.owner = owner; this.events = events;
        this.transactions = new TransactionTemplate(manager);
        this.actionLifecycle = actionLifecycle;
    }

    public ApplicationResponse create(ApplicationRequest request) {
        validate(request);
        prepareWrites();
        return transactions.execute(transaction -> {
            Instant now = Instant.now();
            var created = store.insert(build(null, owner.id(), request, now, now, null));
            events.append(new Activity(null, created.userId(), created.id(), Activity.Type.APPLICATION_CREATED,
                    "", null, created.status(), now));
            return ApplicationResponse.from(created);
        });
    }

    public ApplicationResponse get(String id) { return ApplicationResponse.from(owned(id)); }

    public ApplicationResponse update(String id, ApplicationRequest request) {
        // Check ownership before exposing validation details or preparing write infrastructure.
        owned(id);
        validate(request);
        prepareWrites();
        return transactions.execute(transaction -> {
            JobApplication current = owned(id);
            checkVersion(current, request.version());
            var saved = store.save(build(current.id(), current.userId(), request,
                    current.createdAt(), Instant.now(), current.version()));
            recordStatus(current, saved);
            return ApplicationResponse.from(saved);
        });
    }

    public ApplicationResponse changeStatus(String id, StatusRequest request) {
        owned(id);
        prepareWrites();
        return transactions.execute(transaction -> {
            var current = owned(id);
            checkVersion(current, request.version());
            // A drop back into the same column is a true no-op, without duplicate history.
            if (current.status() == request.status()) return ApplicationResponse.from(current);
            var date = current.appliedOn() != null ? current.appliedOn() : request.appliedOn();
            if (request.status() != ApplicationStatus.SAVED && date == null)
                throw new InvalidApplication(Map.of("appliedOn", "Add the application date for this status."));
            var saved = store.save(new JobApplication(current.id(), current.userId(), current.company(), current.role(),
                    current.postingUrl(), current.source(), current.location(), current.jobDescription(), request.status(),
                    date, current.createdAt(), Instant.now(), current.version()));
            recordStatus(current, saved);
            return ApplicationResponse.from(saved);
        });
    }

    private void prepareWrites() {
        // Collections/indexes must exist before a multi-document transaction starts.
        store.prepareIndexes();
        events.prepareIndexes();
        actionLifecycle.prepareIndexes();
    }

    private void checkVersion(JobApplication current, Long version) {
        if (version == null) throw new InvalidApplication(Map.of("version", "Reload the application before editing."));
        if (!version.equals(current.version())) throw new StaleApplication();
    }

    private void recordStatus(JobApplication before, JobApplication after) {
        if (before.status() != after.status()) events.append(new Activity(null, after.userId(), after.id(),
                Activity.Type.STATUS_CHANGED, "", before.status(), after.status(), after.updatedAt()));
        // Pending-action cancellation and its events join the same parent transaction.
        actionLifecycle.cancelPending(after);
    }

    public ApplicationPage list(String search, ApplicationStatus status, String source, int page, int size,
                                String sort, String direction) {
        if (page < 0 || page > 100000 || size < 1 || size > 50) throw new IllegalArgumentException("Choose a page size between 1 and 50 and a valid page number.");
        if (search.length() > 120 || source.length() > 80) throw new IllegalArgumentException("Search or source filter is too long.");
        if (!SORT_FIELDS.contains(sort)) throw new IllegalArgumentException("Unsupported sort field.");
        Sort.Direction order;
        try { order = Sort.Direction.fromString(direction); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Sort direction must be asc or desc."); }
        var result = store.search(owner.id(), search.trim(), status, source.trim(), page, size, sort, order);
        return new ApplicationPage(result.items().stream().map(ApplicationResponse::from).toList(),
                page, size, result.total(), (int) Math.ceil((double) result.total() / size));
    }

    public List<String> sources() { return store.sources(owner.id()); }

    private JobApplication owned(String id) {
        // Same 404 for absent and foreign-owned records avoids disclosing another user's data.
        return store.findOwned(id, owner.id()).orElseThrow(MissingApplication::new);
    }

    private void validate(ApplicationRequest request) {
        if (request.status() != ApplicationStatus.SAVED && request.appliedOn() == null)
            throw new InvalidApplication(Map.of("appliedOn", "Add the application date for this status."));
        String url = text(request.postingUrl());
        if (!url.isEmpty()) {
            try {
                URI parsed = URI.create(url);
                if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))
                        || parsed.getHost() == null || parsed.getUserInfo() != null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                throw new InvalidApplication(Map.of("postingUrl", "Enter a complete http:// or https:// URL without embedded credentials."));
            }
        }
    }

    private JobApplication build(String id, String userId, ApplicationRequest request, Instant created, Instant updated, Long version) {
        return new JobApplication(id, userId, text(request.company()), text(request.role()), text(request.postingUrl()),
                text(request.source()), text(request.location()), text(request.jobDescription()), request.status(),
                request.appliedOn(), created, updated, version);
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    public record ApplicationPage(List<ApplicationResponse> items, int page, int size, long totalElements, int totalPages) {}
    public static class MissingApplication extends RuntimeException {}
    public static class StaleApplication extends RuntimeException {}
    public static class InvalidApplication extends RuntimeException {
        public final Map<String, String> fields;
        public InvalidApplication(Map<String, String> fields) { this.fields = fields; }
    }
}
