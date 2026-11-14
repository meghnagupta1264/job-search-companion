package com.example.webapp.activities;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public class ActivityStore {
    private final MongoTemplate mongo;
    private boolean ready;
    public ActivityStore(MongoTemplate mongo) { this.mongo = mongo; }

    public synchronized void prepareIndexes() {
        if (ready) return;
        // Create collection/index before opening a transaction: DDL is not part of the write.
        mongo.indexOps(Activity.class).createIndex(new Index().on("userId", Sort.Direction.ASC)
                .on("applicationId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
                .on("_id", Sort.Direction.DESC).named("owner_application_timeline"));
        ready = true;
    }

    public Activity append(Activity event) { return mongo.insert(event); }

    public Result list(String owner, String applicationId, int page, int size) {
        Criteria owned = Criteria.where("userId").is(owner).and("applicationId").is(applicationId);
        long total = mongo.count(Query.query(owned), Activity.class);
        var query = Query.query(owned).with(Sort.by(Sort.Direction.DESC, "createdAt", "_id"))
                .skip((long) page * size).limit(size);
        return new Result(mongo.find(query, Activity.class), total);
    }
    public record Result(List<Activity> items, long total) {}
}
