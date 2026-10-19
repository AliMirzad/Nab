package ir.nab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:nab-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","nab.demo=true","nab.upload-dir=./target/test-photos"})
@AutoConfigureMockMvc
class SyncIntegrationTest extends TestDatabase {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired Store store;
    String guard,admin,assignment,device,tour;
    @BeforeEach void setup() throws Exception {
        guard=login("guard","Guard@12345");admin=login("admin","Admin@12345");
        var seed=json.readTree(mvc.perform(get("/api/guard/bootstrap").header("Authorization",guard)).andReturn().getResponse().getContentAsString());
        var first=seed.path("assignments").get(0);var source=store.one("SELECT route_id,guard_id FROM assignment WHERE id=?",first.path("id").asText());
        String body=json.writeValueAsString(Map.of("guardId",source.get("guard_id"),"routeId",source.get("route_id"),"label","Test "+UUID.randomUUID(),"startsAt",Instant.now().minusSeconds(60).toString(),"endsAt",Instant.now().plusSeconds(3600).toString()));
        assignment=json.readTree(mvc.perform(post("/api/admin/assignments").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();
        device=UUID.randomUUID().toString();tour=UUID.randomUUID().toString();
    }
    String login(String username,String password) throws Exception {return "Bearer "+json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",username,"password",password)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("token").asText();}
    Map<String,Object> visitData(int index) {var point=store.decode((String)store.one("SELECT snapshot FROM assignment WHERE id=?",assignment).get("snapshot")).path("checkpoints").get(index);return new LinkedHashMap<>(Map.of("checkpointId",point.path("id").asText(),"checkpointName",point.path("name").asText(),"tagCode",point.path("tagCode").asText(),"stopIndex",index,"method","QR"));}
    Map<String,Object> event(int seq,String type,Map<String,Object> data) {var payload=new LinkedHashMap<String,Object>();if(type.equals("START"))payload.putAll(visitData(0));payload.putAll(data);return new LinkedHashMap<>(Map.of("id",UUID.randomUUID().toString(),"tourId",tour,"assignmentId",assignment,"sequence",seq,"type",type,"capturedAt","2026-10-18T01:00:00Z","data",payload));}
    String batch(List<Map<String,Object>> events) throws Exception{return json.writeValueAsString(Map.of("deviceId",device,"events",events));}
    void send(List<Map<String,Object>> events,int statusCode) throws Exception {mvc.perform(post("/api/guard/sync").header("Authorization",guard).contentType(MediaType.APPLICATION_JSON).content(batch(events))).andExpect(status().is(statusCode));}
    @Test void offlineTourCanReplayWholeBatchWithoutDuplicates() throws Exception {
        var snapshot=store.decode((String)store.one("SELECT snapshot FROM assignment WHERE id=?",assignment).get("snapshot"));
        List<Map<String,Object>> batch=new ArrayList<>();batch.add(event(1,"START",Map.of("locationStatus","UNAVAILABLE")));
        for(int i=0;i<snapshot.path("checkpoints").size();i++){var d=visitData(i);d.put("location",Map.of("latitude",35.7,"longitude",51.3,"accuracy",9000,"fixAt","2020-01-01T00:00:00Z"));batch.add(event(i+2,"VISIT",d));}
        batch.add(event(batch.size()+1,"FINISH",Collections.singletonMap("location",null)));
        send(batch,200);send(batch,200);
        assertThat(store.db.queryForObject("SELECT COUNT(*) FROM patrol_event WHERE tour_id=?",Integer.class,tour)).isEqualTo(batch.size());
        assertThat(store.one("SELECT status FROM patrol_tour WHERE id=?",tour).get("status")).isEqualTo("FINISHED");
    }
    @Test void invalidBatchRollsBackIncludingStart() throws Exception {
        send(List.of(event(1,"START",Map.of()),event(3,"FINISH",Map.of())),409);
        assertThat(store.db.queryForList("SELECT id FROM patrol_tour WHERE id=?",tour)).isEmpty();
    }
    @Test void sameIdWithChangedDataIsRejected() throws Exception {
        var start=event(1,"START",Map.of());send(List.of(start),200);start.put("data",Map.of("changed",true));send(List.of(start),409);
    }
    @Test void anotherDeviceCannotContinueTour() throws Exception {
        send(List.of(event(1,"START",Map.of())),200);device="different-device";send(List.of(event(2,"FINISH",Map.of())),403);
    }
    @Test void rolesAndSessionRevocationAreEnforced() throws Exception {
        mvc.perform(get("/api/admin/users").header("Authorization",guard)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").header("Authorization",guard)).andExpect(status().isOk());
        mvc.perform(get("/api/guard/bootstrap").header("Authorization",guard)).andExpect(status().isUnauthorized());
    }
    @Test void anotherGuardCannotUploadIntoAssignment() throws Exception {
        mvc.perform(post("/api/admin/users").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name","Other","username","other-"+device,"password","Other@12345","role","GUARD")))).andExpect(status().isOk());
        guard=login("other-"+device,"Other@12345");send(List.of(event(1,"START",Map.of())),404);
    }
    @Test void photoReceiptIsIdempotentAndRequiresOwnedIncident() throws Exception {
        var start=event(1,"START",Map.of());var incident=event(2,"INCIDENT",Map.of("note","Broken gate","severity","HIGH","hasPhoto",true));send(List.of(start,incident),200);
        byte[] image=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jS1sAAAAASUVORK5CYII=");
        var file=new MockMultipartFile("file","incident.png","image/png",image);
        var r1=mvc.perform(multipart("/api/photos/"+incident.get("id")).file(file).header("Authorization",guard)).andExpect(status().isOk()).andReturn();
        var r2=mvc.perform(multipart("/api/photos/"+incident.get("id")).file(file).header("Authorization",guard)).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(r1.getResponse().getContentAsString()).get("id")).isEqualTo(json.readTree(r2.getResponse().getContentAsString()).get("id"));
        mvc.perform(multipart("/api/photos/"+start.get("id")).file(file).header("Authorization",guard)).andExpect(status().isBadRequest());
    }
    @Test void outOfOrderVisitAndEarlyFinishNeverBecomeAccepted() throws Exception {
        send(List.of(event(1,"START",Map.of()),event(2,"VISIT",visitData(1))),409);
        assertThat(store.db.queryForList("SELECT id FROM patrol_tour WHERE id=?",tour)).isEmpty();
        send(List.of(event(1,"START",Map.of()),event(2,"VISIT",visitData(0))),200);
        send(List.of(event(3,"FINISH",Map.of())),409);
        assertThat(store.one("SELECT status FROM patrol_tour WHERE id=?",tour).get("status")).isEqualTo("ACTIVE");
    }
    @Test void abortIsRecordedButNotCountedAsCompleted() throws Exception {
        var events=List.of(event(1,"START",Map.of()),event(2,"VISIT",visitData(0)),event(3,"ABORT",Map.of("note","Gate inaccessible")));
        send(events,200);send(events,200);
        assertThat(store.one("SELECT status FROM patrol_tour WHERE id=?",tour).get("status")).isEqualTo("ABORTED");
    }
    @Test void circularRouteRequiresTheSameStartTagAgainAtTheEnd() throws Exception {
        var a=visitData(0);var b=visitData(1);var route=store.one("SELECT r.site_id,a.guard_id FROM assignment a JOIN patrol_route r ON r.id=a.route_id WHERE a.id=?",assignment);
        String routeId=json.readTree(mvc.perform(post("/api/admin/routes").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("siteId",route.get("site_id"),"name","Loop","checkpointIds",List.of(a.get("checkpointId"),b.get("checkpointId"),a.get("checkpointId")),"expectedMinutes",30)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();
        assignment=json.readTree(mvc.perform(post("/api/admin/assignments").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("guardId",route.get("guard_id"),"routeId",routeId,"label","Loop","startsAt",Instant.now().toString(),"endsAt",Instant.now().plusSeconds(3600).toString())))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();
        send(List.of(event(1,"START",Map.of()),event(2,"VISIT",visitData(0)),event(3,"VISIT",visitData(1))),200);
        send(List.of(event(4,"FINISH",Map.of())),409);
        send(List.of(event(4,"VISIT",visitData(2)),event(5,"FINISH",Map.of())),200);
    }
    @Test void weekly24On24OffPlanGeneratesOnlyWorkingHourSlots() throws Exception {
        var source=store.one("SELECT guard_id,route_id FROM assignment WHERE id=?",assignment);
        var input=new LinkedHashMap<>(Map.of("guardId",source.get("guard_id"),"routeId",source.get("route_id"),"label","Weekly","startsAt","2026-10-19T04:30:00Z","endsAt","2026-10-26T04:30:00Z","workHours",24,"restHours",24,"intervalMinutes",120,"windowMinutes",45));
        var response=mvc.perform(post("/api/admin/plans").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isOk()).andReturn();
        var result=json.readTree(response.getResponse().getContentAsString());assertThat(result.path("roundCount").asInt()).isEqualTo(48);
        for(var slot:store.db.queryForList("SELECT * FROM assignment WHERE plan_id=?",result.path("id").asText())) {
            long hour=java.time.Duration.between(Instant.parse("2026-10-19T04:30:00Z"),Instant.parse((String)slot.get("starts_at"))).toHours();
            assertThat(hour%48).isLessThan(24);assertThat(store.decode((String)slot.get("snapshot")).path("routePolicy").asText()).isEqualTo("ORDERED_V1");
        }
        input.put("windowMinutes",180);
        mvc.perform(post("/api/admin/plans").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isBadRequest());
    }
    @Test void nullNestedInputsAndMultibytePasswordsReturnClientErrors() throws Exception {
        mvc.perform(post("/api/guard/sync").header("Authorization",guard).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("deviceId",device,"events",Arrays.asList((Object)null))))).andExpect(status().isBadRequest());
        var a=store.one("SELECT r.site_id FROM assignment a JOIN patrol_route r ON r.id=a.route_id WHERE a.id=?",assignment);
        mvc.perform(post("/api/admin/routes").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("siteId",a.get("site_id"),"name","Invalid","checkpointIds",Arrays.asList(null,"point"),"expectedMinutes",30)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/users").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username","wide-"+device,"name","Wide password","password","ژ".repeat(40),"role","GUARD")))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username","guard","password","ژ".repeat(40))))).andExpect(status().isUnauthorized());
    }
    @Test void simultaneousPhotoRetriesReturnTheSameReceipt() throws Exception {
        var incident=event(2,"INCIDENT",Map.of("note","Concurrent photo","severity","HIGH"));send(List.of(event(1,"START",Map.of()),incident),200);
        byte[] bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jS1sAAAAASUVORK5CYII=");
        String photoPath="/api/photos/"+incident.get("id");var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            java.util.concurrent.Callable<String> call=()->{gate.await();return json.readTree(mvc.perform(multipart(photoPath).file(new MockMultipartFile("file","incident.png","image/png",bytes)).header("Authorization",guard)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();};
            var a=pool.submit(call);var b=pool.submit(call);gate.countDown();assertThat(a.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(store.db.queryForObject("SELECT COUNT(*) FROM photo WHERE event_id=?",Integer.class,incident.get("id"))).isEqualTo(1);
    }
}
