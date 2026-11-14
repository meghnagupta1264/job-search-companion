package com.example.webapp.activities;

import com.example.webapp.account.AccountPrincipal;
import com.example.webapp.applications.*;
import java.time.LocalDate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real replica-set tests prove that status and history commit or roll back together. */
@SpringBootTest(properties="spring.mongodb.uri=mongodb://localhost:27017/job_search_companion_activity_it?replicaSet=rs0&serverSelectionTimeoutMS=5000")
@AutoConfigureMockMvc
// Shared lifecycle drops only the explicitly approved isolated database after this class.
class ActivityPersistenceIT extends com.example.webapp.support.IsolatedMongoIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper json;
    @MockitoSpyBean ActivityStore events;
    @MockitoSpyBean ApplicationStore applications;
    @BeforeEach @AfterEach void clean() {
        reset(events, applications);
        mongo.remove(new Query(), JobApplication.class);
        mongo.remove(new Query(), Activity.class);
    }
    private RequestPostProcessor owner(String id) {
        var principal = new AccountPrincipal(id,id,"unused","Asia/Kolkata");
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
    }
    private ApplicationRequest input(ApplicationStatus status, Long version, LocalDate date) {
        return new ApplicationRequest("Acme","Engineer","https://example.com","LinkedIn","Remote","Description",status,date,version);
    }
    private ApplicationResponse create() throws Exception {
        var response = mvc.perform(post("/api/applications").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input(ApplicationStatus.SAVED,null,null))))
                .andExpect(status().isCreated()).andReturn();
        return json.readValue(response.getResponse().getContentAsString(),ApplicationResponse.class);
    }

    @Test void creationAndNotesAppearAsSafeImmutableEvents() throws Exception {
        var app=create();
        mvc.perform(post("/api/applications/"+app.id()+"/notes").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ActivityController.NoteRequest("  Recruiter called.\n<script>plain text</script>  "))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("NOTE"))
                .andExpect(jsonPath("$.content").value("Recruiter called.\n<script>plain text</script>"))
                .andExpect(jsonPath("$.userId").doesNotExist());
        mvc.perform(get("/api/applications/"+app.id()+"/activities").with(owner("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].type").value("NOTE"))
                .andExpect(jsonPath("$.items[1].type").value("APPLICATION_CREATED"))
                .andExpect(jsonPath("$.items[1].status").value("SAVED"));
        assertEquals(app.version(),mongo.findById(app.id(),JobApplication.class).version());
    }

    @Test void fullEditRecordsOnlyRealStatusChanges() throws Exception {
        var app=create();
        mvc.perform(patch("/api/applications/"+app.id()).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input(ApplicationStatus.APPLIED,app.version(),LocalDate.of(2026,11,10)))))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/applications/"+app.id()).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input(ApplicationStatus.APPLIED,1L,LocalDate.of(2026,11,10)))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/applications/"+app.id()+"/activities").with(owner("alice")))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].previousStatus").value("SAVED"))
                .andExpect(jsonPath("$.items[0].status").value("APPLIED"));
    }

    @Test void statusMovesRequireDatePreserveExistingDateAndRejectStaleCards() throws Exception {
        var app=create();String path="/api/applications/"+app.id()+"/status";
        mvc.perform(patch(path).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StatusRequest(ApplicationStatus.APPLIED,0L,null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.appliedOn").exists());
        mvc.perform(patch(path).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StatusRequest(ApplicationStatus.APPLIED,0L,LocalDate.of(2026,11,10)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(patch(path).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StatusRequest(ApplicationStatus.INTERVIEWING,1L,LocalDate.of(2000,1,1)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.appliedOn").value("2026-11-10"));
        // Same-column drops are no-ops; stale cards cannot silently overwrite a newer move.
        mvc.perform(patch(path).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StatusRequest(ApplicationStatus.INTERVIEWING,2L,null))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(patch(path).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StatusRequest(ApplicationStatus.OFFER,1L,null))))
                .andExpect(status().isConflict());
        assertEquals(3,mongo.count(new Query(),Activity.class));
    }

    @Test void failedHistoryInsertRollsBackStatusAndVersion() throws Exception {
        var app=create();
        // Fail after the application write but before the transaction commits.
        doThrow(new DataAccessResourceFailureException("Simulated history write failure")).when(events).append(any(Activity.class));
        mvc.perform(patch("/api/applications/"+app.id()+"/status").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StatusRequest(ApplicationStatus.APPLIED,0L,LocalDate.of(2026,11,10)))))
                .andExpect(status().isServiceUnavailable());
        var unchanged=mongo.findById(app.id(),JobApplication.class);
        assertEquals(ApplicationStatus.SAVED,unchanged.status());assertEquals(0L,unchanged.version());
        assertEquals(1,mongo.count(new Query(),Activity.class));
    }

    @Test void failedCreationEventLeavesNoOrphanApplication() throws Exception {
        // Index/collection creation happens before the transaction; records still roll back.
        doThrow(new DataAccessResourceFailureException("Simulated creation event failure")).when(events).append(any(Activity.class));
        mvc.perform(post("/api/applications").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input(ApplicationStatus.SAVED,null,null))))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0,mongo.count(new Query(),JobApplication.class));
        assertEquals(0,mongo.count(new Query(),Activity.class));
    }

    @Test void foreignOwnerCannotReadNotesAddNotesOrMoveCards() throws Exception {
        var app=create();String path="/api/applications/"+app.id();
        mvc.perform(get(path+"/activities").with(owner("bob"))).andExpect(status().isNotFound());
        mvc.perform(post(path+"/notes").with(owner("bob")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Foreign note\"}")).andExpect(status().isNotFound());
        mvc.perform(patch(path+"/status").with(owner("bob")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StatusRequest(ApplicationStatus.APPLIED,0L,LocalDate.of(2026,11,10)))))
                .andExpect(status().isNotFound());
        assertEquals(1,mongo.count(new Query(),Activity.class));
    }

    @Test void notesValidateContentAndProtectWritesWithCsrf() throws Exception {
        var app=create();String path="/api/applications/"+app.id()+"/notes";
        for(String text:new String[]{"   ","x".repeat(10001)})
            mvc.perform(post(path).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new ActivityController.NoteRequest(text))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.content").exists());
        mvc.perform(post(path).with(owner("alice")).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"No CSRF\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/applications/"+app.id()+"/activities")).andExpect(status().isUnauthorized());
        assertEquals(1,mongo.count(new Query(),Activity.class));
    }

    @Test void timelinePaginatesAndOldApplicationsHaveNoInventedHistory() throws Exception {
        // Represents a record created before timeline tracking existed.
        var old=mongo.insert(new JobApplication(null,"alice","Legacy","Role","","","","",ApplicationStatus.SAVED,null,
                java.time.Instant.now(),java.time.Instant.now(),null));
        mvc.perform(get("/api/applications/"+old.id()+"/activities").with(owner("alice")))
                .andExpect(jsonPath("$.totalElements").value(0));
        for(int i=0;i<3;i++)mvc.perform(post("/api/applications/"+old.id()+"/notes").with(owner("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ActivityController.NoteRequest("Note "+i))))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/applications/"+old.id()+"/activities").with(owner("alice")).param("size","2").param("page","1"))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].content").value("Note 0"));
        mvc.perform(get("/api/applications/"+old.id()+"/activities").with(owner("alice")).param("size","100"))
                .andExpect(status().isBadRequest());
    }
    @Test void simultaneousMovesReturnOneConflictWithoutDuplicateHistory() throws Exception {
        var app=create();
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        // Hold both transactions after their version read, forcing a real write race.
        doAnswer(invocation -> { barrier.await(10,java.util.concurrent.TimeUnit.SECONDS); return invocation.callRealMethod(); })
                .when(applications).save(any(JobApplication.class));
        var threads=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> move = () -> mvc.perform(patch("/api/applications/"+app.id()+"/status")
                    .with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new StatusRequest(ApplicationStatus.APPLIED,0L,LocalDate.of(2026,11,10)))))
                    .andReturn().getResponse().getStatus();
            var first=threads.submit(move);var second=threads.submit(move);
            var codes=new java.util.ArrayList<>(java.util.List.of(first.get(20,java.util.concurrent.TimeUnit.SECONDS),second.get(20,java.util.concurrent.TimeUnit.SECONDS)));
            codes.sort(Integer::compareTo);assertEquals(java.util.List.of(200,409),codes);
            assertEquals(1L,mongo.findById(app.id(),JobApplication.class).version());
            assertEquals(2,mongo.count(new Query(),Activity.class));
        } finally { threads.shutdownNow(); }
    }

}
