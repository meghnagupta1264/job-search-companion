package com.example.webapp.actions;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public class ActionStore {
    private final MongoTemplate mongo;
    private boolean ready;
    public ActionStore(MongoTemplate mongo) { this.mongo = mongo; }
    public synchronized void prepareIndexes() {
        if (ready) return;
        // Prepare collection/indexes outside transactions, also supporting the future Today view.
        var indexes = mongo.indexOps(JobAction.class);
        indexes.createIndex(new Index().on("userId", Sort.Direction.ASC).on("applicationId", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC).on("dueOn", Sort.Direction.ASC).named("owner_application_status_due"));
        indexes.createIndex(new Index().on("userId", Sort.Direction.ASC).on("status", Sort.Direction.ASC)
                .on("dueOn", Sort.Direction.ASC).named("owner_status_due"));
        ready = true;
    }
    public JobAction insert(JobAction action) { return mongo.insert(action); }
    public JobAction save(JobAction action) { return mongo.save(action); }
    public Optional<JobAction> owned(String id, String owner) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("_id").is(id).and("userId").is(owner)), JobAction.class));
    }
    public List<JobAction> pending(String owner, String applicationId) {
        return mongo.find(Query.query(Criteria.where("userId").is(owner).and("applicationId").is(applicationId)
                .and("status").is(JobAction.Status.PENDING)), JobAction.class);
    }
    public Result list(String owner, String applicationId, JobAction.Status status, int page, int size) {
        Criteria criteria = Criteria.where("userId").is(owner);
        if (applicationId != null) criteria.and("applicationId").is(applicationId);
        if (status != null) criteria.and("status").is(status);
        long total = mongo.count(Query.query(criteria), JobAction.class);
        var query = Query.query(criteria).with(Sort.by(Sort.Direction.ASC,"dueOn","scheduledAt","_id"))
                .skip((long)page * size).limit(size);
        return new Result(mongo.find(query, JobAction.class), total);
    }
    /** Date ranges are computed by the server in the account timezone, never by the browser. */
    public Result agenda(String owner, java.time.LocalDate from, java.time.LocalDate until, int page, int size) {
        prepareIndexes();
        Criteria criteria = Criteria.where("userId").is(owner).and("status").is(JobAction.Status.PENDING);
        Criteria dates = criteria.and("dueOn");
        if (from != null) dates.gte(from);
        if (until != null) dates.lt(until);
        long total = mongo.count(Query.query(criteria), JobAction.class);
        var query = Query.query(criteria).with(Sort.by(Sort.Direction.ASC, "dueOn", "scheduledAt", "_id"))
                .skip((long) page * size).limit(size);
        return new Result(mongo.find(query, JobAction.class), total);
    }
    /** Inclusive calendar-date filters; search is scoped before counting and paging. */
    public Result search(String owner, JobAction.Status status, JobAction.Type type, java.time.LocalDate from,
                         java.time.LocalDate to, String search, List<String> parentIds, int page, int size) {
        prepareIndexes();
        var criteria = Criteria.where("userId").is(owner);
        if (status != null) criteria.and("status").is(status);
        if (type != null) criteria.and("type").is(type);
        if (from != null || to != null) {
            var dates = criteria.and("dueOn");
            if (from != null) dates.gte(from);
            if (to != null) dates.lte(to);
        }
        if (!search.isBlank()) criteria.orOperator(
                Criteria.where("title").regex(java.util.regex.Pattern.quote(search), "i"),
                Criteria.where("applicationId").in(parentIds));
        long total = mongo.count(Query.query(criteria), JobAction.class);
        var query = Query.query(criteria).with(Sort.by(Sort.Direction.ASC, "dueOn", "scheduledAt", "_id"))
                .skip((long) page * size).limit(size);
        return new Result(mongo.find(query, JobAction.class), total);
    }
    public record Result(List<JobAction> items, long total) {}
}
