package ir.nab;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlanScheduleTest {
    static final Instant START=Instant.parse("2026-10-19T04:30:00Z"),END=START.plus(Duration.ofDays(7));
    static final Set<Integer> EVERY=Set.of(1,2,3,4,5,6,7);
    PlanSchedule.WeeklyWindow window(String a,String b){return new PlanSchedule.WeeklyWindow(EVERY,a,b);}
    PlanSchedule.Spec spec(Instant start,Instant end,List<PlanSchedule.WeeklyWindow> windows,Instant anchor){return new PlanSchedule.Spec("guard","route","هفته",start,end,24,24,120,45,"WEEKLY",windows,anchor);}
    List<PlanSchedule.WeeklyWindow> regular(){return List.of(window("02:00","04:00"),window("06:00","08:00"));}
    @Test void fixedWindowsAreExpandedOnlyInsideWorkShifts(){
        var result=PlanSchedule.build(spec(START,END,regular(),null),45);
        assertThat(result.slots()).hasSize(8);assertThat(result.preview().shiftCount()).isEqualTo(4);
        assertThat(result.preview().days().stream().mapToInt(PlanSchedule.Day::workMinutes).sum()).isEqualTo(96*60);
        assertThat(result.preview().days().stream().mapToInt(PlanSchedule.Day::restMinutes).sum()).isEqualTo(72*60);
        assertThat(result.preview().days().stream().mapToInt(d->d.rounds().size()).sum()).isEqualTo(8);
        assertThat(result.slots().getFirst().startsAt()).isEqualTo(Instant.parse("2026-10-19T22:30:00Z"));
        assertThat(result.slots().getLast().endsAt()).isEqualTo(END);
        for(var slot:result.slots())assertThat(result.preview().shifts().stream().anyMatch(d->!slot.startsAt().isBefore(d.startsAt())&&!slot.endsAt().isAfter(d.endsAt()))).isTrue();
    }
    @Test void repeatingAnOddLengthWeekKeepsOriginalWorkRestPhase(){
        var result=PlanSchedule.build(spec(END,END.plus(Duration.ofDays(7)),regular(),START),45);
        assertThat(result.preview().shiftCount()).isEqualTo(3);assertThat(result.slots()).hasSize(6);
        assertThat(result.preview().shifts().getFirst().startsAt()).isEqualTo(END.plus(Duration.ofDays(1)));
        assertThat(result.preview().days().getFirst().workMinutes()).isZero();
    }
    @Test void selectedWeekdaysAreCalendarWeekdaysNotShiftOffsets(){
        var result=PlanSchedule.build(spec(START,END,List.of(new PlanSchedule.WeeklyWindow(Set.of(2),"02:00","04:00")),null),45);
        assertThat(result.slots()).hasSize(1);assertThat(result.slots().getFirst().startsAt().atZone(PlanSchedule.ZONE).getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
    }
    @Test void overnightWindowsUseTheNextDayAndRemainWithinTheShift(){
        var result=PlanSchedule.build(spec(START,END,List.of(window("23:00","01:00")),null),45);
        assertThat(result.slots()).hasSize(4);
        assertThat(Duration.between(result.slots().getFirst().startsAt(),result.slots().getFirst().endsAt()).toHours()).isEqualTo(2);
        assertThat(result.slots().getFirst().endsAt().atZone(PlanSchedule.ZONE).toLocalDate()).isEqualTo(LocalDate.of(2026,10,20));
    }
    @Test void windowsCrossingWorkRestBoundaryAreNotClippedOrPartiallyCreated(){
        var result=PlanSchedule.build(spec(START,END,List.of(window("07:00","09:00")),null),45);
        assertThat(result.slots()).isEmpty();assertThat(result.preview().skippedWindows()).isGreaterThan(0);
    }
    @Test void overlapAcrossWeekBoundaryIsRejectedButAdjacentWindowsAreAllowed(){
        var windows=List.of(new PlanSchedule.WeeklyWindow(Set.of(7),"23:00","01:00"),new PlanSchedule.WeeklyWindow(Set.of(1),"00:30","02:00"));
        assertThatThrownBy(()->PlanSchedule.build(spec(START,END,windows,null),45)).isInstanceOf(ApiException.class);
        assertThat(PlanSchedule.build(spec(START,END,List.of(window("02:00","04:00"),window("04:00","06:00")),null),45).slots()).hasSize(8);
    }
    @Test void duplicateEqualAndTooShortWindowsAreRejected(){
        for(var windows:List.of(List.of(window("02:00","04:00"),window("02:00","04:00")),List.of(window("02:00","02:00")),List.of(window("02:00","02:10"))))
            assertThatThrownBy(()->PlanSchedule.build(spec(START,END,windows,null),45)).isInstanceOf(ApiException.class);
    }
    @Test void partialPeriodsKeepTheFullWindowsAndRespectTheExclusiveEnd(){
        Instant a=Instant.parse("2026-10-19T23:30:00Z"),b=Instant.parse("2026-10-20T04:00:00Z");
        var result=PlanSchedule.build(spec(a,b,regular(),START),45);
        assertThat(result.slots()).isEmpty(); // 02:00 is before the period, 06:00–08:00 ends outside it.
        var exact=PlanSchedule.build(spec(a,Instant.parse("2026-10-20T04:30:00Z"),regular(),START),45);
        assertThat(exact.slots()).hasSize(1);
    }
    @Test void legacyIntervalModeStillProducesFortyEightRoundsAndThirtySixNextWeek(){
        var first=new PlanSchedule.Spec("g","r","legacy",START,END,24,24,120,45,null,null,null);
        var next=new PlanSchedule.Spec("g","r","legacy",END,END.plus(Duration.ofDays(7)),24,24,120,45,"INTERVAL",null,START);
        assertThat(PlanSchedule.build(first,45).slots()).hasSize(48);assertThat(PlanSchedule.build(next,45).slots()).hasSize(36);
    }
    @Test void invalidRangesAnchorsAndExcessiveExpansionAreRejected(){
        assertThatThrownBy(()->PlanSchedule.build(spec(START,START,regular(),null),45)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->PlanSchedule.build(spec(START,START.plus(Duration.ofDays(32)),regular(),null),45)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->PlanSchedule.build(spec(START,END,regular(),END),45)).isInstanceOf(ApiException.class);
        var huge=new PlanSchedule.Spec("g","r","large",START,START.plus(Duration.ofDays(2)),24,0,1,1,"INTERVAL",null,null);
        assertThatThrownBy(()->PlanSchedule.build(huge,1)).isInstanceOf(ApiException.class);
    }
}
