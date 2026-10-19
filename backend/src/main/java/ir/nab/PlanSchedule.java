package ir.nab;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

/** Deterministic scheduling shared by preview, creation and template reuse. */
final class PlanSchedule {
    static final ZoneId ZONE=ZoneId.of("Asia/Tehran");
    record WeeklyWindow(@NotEmpty Set<@NotNull @Min(1) @Max(7) Integer> days,
        @NotNull @Pattern(regexp="(?:[01][0-9]|2[0-3]):[0-5][0-9]") String startTime,
        @NotNull @Pattern(regexp="(?:[01][0-9]|2[0-3]):[0-5][0-9]") String endTime) {}
    record Spec(@NotBlank String guardId,@NotBlank String routeId,@NotBlank @Size(max=110) String label,
        @NotNull Instant startsAt,@NotNull Instant endsAt,
        @Min(1) @Max(72) int workHours,@Min(0) @Max(168) int restHours,
        @Min(1) @Max(1440) int intervalMinutes,@Min(1) @Max(1440) int windowMinutes,
        @Pattern(regexp="INTERVAL|WEEKLY") String scheduleMode,
        @Size(max=32) List<@Valid @NotNull WeeklyWindow> weeklyWindows,Instant cycleAnchor) {
        String mode(){return scheduleMode==null?"INTERVAL":scheduleMode;}
        Instant anchor(){return cycleAnchor==null?startsAt:cycleAnchor;}
    }
    record Span(Instant startsAt,Instant endsAt) {}
    record Day(LocalDate date,int workMinutes,int restMinutes,List<Span> work,List<Span> rest,List<Span> rounds) {}
    record Preview(String timeZone,String scheduleMode,int roundCount,int shiftCount,int skippedWindows,List<Span> shifts,List<Day> days) {}
    record Result(List<Span> slots,Preview preview) {}

