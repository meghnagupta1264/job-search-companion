package com.example.webapp.applications;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** MongoTemplate makes owner-scoped search and pagination explicit in one place. */
@Repository
public class ApplicationStore {
    private final MongoTemplate mongo;
    private boolean indexesReady;
    public ApplicationStore(MongoTemplate mongo) { this.mongo = mongo; }

    public synchronized void prepareIndexes() {
        if (indexesReady) return;
        // Lazy creation lets normal context tests start without a live database.
        var indexes = mongo.indexOps(JobApplication.class);
        indexes.createIndex(new Index().on("userId", Sort.Direction.ASC)
                .on("updatedAt", Sort.Direction.DESC).on("_id", Sort.Direction.DESC).named("owner_updated"));
        indexes.createIndex(new Index().on("userId", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC).on("updatedAt", Sort.Direction.DESC).named("owner_status_updated"));
        indexes.createIndex(new Index().on("userId", Sort.Direction.ASC)
                .on("source", Sort.Direction.ASC).on("updatedAt", Sort.Direction.DESC).named("owner_source_updated"));
        indexesReady = true;
    }

    public JobApplication insert(JobApplication app) {
        prepareIndexes();
        return mongo.insert(app);
    }

    public JobApplication save(JobApplication app) {
        // @Version adds an atomic version predicate and increments it on success.
        return mongo.save(app);
    }

    public Optional<JobApplication> findOwned(String id, String userId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("_id").is(id)
                .and("userId").is(userId)), JobApplication.class));
    }

    /** Resolve one dashboard page in a single owner-scoped query instead of one query per card. */
    public List<JobApplication> findOwnedBatch(List<String> ids, String userId) {
        return mongo.find(Query.query(Criteria.where("userId").is(userId).and("_id").in(ids)), JobApplication.class);
    }

    public SearchResult search(String userId, String search, ApplicationStatus status, String source,
                               int page, int size, String sort, Sort.Direction direction) {
        prepareIndexes();
        Criteria criteria = Criteria.where("userId").is(userId);
        if (status != null) criteria.and("status").is(status);
        if (!source.isBlank()) criteria.and("source").regex("^" + Pattern.quote(source) + "$", "i");
        if (!search.isBlank()) {
            // Treat user text literally: special regex characters cannot change query meaning.
            String literal = Pattern.quote(search);
            criteria.orOperator(Criteria.where("company").regex(literal, "i"),
                    Criteria.where("role").regex(literal, "i"), Criteria.where("location").regex(literal, "i"));
        }
        long total = mongo.count(Query.query(criteria), JobApplication.class);
        // _id is a deterministic tie-breaker when several values/timestamps match.
        Query query = Query.query(criteria).with(Sort.by(direction, sort).and(Sort.by(Sort.Direction.DESC, "_id")))
                .skip((long) page * size).limit(size);
        return new SearchResult(mongo.find(query, JobApplication.class), total);
    }

    public List<String> sources(String userId) {
        prepareIndexes();
        return mongo.findDistinct(Query.query(Criteria.where("userId").is(userId)), "source", JobApplication.class, String.class)
                .stream().filter(value -> !value.isBlank()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public record SearchResult(List<JobApplication> items, long total) {}
}
