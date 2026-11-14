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
    public record Result(List<JobAction> items, long total) {}
}