    static Result build(Spec in,int expectedMinutes) {
        if(!in.endsAt().isAfter(in.startsAt())||Duration.between(in.startsAt(),in.endsAt()).compareTo(Duration.ofDays(31))>0)
            throw ApiException.bad("بازه برنامه باید بیشتر از صفر و حداکثر ۳۱ روز باشد");
        if(in.anchor().isAfter(in.startsAt()))throw ApiException.bad("شروع چرخه شیفت نمی‌تواند بعد از شروع بازه باشد");
        if(in.mode().equals("INTERVAL")){
            if(in.windowMinutes()>in.intervalMinutes()||in.windowMinutes()>in.workHours()*60)throw ApiException.bad("مهلت گشت نباید از فاصله نوبت‌ها یا طول شیفت بیشتر باشد");
            if(in.windowMinutes()<expectedMinutes)throw ApiException.bad("مهلت گشت از زمان مورد انتظار مسیر کمتر است");
        }else validateWindows(in,expectedMinutes);
        long cycle=(in.workHours()+in.restHours())*3600L;
        long cycles=Duration.between(in.anchor(),in.startsAt()).getSeconds()/cycle;
        Instant first=in.anchor().plusSeconds(cycles*cycle);
        List<Span> duties=new ArrayList<>(),shifts=new ArrayList<>(),slots=new ArrayList<>();
        for(Instant duty=first;duty.isBefore(in.endsAt());duty=duty.plusSeconds(cycle)){
            Instant end=duty.plus(Duration.ofHours(in.workHours()));
            if(!end.isAfter(in.startsAt()))continue;
            duties.add(new Span(duty,end));shifts.add(new Span(max(duty,in.startsAt()),min(end,in.endsAt())));
            if(in.mode().equals("INTERVAL"))for(Instant start=duty;!start.plusSeconds(in.windowMinutes()*60L).isAfter(min(end,in.endsAt()));start=start.plusSeconds(in.intervalMinutes()*60L)){
                if(!start.isBefore(in.startsAt()))add(slots,new Span(start,start.plusSeconds(in.windowMinutes()*60L)));
            }
        }
        int skipped=0;
        if(in.mode().equals("WEEKLY")){
            LocalDate from=in.startsAt().atZone(ZONE).toLocalDate(),to=in.endsAt().atZone(ZONE).toLocalDate();
            for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1))for(var window:in.weeklyWindows()){
                if(!window.days().contains(date.getDayOfWeek().getValue()))continue;
                LocalTime a=LocalTime.parse(window.startTime()),b=LocalTime.parse(window.endTime());
                Instant start=date.atTime(a).atZone(ZONE).toInstant();
                Instant end=(b.isAfter(a)?date:date.plusDays(1)).atTime(b).atZone(ZONE).toInstant();
                if(start.isBefore(in.startsAt())||!start.isBefore(in.endsAt()))continue;
                boolean fits=!end.isAfter(in.endsAt())&&duties.stream().anyMatch(d->!start.isBefore(d.startsAt())&&!end.isAfter(d.endsAt()));
                if(fits)add(slots,new Span(start,end));else skipped++;
            }
        }
        slots.sort(Comparator.comparing(Span::startsAt));
        List<Day> days=new ArrayList<>();
        for(LocalDate day=in.startsAt().atZone(ZONE).toLocalDate();day.atStartOfDay(ZONE).toInstant().isBefore(in.endsAt());day=day.plusDays(1)){
            Instant a=max(day.atStartOfDay(ZONE).toInstant(),in.startsAt()),b=min(day.plusDays(1).atStartOfDay(ZONE).toInstant(),in.endsAt());
            var work=shifts.stream().filter(d->d.startsAt().isBefore(b)&&d.endsAt().isAfter(a)).map(d->new Span(max(d.startsAt(),a),min(d.endsAt(),b))).toList();
            var rounds=slots.stream().filter(d->!d.startsAt().isBefore(a)&&d.startsAt().isBefore(b)).toList();
            List<Span> rest=new ArrayList<>();Instant cursor=a;
            for(var w:work){if(cursor.isBefore(w.startsAt()))rest.add(new Span(cursor,w.startsAt()));cursor=w.endsAt();}
            if(cursor.isBefore(b))rest.add(new Span(cursor,b));
            days.add(new Day(day,(int)work.stream().mapToLong(d->Duration.between(d.startsAt(),d.endsAt()).toMinutes()).sum(),
                (int)rest.stream().mapToLong(d->Duration.between(d.startsAt(),d.endsAt()).toMinutes()).sum(),work,List.copyOf(rest),rounds));
        }
        return new Result(List.copyOf(slots),new Preview(ZONE.getId(),in.mode(),slots.size(),shifts.size(),skipped,List.copyOf(shifts),List.copyOf(days)));
    }
    private static void validateWindows(Spec in,int expected){
        if(in.weeklyWindows()==null||in.weeklyWindows().isEmpty())throw ApiException.bad("حداقل یک بازه گشت هفتگی انتخاب کنید");
        List<int[]> spans=new ArrayList<>();
        for(var w:in.weeklyWindows()){
            int a=LocalTime.parse(w.startTime()).toSecondOfDay()/60,b=LocalTime.parse(w.endTime()).toSecondOfDay()/60;
            if(a==b)throw ApiException.bad("شروع و پایان بازه گشت نباید یکسان باشد");
            int duration=b>a?b-a:1440-a+b;
            if(duration<expected||duration>in.workHours()*60)throw ApiException.bad("طول هر بازه باید حداقل زمان مسیر و حداکثر طول شیفت باشد");
            for(int day:w.days())spans.add(new int[]{(day-1)*1440+a,(day-1)*1440+a+duration});
        }
        for(int i=0;i<spans.size();i++)for(int j=i+1;j<spans.size();j++)for(int week=-1;week<=1;week++){
            int[] a=spans.get(i),b=spans.get(j);int offset=week*10080;
            if(a[0]<b[1]+offset&&a[1]>b[0]+offset)throw ApiException.bad("بازه‌های گشت هفتگی نباید هم‌پوشانی یا تکرار داشته باشند");
        }
    }
    private static void add(List<Span> slots,Span slot){slots.add(slot);if(slots.size()>1000)throw ApiException.bad("بیش از هزار نوبت ساخته می‌شود؛ بازه یا تعداد گشت‌ها را کمتر کنید");}
    private static Instant max(Instant a,Instant b){return a.isAfter(b)?a:b;}
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
}
