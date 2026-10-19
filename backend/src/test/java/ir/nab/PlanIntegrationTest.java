package ir.nab;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:weekly-plan-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","nab.demo=true","nab.upload-dir=./target/plan-photos"})
@AutoConfigureMockMvc
class PlanIntegrationTest extends TestDatabase {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired Store s;
    String admin,guard,guardId,routeId;
    static final String START="2026-10-19T04:30:00Z",END="2026-10-26T04:30:00Z",NEXT_END="2026-11-02T04:30:00Z";
    @BeforeEach void setup()throws Exception{
        admin=login("admin","Admin@12345");guard=login("guard","Guard@12345");
        guardId=(String)s.one("SELECT id FROM app_user WHERE username='guard'").get("id");routeId=(String)s.db.queryForList("SELECT id FROM patrol_route").getFirst().get("id");
    }
    String login(String username,String password)throws Exception{return "Bearer "+json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",username,"password",password)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("token").asText();}
    Map<String,Object> window(String a,String b){return Map.of("days",List.of(1,2,3,4,5,6,7),"startTime",a,"endTime",b);}
    Map<String,Object> input(){var in=new LinkedHashMap<String,Object>();in.putAll(Map.of("guardId",guardId,"routeId",routeId,"label","الگوی آزمایشی "+UUID.randomUUID(),"startsAt",START,"endsAt",END,"workHours",24,"restHours",24,"intervalMinutes",120,"windowMinutes",45));in.put("scheduleMode","WEEKLY");in.put("weeklyWindows",List.of(window("02:00","04:00"),window("06:00","08:00")));return in;}
    JsonNode postJson(String path,Object body,int expected)throws Exception{return json.readTree(mvc.perform(post("/api/admin/plans"+path).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());}
    Map<String,Object> repeat(){return Map.of("label","هفته بعد","startsAt",END,"endsAt",NEXT_END);}
    @Test void previewIsReadOnlyAndCreationMatchesItWithOfflineReadyOrderedSnapshots()throws Exception{
        var in=input();int before=s.db.queryForObject("SELECT COUNT(*) FROM assignment",Integer.class);
        var preview=postJson("/preview",in,200);assertThat(preview.path("roundCount").asInt()).isEqualTo(8);assertThat(preview.path("shiftCount").asInt()).isEqualTo(4);
        assertThat(s.db.queryForObject("SELECT COUNT(*) FROM assignment",Integer.class)).isEqualTo(before);
        var result=postJson("",in,200);var rows=s.db.queryForList("SELECT * FROM assignment WHERE plan_id=? ORDER BY starts_at",result.path("id").asText());
        assertThat(rows).hasSize(8);Set<String> previewStarts=new HashSet<>();for(var day:preview.path("days"))for(var slot:day.path("rounds"))previewStarts.add(slot.path("startsAt").asText());
        assertThat(rows).extracting(r->r.get("starts_at")).containsExactlyInAnyOrderElementsOf(previewStarts);
        for(var row:rows){var snapshot=s.decode((String)row.get("snapshot"));assertThat(snapshot.path("routePolicy").asText()).isEqualTo("ORDERED_V1");assertThat(snapshot.path("checkpoints").size()).isGreaterThan(1);}
        var bootstrap=json.readTree(mvc.perform(get("/api/guard/bootstrap").header("Authorization",guard)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());Set<String> mobileIds=new HashSet<>();bootstrap.path("assignments").forEach(a->mobileIds.add(a.path("id").asText()));assertThat(mobileIds).containsAll(rows.stream().map(r->(String)r.get("id")).toList());
        var plans=json.readTree(mvc.perform(get("/api/admin/plans").header("Authorization",admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertThat(plans.toString()).contains("weeklyWindows","WEEKLY","site_name");
    }
    @Test void reuseKeepsPhaseAndOriginalAssignmentsAndRejectsDuplicateRanges()throws Exception{
        var original=postJson("",input(),200);String id=original.path("id").asText();var old=s.db.queryForList("SELECT id,snapshot FROM assignment WHERE plan_id=? ORDER BY id",id);
        var next=postJson("/"+id+"/repeat",repeat(),200);assertThat(next.path("roundCount").asInt()).isEqualTo(6);assertThat(next.path("shiftCount").asInt()).isEqualTo(3);
        var stored=s.one("SELECT * FROM patrol_plan WHERE id=?",next.path("id").asText());assertThat(stored.get("cycle_anchor")).isEqualTo(START);assertThat(stored.get("template_root_id")).isEqualTo(id);
        assertThat(s.db.queryForList("SELECT id,snapshot FROM assignment WHERE plan_id=? ORDER BY id",id)).isEqualTo(old);
        postJson("/"+id+"/repeat",repeat(),409);
        var third=postJson("/"+next.path("id").asText()+"/repeat",Map.of("label","هفته سوم","startsAt",NEXT_END,"endsAt","2026-11-09T04:30:00Z"),200);
        assertThat(third.path("roundCount").asInt()).isEqualTo(8);assertThat(s.one("SELECT template_root_id FROM patrol_plan WHERE id=?",third.path("id").asText()).get("template_root_id")).isEqualTo(id);
        postJson("/"+next.path("id").asText()+"/repeat",Map.of("label","هم‌پوشان","startsAt","2026-10-30T04:30:00Z","endsAt","2026-11-06T04:30:00Z"),409);
    }
    @Test void invalidPatternsAndDatesFailWithoutPartialWrites()throws Exception{
        int before=s.db.queryForObject("SELECT COUNT(*) FROM patrol_plan",Integer.class);
        List<Map<String,Object>> invalid=new ArrayList<>();
        var a=input();a.put("weeklyWindows",List.of(window("02:00","04:00"),window("03:00","05:00")));invalid.add(a);
        a=input();a.put("weeklyWindows",List.of(Map.of("days",List.of(0),"startTime","02:00","endTime","04:00")));invalid.add(a);
        a=input();a.put("weeklyWindows",List.of(Map.of("days",List.of(),"startTime","02:00","endTime","04:00")));invalid.add(a);
        a=input();a.put("weeklyWindows",Arrays.asList((Object)null));invalid.add(a);
        a=input();a.put("weeklyWindows",List.of(Map.of("days",Arrays.asList((Object)null),"startTime","02:00","endTime","04:00")));invalid.add(a);
        a=input();a.put("weeklyWindows",List.of(window("25:00","04:00")));invalid.add(a);
        a=input();a.put("weeklyWindows",List.of(window("02:00","02:10")));invalid.add(a);
        a=input();a.put("cycleAnchor",END);invalid.add(a);
        a=input();a.put("workHours",0);invalid.add(a);
        a=input();a.put("endsAt",START);invalid.add(a);
        for(var in:invalid)postJson("",in,400);
        assertThat(s.db.queryForObject("SELECT COUNT(*) FROM patrol_plan",Integer.class)).isEqualTo(before);
    }
    @Test void guardsCannotPreviewCreateOrRepeatAdministrativePlans()throws Exception{
        String id=postJson("",input(),200).path("id").asText();
        for(String path:List.of("","/preview","/"+id+"/repeat"))mvc.perform(post("/api/admin/plans"+path).header("Authorization",guard).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(path.endsWith("repeat")?repeat():input()))).andExpect(status().isForbidden());
    }
    @Test void concurrentRepeatsCreateOnlyOneWeek()throws Exception{
        String id=postJson("",input(),200).path("id").asText();String body=json.writeValueAsString(repeat());var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            Callable<Integer> call=()->{gate.await();return mvc.perform(post("/api/admin/plans/"+id+"/repeat").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();};
            var a=pool.submit(call);var b=pool.submit(call);gate.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(s.db.queryForObject("SELECT COUNT(*) FROM patrol_plan WHERE template_root_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(s.db.queryForObject("SELECT COUNT(*) FROM assignment a JOIN patrol_plan p ON p.id=a.plan_id WHERE p.template_root_id=?",Integer.class,id)).isEqualTo(6);
    }
    @Test void zeroRoundPreviewDoesNotAllowPersistingAnEmptyPlan()throws Exception{
        var in=input();in.put("weeklyWindows",List.of(window("07:00","09:00")));int before=s.db.queryForObject("SELECT COUNT(*) FROM patrol_plan",Integer.class);
        assertThat(postJson("/preview",in,200).path("roundCount").asInt()).isZero();postJson("",in,400);assertThat(s.db.queryForObject("SELECT COUNT(*) FROM patrol_plan",Integer.class)).isEqualTo(before);
    }
    @Test void legacyIntervalPlansRemainReusableWithoutConvertingToFixedTimes()throws Exception{
        var in=input();in.remove("scheduleMode");in.remove("weeklyWindows");String id=postJson("",in,200).path("id").asText();
        assertThat(s.db.queryForObject("SELECT COUNT(*) FROM assignment WHERE plan_id=?",Integer.class,id)).isEqualTo(48);
        var next=postJson("/"+id+"/repeat",repeat(),200);assertThat(next.path("roundCount").asInt()).isEqualTo(36);
    }
}
