package com.example.webapp.applications;

import com.example.webapp.account.AccountPrincipal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP/filter/database checks use isolated records, never the personal database. */
@SpringBootTest(properties = "spring.mongodb.uri=mongodb://localhost:27017/job_search_companion_applications_it?replicaSet=rs0&serverSelectionTimeoutMS=5000")
@AutoConfigureMockMvc
// Shared lifecycle drops only the explicitly approved isolated database after this class.
class ApplicationPersistenceIT extends com.example.webapp.support.IsolatedMongoIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MongoTemplate mongo;
    @Autowired ApplicationStore store;

    @BeforeEach @AfterEach void cleanFixtures() {
        // Application creation now also records an immutable creation event.
        mongo.remove(new Query(), JobApplication.class);
        mongo.remove(new Query(), com.example.webapp.activities.Activity.class);
    }

    private RequestPostProcessor owner(String id) {
        var principal = new AccountPrincipal(id, id, "unused", "Asia/Kolkata");
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
    private ApplicationRequest input(String company, String role, String source) {
        return new ApplicationRequest(company, role, "https://example.com/jobs/123", source, "Remote",
                "A plain-text description", ApplicationStatus.SAVED, null, null);
    }
    private ApplicationResponse create(String owner, ApplicationRequest body) throws Exception {
        var result = mvc.perform(post("/api/applications").with(owner(owner)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.userId").doesNotExist()).andReturn();
        return json.readValue(result.getResponse().getContentAsString(), ApplicationResponse.class);
    }

    @Test void savesAndRetrievesAJobWithServerAssignedOwnership() throws Exception {
        // Extra owner/audit fields in browser JSON must not control persisted values.
        var body = new LinkedHashMap<String,Object>();
        body.put("company", "  Acme  "); body.put("role", "Backend developer"); body.put("status", "SAVED");
        body.put("userId", "foreign-owner"); body.put("createdAt", "2000-01-01T00:00:00Z");
        var result = mvc.perform(post("/api/applications").with(owner("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.company").value("Acme"))
                .andExpect(jsonPath("$.version").value(0)).andReturn();
        var app = json.readValue(result.getResponse().getContentAsString(), ApplicationResponse.class);
        assertEquals("alice", mongo.findById(app.id(), JobApplication.class).userId());
        mvc.perform(get("/api/applications/" + app.id()).with(owner("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("Backend developer"));
        assertTrue(mongo.indexOps(JobApplication.class).getIndexInfo().stream().anyMatch(i -> i.getName().equals("owner_updated")));
    }

    @Test void foreignOwnerCannotReadUpdateListOrDiscoverSources() throws Exception {
        var app = create("alice", input("Private company", "Private role", "Private source"));
        mvc.perform(get("/api/applications/" + app.id()).with(owner("bob"))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/applications/" + app.id()).with(owner("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("Changed", "Role", "Other"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/applications").with(owner("bob"))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/applications/sources").with(owner("bob"))).andExpect(content().json("[]"));
    }

    @Test void updatePreservesCreationTimeAndRejectsStaleVersions() throws Exception {
        var app = create("alice", input("Acme", "Original role", "LinkedIn"));
        var edit = new ApplicationRequest("Acme", "Updated role", "", "Company website", "Hybrid", "Updated description",
                ApplicationStatus.APPLIED, LocalDate.of(2026,11,10), app.version());
        mvc.perform(patch("/api/applications/" + app.id()).with(owner("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.appliedOn").value("2026-11-10"));
        assertEquals(app.createdAt().toEpochMilli(), mongo.findById(app.id(), JobApplication.class).createdAt().toEpochMilli());
        mvc.perform(patch("/api/applications/" + app.id()).with(owner("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPLICATION_CHANGED"));
        assertEquals("Updated role", mongo.findById(app.id(), JobApplication.class).role());
    }

    @Test void databaseVersionCheckAlsoStopsConcurrentSaveRaces() throws Exception {
        var app = create("alice", input("Acme", "Role", "LinkedIn"));
        var original = mongo.findById(app.id(), JobApplication.class);
        store.save(original);
        // Both writers could have read version zero; only one atomic update can succeed.
        assertThrows(OptimisticLockingFailureException.class, () -> store.save(original));
    }

    @Test void searchIsLiteralCaseInsensitiveFilteredAndPaginated() throws Exception {
        create("alice", input("Acme.*", "Backend [Java]", "LinkedIn"));
        create("alice", input("Beta", "Backend [Java]", "LinkedIn"));
        create("alice", input("Gamma", "Designer", "Company website"));
        create("bob", input("Acme.*", "Backend [Java]", "LinkedIn"));
        mvc.perform(get("/api/applications").with(owner("alice")).param("search", "[java]")
                .param("source", "linkedin").param("status", "SAVED").param("size", "1")
                .param("sort", "company").param("direction", "asc").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.items[0].company").value("Beta"));
        mvc.perform(get("/api/applications").with(owner("alice")).param("search", ".*"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/applications/sources").with(owner("alice")))
                .andExpect(content().json("[\"Company website\",\"LinkedIn\"]"));
    }

    @Test void invalidFieldsUrlsAndMissingDatesReturnValidationErrors() throws Exception {
        mvc.perform(post("/api/applications").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input(" ", "", "LinkedIn"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.company").exists());
        var missingDate = new ApplicationRequest("Acme", "Role", "", "", "", "", ApplicationStatus.APPLIED, null, null);
        mvc.perform(post("/api/applications").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(missingDate)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.appliedOn").exists());
        for (String url : new String[]{"javascript:alert(1)", "ftp://example.com/file", "https://user:password@example.com"}) {
            var invalid = new ApplicationRequest("Acme", "Role", url, "", "", "", ApplicationStatus.SAVED, null, null);
            mvc.perform(post("/api/applications").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.postingUrl").exists());
        }
        assertEquals(0, mongo.count(new Query(), JobApplication.class));
    }

    @Test void invalidFiltersAndMissingVersionsHaveStructuredErrors() throws Exception {
        for (String[] filter : new String[][]{{"page","-1"},{"size","51"},{"sort","passwordHash"},{"status","INVALID"},{"direction","sideways"}}) {
            mvc.perform(get("/api/applications").with(owner("alice")).param(filter[0],filter[1]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").exists());
        }
        var app = create("alice", input("Acme", "Role", "LinkedIn"));
        mvc.perform(patch("/api/applications/" + app.id()).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("Acme", "Updated", "LinkedIn"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.version").exists());
        mvc.perform(get("/api/applications/missing-id").with(owner("alice"))).andExpect(status().isNotFound());
    }

    @Test void writesRequireBothAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/applications").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("Acme", "Role", "")))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/applications").with(owner("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("Acme", "Role", "")))).andExpect(status().isForbidden());
        assertEquals(0, mongo.count(new Query(), JobApplication.class));
    }
}
