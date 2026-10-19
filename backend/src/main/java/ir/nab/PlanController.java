package ir.nab;

import ir.nab.SecurityConfig.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/plans")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class PlanController {
    final Store s;final AdminController admin;
    PlanController(Store s,AdminController admin){this.s=s;this.admin=admin;}
    public record RepeatInput(@NotBlank @Size(max=110) String label,@NotNull Instant startsAt,@NotNull Instant endsAt) {}
    @GetMapping List<Map<String,Object>> list(){
        var rows=s.db.queryForList("SELECT p.*,u.name AS guard_name,r.name AS route_name,si.name AS site_name,(SELECT COUNT(*) FROM assignment a WHERE a.plan_id=p.id) AS round_count FROM patrol_plan p JOIN app_user u ON u.id=p.guard_id JOIN patrol_route r ON r.id=p.route_id JOIN site si ON si.id=r.site_id ORDER BY p.created_at DESC");
        rows.forEach(p->p.put("weeklyWindows",p.get("weekly_windows")==null?List.of():s.decode((String)p.get("weekly_windows"))));return rows;
    }
    private PlanSchedule.Result schedule(PlanSchedule.Spec in){
        s.one("SELECT id FROM app_user WHERE id=? AND role='GUARD' AND active=TRUE",in.guardId());
        var route=s.one("SELECT expected_minutes FROM patrol_route WHERE id=?",in.routeId());
        return PlanSchedule.build(in,((Number)route.get("expected_minutes")).intValue());
    }
    @PostMapping("/preview") @Transactional(readOnly=true)
    PlanSchedule.Preview preview(@Valid @RequestBody PlanSchedule.Spec in){return schedule(in).preview();}
    @PostMapping @Transactional Map<String,Object> create(@Valid @RequestBody PlanSchedule.Spec in,@AuthenticationPrincipal Actor actor){return persist(in,null,actor);}
    @PostMapping("/{id}/repeat") @Transactional
    Map<String,Object> repeat(@PathVariable UUID id,@Valid @RequestBody RepeatInput in,@AuthenticationPrincipal Actor actor){
        var source=s.one("SELECT * FROM patrol_plan WHERE id=?",id.toString());
        String root=source.get("template_root_id")==null?id.toString():(String)source.get("template_root_id");
        // Serialize repeats of the same template; concurrent submissions cannot create overlapping copies.
        s.one("SELECT id FROM patrol_plan WHERE id=? FOR UPDATE",root);
        for(var existing:s.db.queryForList("SELECT starts_at,ends_at FROM patrol_plan WHERE id=? OR template_root_id=?",root,root)){
            if(in.startsAt().isBefore(Instant.parse((String)existing.get("ends_at")))&&in.endsAt().isAfter(Instant.parse((String)existing.get("starts_at"))))
                throw ApiException.conflict("این بازه قبلاً با همین الگو برنامه‌ریزی شده است");
        }
        List<PlanSchedule.WeeklyWindow> windows=new ArrayList<>();
        if(source.get("weekly_windows")!=null)for(var w:s.decode((String)source.get("weekly_windows"))){Set<Integer> days=new HashSet<>();w.path("days").forEach(d->days.add(d.asInt()));windows.add(new PlanSchedule.WeeklyWindow(days,w.path("startTime").asText(),w.path("endTime").asText()));}
        var spec=new PlanSchedule.Spec((String)source.get("guard_id"),(String)source.get("route_id"),in.label(),in.startsAt(),in.endsAt(),
            ((Number)source.get("work_hours")).intValue(),((Number)source.get("rest_hours")).intValue(),((Number)source.get("interval_minutes")).intValue(),((Number)source.get("window_minutes")).intValue(),
            (String)source.get("schedule_mode"),windows,Instant.parse((String)(source.get("cycle_anchor")==null?source.get("starts_at"):source.get("cycle_anchor"))));
        return persist(spec,root,actor);
    }
    private Map<String,Object> persist(PlanSchedule.Spec in,String root,Actor actor){
        var result=schedule(in);
        if(result.slots().isEmpty())throw ApiException.bad("هیچ نوبت کاملی در ساعت‌های کاری این بازه قرار نمی‌گیرد؛ پیش‌نمایش را بررسی کنید");
        String id=Store.id();
        s.db.update("INSERT INTO patrol_plan(id,guard_id,route_id,label,starts_at,ends_at,work_hours,rest_hours,interval_minutes,window_minutes,created_at,schedule_mode,weekly_windows,cycle_anchor,template_root_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,in.guardId(),in.routeId(),in.label(),in.startsAt().toString(),in.endsAt().toString(),in.workHours(),in.restHours(),in.intervalMinutes(),in.windowMinutes(),Store.now(),in.mode(),in.mode().equals("WEEKLY")?s.encode(in.weeklyWindows()):null,in.anchor().toString(),root);
        int number=0;
        for(var slot:result.slots()){
            String assignment=admin.assign(new AdminController.AssignmentInput(in.guardId(),in.routeId(),in.label()+" · نوبت "+(++number),slot.startsAt(),slot.endsAt()),actor).get("id");
            s.db.update("UPDATE assignment SET plan_id=? WHERE id=?",id,assignment);
        }
        s.audit(actor.id(),root==null?"CREATE_PLAN":"REPEAT_PLAN",id);
        return Map.of("id",id,"roundCount",number,"shiftCount",result.preview().shiftCount());
    }
}
