package ir.nab;
import com.fasterxml.jackson.databind.JsonNode;
import ir.nab.SecurityConfig.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/guard")
@PreAuthorize("hasRole('GUARD')")
public class SyncController {
    final Store s;SyncController(Store s) {this.s=s;}
    public record Event(@NotNull UUID id,@NotNull UUID tourId,@NotNull UUID assignmentId,@Min(1) int sequence,@Pattern(regexp="START|VISIT|LOCATION|INCIDENT|FINISH|ABORT") @NotNull String type,@NotNull Instant capturedAt,@NotNull JsonNode data) {}
    public record Batch(@NotBlank @Size(max=80) String deviceId,@NotEmpty @Size(max=100) List<@NotNull @Valid Event> events) {}
    @GetMapping("/bootstrap") Map<String,Object> bootstrap(@AuthenticationPrincipal Actor actor) {
        var rows=s.db.queryForList("SELECT * FROM assignment WHERE guard_id=? ORDER BY starts_at DESC",actor.id());List<Object> assignments=new ArrayList<>();
        for(var a:rows) {var result=new LinkedHashMap<String,Object>();result.put("id",a.get("id"));result.put("guardId",actor.id());result.put("label",a.get("label"));result.put("startsAt",a.get("starts_at"));result.put("endsAt",a.get("ends_at"));result.put("snapshot",s.decode((String)a.get("snapshot")));var tours=s.db.queryForList("SELECT id,status,device_id FROM patrol_tour WHERE assignment_id=?",a.get("id"));result.put("serverTour",tours.isEmpty()?null:tours.getFirst());assignments.add(result);}
        return Map.of("user",actor,"assignments",assignments,"serverTime",Store.now(),"syncIntervalSeconds",60);
    }
    // Commit and acknowledgement are one transaction. A lost response can be replayed safely.
    @PostMapping("/sync") @Transactional Map<String,Object> sync(@Valid @RequestBody Batch batch,@AuthenticationPrincipal Actor actor) {
        List<String> accepted=new ArrayList<>();
        for(Event e:batch.events()) {
            String id=e.id().toString(),tourId=e.tourId().toString(),assignmentId=e.assignmentId().toString();var assignment=s.one("SELECT * FROM assignment WHERE id=? AND guard_id=? FOR UPDATE",assignmentId,actor.id());
            var existing=s.db.queryForList("SELECT e.payload,t.guard_id,t.device_id FROM patrol_event e JOIN patrol_tour t ON t.id=e.tour_id WHERE e.id=?",id);JsonNode encoded=s.json.valueToTree(e);
            if(!existing.isEmpty()) {var old=existing.getFirst();if(!actor.id().equals(old.get("guard_id"))||!batch.deviceId().equals(old.get("device_id"))||!s.decode((String)old.get("payload")).equals(encoded)) throw ApiException.conflict("شناسه رویداد با اطلاعات دیگری ثبت شده است");accepted.add(id);continue;}
            var snapshot=s.decode((String)assignment.get("snapshot"));
            validate(e,snapshot);var tours=s.db.queryForList("SELECT * FROM patrol_tour WHERE id=? FOR UPDATE",tourId);
            if(tours.isEmpty()) {
                if(!e.type().equals("START")||e.sequence()!=1) throw ApiException.conflict("ابتدا رویداد شروع گشت باید ارسال شود");
                if(!s.db.queryForList("SELECT id FROM patrol_tour WHERE assignment_id=?",assignmentId).isEmpty()) throw ApiException.conflict("این مأموریت قبلاً روی دستگاه دیگری شروع شده است");
                s.db.update("INSERT INTO patrol_tour VALUES(?,?,?,?,?,?,?,?,?)",tourId,assignmentId,actor.id(),batch.deviceId(),"ACTIVE",e.capturedAt().toString(),null,0,Store.now());tours=s.db.queryForList("SELECT * FROM patrol_tour WHERE id=?",tourId);
            } else if(e.type().equals("START")) throw ApiException.conflict("گشت قبلاً شروع شده است");
            var tour=tours.getFirst();
            if(!actor.id().equals(tour.get("guard_id"))||!assignmentId.equals(tour.get("assignment_id"))||!batch.deviceId().equals(tour.get("device_id"))) throw new ApiException(403,"FORBIDDEN","این گشت متعلق به این حساب و دستگاه نیست");
            if(!"ACTIVE".equals(tour.get("status"))) throw ApiException.conflict("گشت پایان یافته است");
            if(e.sequence()!=((Number)tour.get("last_sequence")).intValue()+1) throw ApiException.conflict("ترتیب رویدادها پیوسته نیست؛ اطلاعات محلی حفظ شد");
            var previous=s.db.queryForList("SELECT payload FROM patrol_event WHERE tour_id=? AND event_type='VISIT' ORDER BY sequence_no",tourId).stream().map(r->s.decode((String)r.get("payload")).path("data")).toList();
            RouteRules.validate(snapshot,previous,e.type(),e.data());
            String received=Store.now();s.db.update("INSERT INTO patrol_event VALUES(?,?,?,?,?,?,?)",id,tourId,e.sequence(),e.type(),e.capturedAt().toString(),received,s.encode(e));
            if(e.type().equals("FINISH")) s.db.update("UPDATE patrol_tour SET status='FINISHED',finished_at=?,last_sequence=?,received_at=? WHERE id=?",e.capturedAt().toString(),e.sequence(),received,tourId);
            else if(e.type().equals("ABORT")) s.db.update("UPDATE patrol_tour SET status='ABORTED',finished_at=?,last_sequence=?,received_at=? WHERE id=?",e.capturedAt().toString(),e.sequence(),received,tourId);
            else s.db.update("UPDATE patrol_tour SET last_sequence=?,received_at=? WHERE id=?",e.sequence(),received,tourId);accepted.add(id);
        }
        return Map.of("acceptedIds",accepted,"serverTime",Store.now());
    }
    private void validate(Event e,JsonNode snapshot) {
        if(!e.data().isObject()||s.encode(e.data()).length()>12000) throw ApiException.bad("داده رویداد معتبر نیست");var d=e.data();var geo=d.get("location");
        // Accuracy, age, missing permission and missing GPS never block a patrol.
        if(geo!=null&&!geo.isNull()&&(!geo.isObject()||!geo.path("latitude").isNumber()||!geo.path("longitude").isNumber()||!Double.isFinite(geo.path("latitude").asDouble())||!Double.isFinite(geo.path("longitude").asDouble())||Math.abs(geo.path("latitude").asDouble())>90||Math.abs(geo.path("longitude").asDouble())>180)) throw ApiException.bad("ساختار مختصات معتبر نیست؛ موقعیت ناموجود باید null باشد");
        if(e.type().equals("VISIT")) {boolean found=false;for(var p:snapshot.path("checkpoints")) if(p.path("id").asText().equals(d.path("checkpointId").asText())&&p.path("tagCode").asText().equals(d.path("tagCode").asText())) found=true;if(!found||!"QR".equals(d.path("method").asText())) throw ApiException.bad("نقطه یا QR در مسیر این مأموریت نیست");}
        if(e.type().equals("INCIDENT")) {int len=d.path("note").asText().trim().length();if(len<1||len>4000||!List.of("LOW","MEDIUM","HIGH").contains(d.path("severity").asText())) throw ApiException.bad("متن یا اهمیت گزارش معتبر نیست");}
        if(e.type().equals("ABORT") && (d.path("note").asText().trim().isEmpty() || d.path("note").asText().length()>4000)) throw ApiException.bad("علت توقف گشت را وارد کنید");
    }
}
