package ir.nab;
import ir.nab.SecurityConfig.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class AdminController {
    final Store s;final PasswordEncoder passwords;
    AdminController(Store s,PasswordEncoder passwords) {this.s=s;this.passwords=passwords;}
    public record UserInput(@NotBlank @Size(max=80) String username,@NotBlank @Size(max=160) String name,@Size(min=8,max=72) @NotBlank String password,@Pattern(regexp="GUARD|SUPERVISOR|ADMIN") @NotBlank String role) {}
    public record SiteInput(@NotBlank @Size(max=160) String name,@NotNull @Size(max=500) String address) {}
    public record PointInput(@NotBlank String siteId,@NotBlank @Size(max=160) String name,@DecimalMin("-90") @DecimalMax("90") Double latitude,@DecimalMin("-180") @DecimalMax("180") Double longitude) {}
    public record RouteInput(@NotBlank String siteId,@NotBlank @Size(max=160) String name,@Size(min=2,max=200) @NotNull List<@NotBlank String> checkpointIds,@Min(1) @Max(1440) int expectedMinutes) {}
    public record AssignmentInput(@NotBlank String guardId,@NotBlank String routeId,@NotBlank @Size(max=160) String label,@NotNull Instant startsAt,@NotNull Instant endsAt) {}
    @GetMapping("/users") List<Map<String,Object>> users() {return s.db.queryForList("SELECT id,username,name,role,active FROM app_user ORDER BY name");}
    @PostMapping("/users") @PreAuthorize("hasRole('ADMIN')") @Transactional Map<String,String> user(@Valid @RequestBody UserInput in,@AuthenticationPrincipal Actor actor) {
        if(in.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw ApiException.bad("رمز عبور نباید بیشتر از ۷۲ بایت UTF-8 باشد");
        String id=Store.id();s.db.update("INSERT INTO app_user(id,username,name,password_hash,role) VALUES(?,?,?,?,?)",id,in.username().trim(),in.name(),passwords.encode(in.password()),in.role());s.audit(actor.id(),"CREATE_USER",id);return Map.of("id",id);
    }
    @PostMapping("/users/{id}/active") @PreAuthorize("hasRole('ADMIN')") @Transactional void active(@PathVariable String id,@RequestBody Map<String,Boolean> in,@AuthenticationPrincipal Actor actor) {
        if(id.equals(actor.id())) throw ApiException.bad("حساب خودتان را نمی‌توانید غیرفعال کنید");s.one("SELECT id FROM app_user WHERE id=?",id);s.db.update("UPDATE app_user SET active=? WHERE id=?",Boolean.TRUE.equals(in.get("active")),id);s.db.update("DELETE FROM app_session WHERE user_id=?",id);s.audit(actor.id(),"CHANGE_USER_ACTIVE",id);
    }
    @GetMapping("/sites") List<Map<String,Object>> sites() {return s.db.queryForList("SELECT * FROM site ORDER BY name");}
    @PostMapping("/sites") @Transactional Map<String,String> site(@Valid @RequestBody SiteInput in,@AuthenticationPrincipal Actor actor) {String id=Store.id();s.db.update("INSERT INTO site VALUES(?,?,?)",id,in.name(),in.address());s.audit(actor.id(),"CREATE_SITE",id);return Map.of("id",id);}
    @GetMapping("/checkpoints") List<Map<String,Object>> points() {return s.db.queryForList("SELECT c.*,s.name AS site_name FROM checkpoint c JOIN site s ON s.id=c.site_id ORDER BY c.name");}
    @PostMapping("/checkpoints") @Transactional Map<String,String> point(@Valid @RequestBody PointInput in,@AuthenticationPrincipal Actor actor) {
        s.one("SELECT id FROM site WHERE id=?",in.siteId());if((in.latitude()==null)!=(in.longitude()==null)) throw ApiException.bad("هر دو مختصات را وارد کنید یا خالی بگذارید");
        String id=Store.id(),code="NAB:"+Store.id();s.db.update("INSERT INTO checkpoint VALUES(?,?,?,?,?,?)",id,in.siteId(),in.name(),code,in.latitude(),in.longitude());s.audit(actor.id(),"CREATE_CHECKPOINT",id);return Map.of("id",id,"tagCode",code);
    }
    @GetMapping("/routes") List<Map<String,Object>> routes() {var rows=s.db.queryForList("SELECT r.*,s.name AS site_name FROM patrol_route r JOIN site s ON s.id=r.site_id ORDER BY r.name");rows.forEach(r->r.put("checkpointIds",s.decode((String)r.remove("checkpoint_ids"))));return rows;}
    @PostMapping("/routes") @Transactional Map<String,String> route(@Valid @RequestBody RouteInput in,@AuthenticationPrincipal Actor actor) {
        var stops=in.checkpointIds();
        var unique=new HashSet<>(stops);
        boolean loop=stops.size()>=3 && stops.getFirst().equals(stops.getLast());
        if(unique.size()!=stops.size()-(loop?1:0)) throw ApiException.bad("فقط نقطه شروع و پایان می‌توانند یک محل مشترک باشند");
        for(String point:stops) s.one("SELECT id FROM checkpoint WHERE id=? AND site_id=?",point,in.siteId());
        String id=Store.id();s.db.update("INSERT INTO patrol_route VALUES(?,?,?,?,?)",id,in.siteId(),in.name(),s.encode(in.checkpointIds()),in.expectedMinutes());s.audit(actor.id(),"CREATE_ROUTE",id);return Map.of("id",id);
    }
    @GetMapping("/assignments") List<Map<String,Object>> assignments() {return s.db.queryForList("SELECT a.id,a.guard_id,a.route_id,a.label,a.starts_at,a.ends_at,a.plan_id,u.name AS guard_name,r.name AS route_name FROM assignment a JOIN app_user u ON u.id=a.guard_id JOIN patrol_route r ON r.id=a.route_id ORDER BY a.starts_at DESC");}
    @PostMapping("/assignments") @Transactional Map<String,String> assign(@Valid @RequestBody AssignmentInput in,@AuthenticationPrincipal Actor actor) {
        s.one("SELECT id FROM app_user WHERE id=? AND role='GUARD' AND active=TRUE",in.guardId());if(!in.endsAt().isAfter(in.startsAt())) throw ApiException.bad("پایان شیفت باید بعد از شروع باشد");
        var route=s.one("SELECT r.*,s.name AS site_name FROM patrol_route r JOIN site s ON s.id=r.site_id WHERE r.id=?",in.routeId());List<Map<String,Object>> points=new ArrayList<>();
        var stopIds=s.decode((String)route.get("checkpoint_ids"));
        if(stopIds.size()<2) throw ApiException.bad("برای این مسیر ابتدا نقطه شروع و پایان تعریف کنید");
        for(var stopId:stopIds) {var p=s.one("SELECT * FROM checkpoint WHERE id=?",stopId.asText());var point=new LinkedHashMap<String,Object>();point.put("id",p.get("id"));point.put("name",p.get("name"));point.put("tagCode",p.get("tag_code"));point.put("latitude",p.get("latitude"));point.put("longitude",p.get("longitude"));point.put("stopIndex",points.size());point.put("kind",points.isEmpty()?"START":points.size()==stopIds.size()-1?"END":"CHECKPOINT");points.add(point);}
        var snapshot=Map.of("routeName",route.get("name"),"siteName",route.get("site_name"),"expectedMinutes",route.get("expected_minutes"),"checkpoints",points,"routePolicy","ORDERED_V1");
        String id=Store.id();s.db.update("INSERT INTO assignment(id,guard_id,route_id,label,starts_at,ends_at,snapshot) VALUES(?,?,?,?,?,?,?)",id,in.guardId(),in.routeId(),in.label(),in.startsAt().toString(),in.endsAt().toString(),s.encode(snapshot));s.audit(actor.id(),"CREATE_ASSIGNMENT",id);return Map.of("id",id);
    }
    @GetMapping("/tours") List<Map<String,Object>> tours() {
        var rows=s.db.queryForList("SELECT t.*,u.name AS guard_name,a.label,a.starts_at,a.ends_at,a.snapshot FROM patrol_tour t JOIN app_user u ON u.id=t.guard_id JOIN assignment a ON a.id=t.assignment_id ORDER BY t.received_at DESC");
        for(var row:rows) {row.put("snapshot",s.decode((String)row.get("snapshot")));var events=s.db.queryForList("SELECT e.*,p.id AS photo_id FROM patrol_event e LEFT JOIN photo p ON p.event_id=e.id WHERE e.tour_id=? ORDER BY e.sequence_no",row.get("id"));events.forEach(e->e.put("payload",s.decode((String)e.get("payload"))));row.put("events",events);}return rows;
    }
    @GetMapping("/audit") @PreAuthorize("hasRole('ADMIN')") List<Map<String,Object>> audit() {return s.db.queryForList("SELECT a.*,u.name AS actor_name FROM audit_log a LEFT JOIN app_user u ON a.actor_id=u.id ORDER BY a.created_at DESC LIMIT 200");}
}
