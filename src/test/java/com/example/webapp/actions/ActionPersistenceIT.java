package com.example.webapp.actions;

import com.example.webapp.account.AccountPrincipal;
import com.example.webapp.activities.*;
import com.example.webapp.applications.*;
import com.example.webapp.support.IsolatedMongoIntegrationTest;
import java.time.*;
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

/** Isolated replica-set tests prove action ownership, timezone rules, and atomic lifecycle writes. */
@SpringBootTest(properties="spring.mongodb.uri=mongodb://localhost:27017/job_search_companion_actions_it?replicaSet=rs0&serverSelectionTimeoutMS=5000")
@AutoConfigureMockMvc
class ActionPersistenceIT extends IsolatedMongoIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper json;
    @MockitoSpyBean ActivityStore events;
    @MockitoSpyBean ApplicationStore applications;
    @BeforeEach @AfterEach void clean() {
        reset(events,applications);
        mongo.remove(new Query(),JobAction.class);mongo.remove(new Query(),JobApplication.class);mongo.remove(new Query(),Activity.class);
    }
    private RequestPostProcessor owner(String id){return owner(id,"Asia/Kolkata");}
    private RequestPostProcessor owner(String id,String zone){var p=new AccountPrincipal(id,id,"unused",zone);return authentication(UsernamePasswordAuthenticationToken.authenticated(p,null,p.getAuthorities()));}
    private ApplicationResponse app() throws Exception {
        var response=mvc.perform(post("/api/applications").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"company\":\"Acme\",\"role\":\"Engineer\",\"status\":\"SAVED\"}")).andExpect(status().isCreated()).andReturn();
        return json.readValue(response.getResponse().getContentAsString(),ApplicationResponse.class);
    }
    private ActionRequest input(String title,JobAction.Type type,LocalDate due,String time,Long version){return new ActionRequest(title,type,due,time,"Useful instructions",version);}
    private ActionResponse create(String appId,ActionRequest body) throws Exception {
        var result=mvc.perform(post("/api/applications/"+appId+"/actions").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isCreated()).andExpect(jsonPath("$.userId").doesNotExist()).andReturn();
        return json.readValue(result.getResponse().getContentAsString(),ActionResponse.class);
    }
    private JobApplication parent(String id){return mongo.findById(id,JobApplication.class);}
    private long events(){return mongo.count(new Query(),Activity.class);}
    private void terminal(String appId,ApplicationStatus status) throws Exception {
        mvc.perform(patch("/api/applications/"+appId+"/status").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StatusRequest(status,parent(appId).version(),LocalDate.of(2026,11,10)))))
                .andExpect(status().isOk());
    }

    @Test void dashboardGroupsBoundariesAndHidesFinishedAndOtherOwners() throws Exception {
        var parent = app();
        var today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        create(parent.id(), input("Late", JobAction.Type.FOLLOW_UP, today.minusDays(1), "", null));
        create(parent.id(), input("Now", JobAction.Type.INTERVIEW, today, "14:30", null));
        create(parent.id(), input("Tomorrow", JobAction.Type.OTHER, today.plusDays(1), "", null));
        create(parent.id(), input("Day seven", JobAction.Type.OTHER, today.plusDays(7), "", null));
        create(parent.id(), input("Outside", JobAction.Type.OTHER, today.plusDays(8), "", null));
        var done = create(parent.id(), input("Done", JobAction.Type.OTHER, today, "", null));
        mvc.perform(post("/api/actions/" + done.id() + "/complete").with(owner("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard/actions?section=OVERDUE").with(owner("alice")))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].action.title").value("Late"))
                .andExpect(jsonPath("$.items[0].company").value("Acme")).andExpect(jsonPath("$.items[0].role").value("Engineer"));
        mvc.perform(get("/api/dashboard/actions?section=TODAY").with(owner("alice")))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].action.title").value("Now"))
                .andExpect(jsonPath("$.today").value(today.toString())).andExpect(jsonPath("$.timezone").value("Asia/Kolkata"));
        mvc.perform(get("/api/dashboard/actions?section=UPCOMING").with(owner("alice")))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.items[1].action.title").value("Day seven"));
        mvc.perform(get("/api/dashboard/actions?section=TODAY").with(owner("bob"))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/dashboard/actions?section=TODAY&page=-1").with(owner("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/dashboard/actions?section=BAD").with(owner("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/dashboard/actions?section=TODAY")).andExpect(status().isUnauthorized());
    }

    @Test void dashboardPaginatesAndMovesRescheduledActions() throws Exception {
        var parent = app(); var today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        // Fixtures avoid unrelated transaction overhead while exercising real Mongo range pagination.
        for (int i = 0; i < 21; i++) mongo.insert(new JobAction(null, "alice", parent.id(), "Task " + i,
                JobAction.Type.OTHER, today, null, "", JobAction.Status.PENDING, null, null, "", Instant.now(), Instant.now(), null));
        mvc.perform(get("/api/dashboard/actions?section=TODAY").with(owner("alice")))
                .andExpect(jsonPath("$.items.length()").value(20)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/dashboard/actions?section=TODAY&page=1").with(owner("alice")))
                .andExpect(jsonPath("$.items.length()").value(1));
        var action = create(parent.id(), input("Move me", JobAction.Type.FOLLOW_UP, today.minusDays(1), "", null));
        mvc.perform(patch("/api/actions/" + action.id()).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("Move me", JobAction.Type.FOLLOW_UP, today.plusDays(1), "", action.version()))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/dashboard/actions?section=OVERDUE").with(owner("alice"))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/dashboard/actions?section=UPCOMING").with(owner("alice")))
                .andExpect(jsonPath("$.items[0].action.title").value("Move me"));
    }

    @Test void createsPendingActionWithTrustedOwnershipAndDerivedOverdue() throws Exception {
        var app=app();var due=LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1);
        var action=create(app.id(),input("  Follow up  ",JobAction.Type.FOLLOW_UP,due,"",null));
        assertEquals("Follow up",action.title());assertTrue(action.overdue());assertEquals(JobAction.Status.PENDING,action.status());assertEquals(0L,action.version());
        assertEquals("alice",mongo.findById(action.id(),JobAction.class).userId());assertEquals(1L,parent(app.id()).version());
        assertEquals(2,events());
        mvc.perform(get("/api/actions").with(owner("alice"))).andExpect(jsonPath("$.totalElements").value(1));
        assertTrue(mongo.indexOps(JobAction.class).getIndexInfo().stream().anyMatch(i->i.getName().equals("owner_status_due")));
    }

    @Test void resolvesInterviewTimeInAccountTimezoneAndReschedulesWithSnapshots() throws Exception {
        var app=app();var action=create(app.id(),input("Technical interview",JobAction.Type.INTERVIEW,LocalDate.of(2026,11,20),"14:30",null));
        assertEquals(Instant.parse("2026-11-20T09:00:00Z"),action.scheduledAt());assertEquals("14:30",action.scheduledTime());
        mvc.perform(patch("/api/actions/"+action.id()).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("Technical interview",JobAction.Type.INTERVIEW,LocalDate.of(2026,11,21),"16:00",0L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scheduledAt").value("2026-11-21T10:30:00Z"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/applications/"+app.id()+"/activities").with(owner("alice")))
                .andExpect(jsonPath("$.items[0].type").value("ACTION_UPDATED"))
                .andExpect(jsonPath("$.items[0].beforeAction.dueOn").value("2026-11-20"))
                .andExpect(jsonPath("$.items[0].afterAction.dueOn").value("2026-11-21"));
    }

    @Test void completionIsFinalAndDoesNotCreateAnotherFollowUp() throws Exception {
        var app=app();var action=create(app.id(),input("Follow up",JobAction.Type.FOLLOW_UP,LocalDate.of(2026,11,20),"",null));
        mvc.perform(post("/api/actions/"+action.id()+"/complete").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists()).andExpect(jsonPath("$.overdue").value(false));
        mvc.perform(patch("/api/actions/"+action.id()).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("Changed",JobAction.Type.FOLLOW_UP,LocalDate.of(2026,11,22),"",1L))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/actions/"+action.id()+"/complete").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isConflict());
        assertEquals(1,mongo.count(new Query(),JobAction.class));assertEquals(3,events());
    }

    @Test void manualCancellationPreservesRecordAndHistory() throws Exception {
        var app=app();var action=create(app.id(),input("Preparation",JobAction.Type.PREPARATION,LocalDate.of(2026,11,20),"",null));
        mvc.perform(post("/api/actions/"+action.id()+"/cancel").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("Cancelled manually"));
        mvc.perform(get("/api/applications/"+app.id()+"/actions").with(owner("alice")).param("status","PENDING"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/applications/"+app.id()+"/actions").with(owner("alice")).param("status","CANCELLED"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test void allTerminalStatusesCancelOnlyPendingAndReopenDoesNotRestoreThem() throws Exception {
        for(var status:new ApplicationStatus[]{ApplicationStatus.ACCEPTED,ApplicationStatus.REJECTED,ApplicationStatus.WITHDRAWN}){
            var app=app();var pending=create(app.id(),input("Pending",JobAction.Type.OTHER,LocalDate.of(2026,11,20),"",null));
            var done=create(app.id(),input("Done",JobAction.Type.ASSIGNMENT,LocalDate.of(2026,11,20),"",null));
            mvc.perform(post("/api/actions/"+done.id()+"/complete").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isOk());
            terminal(app.id(),status);
            assertEquals(JobAction.Status.CANCELLED,mongo.findById(pending.id(),JobAction.class).status());
            assertEquals(JobAction.Status.COMPLETED,mongo.findById(done.id(),JobAction.class).status());
            mvc.perform(post("/api/applications/"+app.id()+"/actions").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(input("Forbidden new",JobAction.Type.OTHER,LocalDate.of(2026,11,20),"",null))))
                    .andExpect(status().isConflict());
            terminal(app.id(),ApplicationStatus.APPLIED);
            assertEquals(JobAction.Status.CANCELLED,mongo.findById(pending.id(),JobAction.class).status());
        }
    }

    @Test void fullFormClosureAlsoCancelsPendingActions() throws Exception {
        var app=app();var action=create(app.id(),input("Follow up",JobAction.Type.FOLLOW_UP,LocalDate.of(2026,11,20),"",null));
        var edit=new ApplicationRequest("Acme","Engineer","","","","",ApplicationStatus.REJECTED,LocalDate.of(2026,11,10),parent(app.id()).version());
        mvc.perform(patch("/api/applications/"+app.id()).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(edit))).andExpect(status().isOk());
        assertEquals(JobAction.Status.CANCELLED,mongo.findById(action.id(),JobAction.class).status());
    }

    @Test void failedActionHistoryRollsBackActionAndParentVersion() throws Exception {
        var app=app();doThrow(new DataAccessResourceFailureException("Simulated event failure")).when(events).append(any(Activity.class));
        mvc.perform(post("/api/applications/"+app.id()+"/actions").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("Follow up",JobAction.Type.FOLLOW_UP,LocalDate.of(2026,11,20),"",null))))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0,mongo.count(new Query(),JobAction.class));assertEquals(0L,parent(app.id()).version());assertEquals(1,events());
    }

    @Test void failedAutomaticCancellationRollsBackApplicationActionsAndEvents() throws Exception {
        var app=app();var action=create(app.id(),input("Follow up",JobAction.Type.FOLLOW_UP,LocalDate.of(2026,11,20),"",null));
        doAnswer(invocation->{Activity event=invocation.getArgument(0);if(event.type()==Activity.Type.ACTION_CANCELLED)throw new DataAccessResourceFailureException("Simulated cancellation event failure");return invocation.callRealMethod();}).when(events).append(any(Activity.class));
        mvc.perform(patch("/api/applications/"+app.id()+"/status").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new StatusRequest(ApplicationStatus.REJECTED,parent(app.id()).version(),LocalDate.of(2026,11,10)))))
                .andExpect(status().isServiceUnavailable());
        assertEquals(ApplicationStatus.SAVED,parent(app.id()).status());assertEquals(1L,parent(app.id()).version());
        assertEquals(JobAction.Status.PENDING,mongo.findById(action.id(),JobAction.class).status());assertEquals(2,events());
    }

    @Test void staleEditsAndNoOpEditsDoNotOverwriteOrDuplicateHistory() throws Exception {
        var app=app();var request=input("Follow up",JobAction.Type.FOLLOW_UP,LocalDate.of(2026,11,20),"",null);var action=create(app.id(),request);
        mvc.perform(patch("/api/actions/"+action.id()).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("Follow up",JobAction.Type.FOLLOW_UP,request.dueOn(),"",0L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        mvc.perform(patch("/api/actions/"+action.id()).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("Updated",JobAction.Type.FOLLOW_UP,request.dueOn(),"",0L)))).andExpect(status().isOk());
        mvc.perform(patch("/api/actions/"+action.id()).with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("Stale",JobAction.Type.FOLLOW_UP,request.dueOn(),"",0L)))).andExpect(status().isConflict());
        assertEquals("Updated",mongo.findById(action.id(),JobAction.class).title());assertEquals(3,events());
    }

    @Test void ownerIsolationAndCsrfProtectEveryActionPath() throws Exception {
        var app=app();var action=create(app.id(),input("Private action",JobAction.Type.OTHER,LocalDate.of(2026,11,20),"",null));
        mvc.perform(get("/api/actions/"+action.id()).with(owner("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/applications/"+app.id()+"/actions").with(owner("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/actions").with(owner("bob"))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post("/api/actions/"+action.id()+"/complete").with(owner("bob")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/actions/"+action.id()+"/cancel").with(owner("alice")).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/actions")).andExpect(status().isUnauthorized());
    }

    @Test void validatesRequiredFieldsTypesAndLocalInterviewTimes() throws Exception {
        var app=app();
        mvc.perform(post("/api/applications/"+app.id()+"/actions").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("title"," ","type","OTHER"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.dueOn").exists());
        mvc.perform(post("/api/applications/"+app.id()+"/actions").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("Wrong type",JobAction.Type.FOLLOW_UP,LocalDate.of(2026,11,20),"14:00",null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.scheduledTime").exists());
        for(var local:new String[]{"2026-03-08T02:30","2026-11-01T01:30"}){
            var dateTime=LocalDateTime.parse(local);
            mvc.perform(post("/api/applications/"+app.id()+"/actions").with(owner("alice","America/New_York")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(input("DST interview",JobAction.Type.INTERVIEW,dateTime.toLocalDate(),dateTime.toLocalTime().toString(),null))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.scheduledTime").exists());
        }
        mvc.perform(get("/api/actions").with(owner("alice")).param("size","51")).andExpect(status().isBadRequest());
        assertEquals(0,mongo.count(new Query(),JobAction.class));
    }

    @Test void actionsPaginateInDueOrderAndCompletedActionsAreNeverOverdue() throws Exception {
        var app=app();var past=LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(3);
        var first=create(app.id(),input("Earliest",JobAction.Type.OTHER,past,"",null));
        create(app.id(),input("Later",JobAction.Type.OTHER,past.plusDays(2),"",null));create(app.id(),input("Middle",JobAction.Type.OTHER,past.plusDays(1),"",null));
        mvc.perform(get("/api/applications/"+app.id()+"/actions").with(owner("alice")).param("status","PENDING").param("size","2"))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.items[0].title").value("Earliest"));
        mvc.perform(post("/api/actions/"+first.id()+"/complete").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(jsonPath("$.overdue").value(false));
    }

    @Test void simultaneousClosureAndCreationCannotLeavePendingWorkOnClosedApplication() throws Exception {
        var app=app();var barrier=new java.util.concurrent.CyclicBarrier(2);
        doAnswer(invocation->{barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return invocation.callRealMethod();}).when(applications).save(any(JobApplication.class));
        var threads=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var create=threads.submit(()->mvc.perform(post("/api/applications/"+app.id()+"/actions").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(input("Racing action",JobAction.Type.OTHER,LocalDate.of(2026,11,20),"",null)))).andReturn().getResponse().getStatus());
            var close=threads.submit(()->mvc.perform(patch("/api/applications/"+app.id()+"/status").with(owner("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new StatusRequest(ApplicationStatus.REJECTED,0L,LocalDate.of(2026,11,10))))).andReturn().getResponse().getStatus());
            int created=create.get(20,java.util.concurrent.TimeUnit.SECONDS),closed=close.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue((created==201&&closed==409)||(created==409&&closed==200));
            reset(applications);terminal(app.id(),ApplicationStatus.REJECTED);
            var pending=Query.query(org.springframework.data.mongodb.core.query.Criteria.where("status").is(JobAction.Status.PENDING));
            assertEquals(0,mongo.count(pending,JobAction.class));
        }finally{threads.shutdownNow();}
    }
}
