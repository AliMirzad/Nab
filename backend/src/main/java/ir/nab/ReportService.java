package ir.nab;

import com.fasterxml.jackson.databind.JsonNode;
import com.ibm.icu.text.SimpleDateFormat;
import com.ibm.icu.util.Calendar;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
    static final ZoneId ZONE = ZoneId.of("Asia/Tehran");
    static final ULocale PERSIAN = new ULocale("fa_IR@calendar=persian;numbers=latn");
    final Store s;
    ReportService(Store s) {this.s=s;}

    public record Filter(UUID guardId, UUID routeId, UUID siteId, UUID assignmentId,
        @NotNull @Pattern(regexp="ALL|MONTH|RANGE") String period,
        @Min(1300) @Max(1500) Integer year, @Min(1) @Max(12) Integer month,
        LocalDate fromDate, LocalDate toDate,
        @NotNull @Pattern(regexp="ALL|ACTIVE|FINISHED|ABORTED|PENDING|OVERDUE") String status,
        boolean includeEvents) {}
    public record Period(Instant start, Instant end, String label) {}
    public record EventRow(String id, int sequence, String type, String checkpoint, String capturedAt,
        String receivedAt, String location, String note, String severity) {}
    public record Row(String assignmentId, String tourId, String guardId, String guardName, String siteName,
        String routeName, String label, String scheduledStart, String scheduledEnd, String actualStart,
        String actualEnd, String status, int expectedStops, int visitedStops, int incidents,
        Long delayMinutes, Long durationMinutes, boolean clockInvalid, List<EventRow> events) {}
    public record Totals(int total, int finished, int active, int aborted, int pending, int overdue,
        int expectedStops, int visitedStops, int incidents) {}
    public record GuardSummary(String guardId, String guardName, Totals totals) {}
    public record Report(String id, String generatedAt, String periodLabel, String timeZone,
        Map<String,String> criteria, Totals summary, List<GuardSummary> guards, List<Row> rows, boolean includeEvents) {}

    static Period period(Filter f) {
        if (f.period().equals("ALL")) return new Period(null,null,"همه زمان‌ها");
        if (f.period().equals("MONTH")) {
            if(f.year()==null||f.month()==null)throw ApiException.bad("سال و ماه شمسی را انتخاب کنید");
            var cal=Calendar.getInstance(TimeZone.getTimeZone(ZONE.getId()),PERSIAN);
            cal.clear();cal.setLenient(false);cal.set(f.year(),f.month()-1,1,0,0,0);
            Instant first=cal.getTime().toInstant();cal.add(Calendar.MONTH,1);
            return new Period(first,cal.getTime().toInstant(),f.year()+"/"+String.format(Locale.ROOT,"%02d",f.month())+" (شمسی)");
        }
        if(f.fromDate()==null||f.toDate()==null||f.toDate().isBefore(f.fromDate()))throw ApiException.bad("بازه تاریخ معتبر نیست");
        if(f.fromDate().getYear()<1900||f.toDate().getYear()>2100)throw ApiException.bad("بازه تاریخ باید بین سال ۱۹۰۰ تا ۲۱۰۰ میلادی باشد");
        return new Period(f.fromDate().atStartOfDay(ZONE).toInstant(),f.toDate().plusDays(1).atStartOfDay(ZONE).toInstant(),
            persian(f.fromDate().atStartOfDay(ZONE).toInstant().toString(),false)+" تا "+persian(f.toDate().atStartOfDay(ZONE).toInstant().toString(),false));
    }
    static String persian(String value,boolean time) {
        if(value==null||value.isBlank())return "-";
        var fmt=new SimpleDateFormat(time?"yyyy/MM/dd HH:mm":"yyyy/MM/dd",PERSIAN);
        fmt.setTimeZone(TimeZone.getTimeZone(ZONE.getId()));
        return fmt.format(Date.from(Instant.parse(value)));
    }
    static String statusLabel(String status) {
        return switch(status){case "FINISHED"->"پایان‌یافته";case "ACTIVE"->"در حال گشت";case "ABORTED"->"متوقف‌شده / ناموفق";case "OVERDUE"->"دریافت‌نشده";default->"برنامه‌ریزی‌شده";};
    }
    static String eventLabel(String type) {
        return switch(type){case "START"->"شروع گشت";case "VISIT"->"اسکن نقطه";case "LOCATION"->"موقعیت";case "INCIDENT"->"حادثه";case "FINISH"->"پایان گشت";case "ABORT"->"توقف ناقص";default->type;};
    }
    private String label(String sql,UUID id,String fallback){return id==null?fallback:(String)s.one(sql,id.toString()).get("name");}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report generate(Filter f) {
        Period p=period(f);Instant now=Instant.now();
        var criteria=new LinkedHashMap<String,String>();
        criteria.put("نگهبان",label("SELECT name FROM app_user WHERE id=?",f.guardId(),"همه نگهبان‌ها"));
        criteria.put("محل",label("SELECT name FROM site WHERE id=?",f.siteId(),"همه محل‌ها"));
        criteria.put("مسیر",label("SELECT name FROM patrol_route WHERE id=?",f.routeId(),"همه مسیرها"));
        criteria.put("نوبت گشت",f.assignmentId()==null?"همه نوبت‌ها":(String)s.one("SELECT label FROM assignment WHERE id=?",f.assignmentId().toString()).get("label"));
        criteria.put("وضعیت",f.status().equals("ALL")?"همه وضعیت‌ها":statusLabel(f.status()));
        criteria.put("بازه",p.label());criteria.put("مبنای تاریخ","شروع برنامه‌ریزی‌شده گشت، به وقت تهران");
        StringBuilder sql=new StringBuilder("SELECT a.id AS assignment_id,a.guard_id,a.label,a.starts_at,a.ends_at,a.snapshot,t.id AS tour_id,t.status,t.started_at,t.finished_at,u.name AS guard_name,r.name AS current_route_name,si.name AS current_site_name FROM assignment a JOIN app_user u ON u.id=a.guard_id JOIN patrol_route r ON r.id=a.route_id JOIN site si ON si.id=r.site_id LEFT JOIN patrol_tour t ON t.assignment_id=a.id WHERE 1=1");
        List<Object> args=new ArrayList<>();
        String[] fields={"a.guard_id","a.route_id","r.site_id","a.id"};UUID[] ids={f.guardId(),f.routeId(),f.siteId(),f.assignmentId()};
        for(int i=0;i<fields.length;i++)if(ids[i]!=null){sql.append(" AND ").append(fields[i]).append("=?");args.add(ids[i].toString());}
        List<Row> rows=new ArrayList<>();int eventCount=0;
        for(var a:s.db.queryForList(sql.toString(),args.toArray())) {
            Instant planned=Instant.parse((String)a.get("starts_at"));
            if(p.start()!=null&&(planned.isBefore(p.start())||!planned.isBefore(p.end())))continue;
            String status=(String)a.get("status");
            if(status==null)status=Instant.parse((String)a.get("ends_at")).isBefore(now)?"OVERDUE":"PENDING";
            if(!f.status().equals("ALL")&&!status.equals(f.status()))continue;
            if(rows.size()>=10000)throw new ApiException(413,"REPORT_TOO_LARGE","گزارش بیش از ۱۰۰۰۰ نوبت دارد؛ شخص یا بازه کوچک‌تری انتخاب کنید");
            var snapshot=s.decode((String)a.get("snapshot"));
            var raw=a.get("tour_id")==null?List.<Map<String,Object>>of():s.db.queryForList("SELECT * FROM patrol_event WHERE tour_id=? ORDER BY sequence_no",a.get("tour_id"));
            List<EventRow> events=new ArrayList<>();Set<String> visits=new HashSet<>();int incidents=0;
            for(var e:raw) {
                var data=s.decode((String)e.get("payload")).path("data");String type=(String)e.get("event_type");
                if(type.equals("VISIT"))visits.add(RouteRules.ordered(snapshot)?data.path("stopIndex").asText():data.path("checkpointId").asText());
                if(type.equals("INCIDENT"))incidents++;
                if(f.includeEvents()) {
                    if(++eventCount>50000)throw new ApiException(413,"REPORT_TOO_LARGE","رویدادهای گزارش بیش از ۵۰۰۰۰ مورد است؛ بازه را کوچک‌تر کنید یا جزئیات رویدادها را غیرفعال کنید");
                    String checkpoint="";for(var point:snapshot.path("checkpoints"))if(point.path("id").asText().equals(data.path("checkpointId").asText())){checkpoint=point.path("name").asText();break;}
                    var geo=data.path("location");String location=geo.isObject()?geo.path("latitude").asText()+", "+geo.path("longitude").asText()+" ("+geo.path("accuracy").asText("?")+" m)":"موجود نیست";
                    events.add(new EventRow((String)e.get("id"),((Number)e.get("sequence_no")).intValue(),type,checkpoint,(String)e.get("captured_at"),(String)e.get("received_at"),location,data.path("note").asText(""),data.path("severity").asText("")));
                }
            }
            String actual=(String)a.get("started_at"),end=(String)a.get("finished_at");
            Long delay=actual==null?null:Math.max(0,Duration.between(planned,Instant.parse(actual)).toMinutes());
            Long duration=actual==null||end==null?null:Duration.between(Instant.parse(actual),Instant.parse(end)).toMinutes();
            boolean invalid=actual!=null&&end!=null&&Instant.parse(end).isBefore(Instant.parse(actual));if(invalid)duration=null;
            rows.add(new Row((String)a.get("assignment_id"),(String)a.get("tour_id"),(String)a.get("guard_id"),(String)a.get("guard_name"),
                snapshot.path("siteName").asText((String)a.get("current_site_name")),snapshot.path("routeName").asText((String)a.get("current_route_name")),(String)a.get("label"),
                (String)a.get("starts_at"),(String)a.get("ends_at"),actual,end,status,snapshot.path("checkpoints").size(),visits.size(),incidents,delay,duration,invalid,List.copyOf(events)));
        }
        rows.sort(Comparator.comparing((Row r)->Instant.parse(r.scheduledStart())).thenComparing(Row::assignmentId));
        var grouped=new LinkedHashMap<String,List<Row>>();for(var row:rows)grouped.computeIfAbsent(row.guardId(),k->new ArrayList<>()).add(row);
        var guards=grouped.entrySet().stream().map(e->new GuardSummary(e.getKey(),e.getValue().getFirst().guardName(),totals(e.getValue()))).toList();
        return new Report(Store.id(),now.toString(),p.label(),ZONE.getId(),Collections.unmodifiableMap(criteria),totals(rows),guards,List.copyOf(rows),f.includeEvents());
    }
    private static Totals totals(List<Row> rows) {
        return new Totals(rows.size(),count(rows,"FINISHED"),count(rows,"ACTIVE"),count(rows,"ABORTED"),count(rows,"PENDING"),count(rows,"OVERDUE"),
            rows.stream().mapToInt(Row::expectedStops).sum(),rows.stream().mapToInt(Row::visitedStops).sum(),rows.stream().mapToInt(Row::incidents).sum());
    }
    private static int count(List<Row> rows,String status){return (int)rows.stream().filter(r->r.status().equals(status)).count();}
}
