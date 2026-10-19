package ir.nab;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:reports-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","nab.demo=true","nab.upload-dir=./target/report-photos"})
@AutoConfigureMockMvc
class ReportIntegrationTest extends TestDatabase {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired Store s;
    @Autowired ReportService reports; @Autowired ReportFiles files;
    String admin,guard,guardId,routeId,siteId,snapshot;
    @BeforeEach void setup() throws Exception {
        admin=login("admin","Admin@12345");guard=login("guard","Guard@12345");
        guardId=(String)s.one("SELECT id FROM app_user WHERE username='guard'").get("id");siteId=Store.id();routeId=Store.id();
        s.db.update("INSERT INTO site(id,name,address) VALUES(?,?,?)",siteId,"کارگاه گزارش آزمایشی","تهران");
        s.db.update("INSERT INTO patrol_route(id,site_id,name,checkpoint_ids,expected_minutes) VALUES(?,?,?,?,?)",routeId,siteId,"گشت منظم کارگاه","[]",45);
        snapshot=s.encode(Map.of("siteName","کارگاه گزارش آزمایشی","routeName","گشت منظم کارگاه","routePolicy","ORDERED_V1","checkpoints",List.of(Map.of("id","gate","name","دروازه ورودی","stopIndex",0),Map.of("id","warehouse","name","انبار مرکزی","stopIndex",1),Map.of("id","gate","name","دروازه ورودی","stopIndex",2))));
    }
    String login(String u,String p)throws Exception{return "Bearer "+json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",u,"password",p)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("token").asText();}
    ReportService.Filter filter(String period,Integer year,Integer month,LocalDate from,LocalDate to,String status,boolean events){return new ReportService.Filter(UUID.fromString(guardId),UUID.fromString(routeId),UUID.fromString(siteId),null,period,year,month,from,to,status,events);}
    String assignment(String start,String label){String id=Store.id();s.db.update("INSERT INTO assignment(id,guard_id,route_id,label,starts_at,ends_at,snapshot) VALUES(?,?,?,?,?,?,?)",id,guardId,routeId,label,start,Instant.parse(start).plusSeconds(3600).toString(),snapshot);return id;}
    String tour(String assignment,String state,String start,String end){String id=Store.id();s.db.update("INSERT INTO patrol_tour(id,assignment_id,guard_id,device_id,status,started_at,finished_at,last_sequence,received_at) VALUES(?,?,?,?,?,?,?,?,?)",id,assignment,guardId,"report-test",state,start,end,5,Store.now());return id;}
    void event(String tour,int seq,String type,Map<String,Object> data){s.db.update("INSERT INTO patrol_event(id,tour_id,sequence_no,event_type,captured_at,received_at,payload) VALUES(?,?,?,?,?,?,?)",Store.id(),tour,seq,type,"2026-10-19T04:40:00Z",Store.now(),s.encode(Map.of("data",data)));}
    JsonNode preview(ReportService.Filter filter)throws Exception{return json.readTree(mvc.perform(post("/api/admin/reports").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(filter))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}

    @Test void persianMonthAndInclusiveDateRangeUseTehranBoundaries() {
        var f=filter("MONTH",1405,7,null,null,"ALL",false);var period=ReportService.period(f);
        assertThat(period.start()).isEqualTo(Instant.parse("2026-09-22T20:30:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-10-22T20:30:00Z"));
        assignment(period.start().minusNanos(1).toString(),"قبل از ماه");assignment(period.start().toString(),"اول ماه");assignment(period.end().minusNanos(1).toString(),"آخر ماه");assignment(period.end().toString(),"ماه بعد");
        assertThat(reports.generate(f).rows()).extracting(ReportService.Row::label).containsExactly("اول ماه","آخر ماه");
        var day=filter("RANGE",null,null,LocalDate.of(2026,10,19),LocalDate.of(2026,10,19),"ALL",false);var range=ReportService.period(day);
        assertThat(Duration.between(range.start(),range.end()).toHours()).isEqualTo(24);
        assignment(range.end().minusNanos(1).toString(),"آخر روز");assignment(range.end().toString(),"روز بعد");
        assertThat(reports.generate(day).rows()).extracting(ReportService.Row::label).containsExactly("آخر روز");
        var leap=ReportService.period(filter("MONTH",1403,12,null,null,"ALL",false));assertThat(Duration.between(leap.start(),leap.end()).toDays()).isEqualTo(30);
    }
    @Test void combinedFiltersStatusesAndCircularVisitsHaveAccurateTotals() {
        String a=assignment("2026-10-19T04:30:00Z","گشت صبح");String t=tour(a,"FINISHED","2026-10-19T04:35:00Z","2026-10-19T05:05:00Z");
        for(int i=0;i<3;i++)event(t,i+1,"VISIT",Map.of("stopIndex",i,"checkpointId",i==1?"warehouse":"gate"));
        event(t,4,"INCIDENT",Map.of("note","قفل انبار بررسی شد","severity","LOW"));
        assignment("2020-01-01T00:00:00Z","قدیمی دریافت‌نشده");assignment("2099-01-01T00:00:00Z","آینده");
        String b=assignment("2026-10-19T05:30:00Z","توقف");tour(b,"ABORTED","2026-10-19T05:30:00Z","2026-10-19T05:20:00Z");
        var report=reports.generate(filter("ALL",null,null,null,null,"ALL",true));
        assertThat(report.summary()).isEqualTo(new ReportService.Totals(4,1,0,1,1,1,12,3,1));
        var completed=report.rows().stream().filter(r->r.assignmentId().equals(a)).findFirst().orElseThrow();
        assertThat(completed.visitedStops()).isEqualTo(3);assertThat(completed.delayMinutes()).isEqualTo(5);assertThat(completed.durationMinutes()).isEqualTo(30);assertThat(completed.events().getFirst().checkpoint()).isEqualTo("دروازه ورودی");
        assertThat(report.rows().stream().filter(r->r.assignmentId().equals(b)).findFirst().orElseThrow().clockInvalid()).isTrue();
        assertThat(reports.generate(filter("ALL",null,null,null,null,"FINISHED",false)).rows()).hasSize(1);
        var exact=new ReportService.Filter(UUID.fromString(guardId),UUID.fromString(routeId),UUID.fromString(siteId),UUID.fromString(a),"ALL",null,null,null,null,"ALL",false);
        assertThat(reports.generate(exact).rows()).extracting(ReportService.Row::assignmentId).containsExactly(a);
        var wrongSite=new ReportService.Filter(UUID.fromString(guardId),UUID.fromString(routeId),UUID.randomUUID(),null,"ALL",null,null,null,null,"ALL",false);
        assertThatThrownBy(()->reports.generate(wrongSite)).isInstanceOf(ApiException.class);
    }
    @Test void personAndRouteFiltersExcludeOtherGuardsAndRoutes() {
        String wanted=assignment("2026-10-19T04:30:00Z","انتخاب‌شده");
        String another=assignment("2026-10-19T05:30:00Z","نگهبان دیگر"),otherGuard=Store.id();
        s.db.update("INSERT INTO app_user(id,username,name,password_hash,role) SELECT ?,?,'نگهبان دوم',password_hash,'GUARD' FROM app_user WHERE username='guard'",otherGuard,"guard-"+otherGuard);
        s.db.update("UPDATE assignment SET guard_id=? WHERE id=?",otherGuard,another);
        String otherRoute=Store.id();s.db.update("INSERT INTO patrol_route(id,site_id,name,checkpoint_ids,expected_minutes) VALUES(?,?,?,?,?)",otherRoute,siteId,"مسیر دوم","[]",30);
        String third=assignment("2026-10-19T06:30:00Z","مسیر دیگر");s.db.update("UPDATE assignment SET route_id=? WHERE id=?",otherRoute,third);
        assertThat(reports.generate(filter("MONTH",1405,7,null,null,"ALL",false)).rows()).extracting(ReportService.Row::assignmentId).containsExactly(wanted);
        var guardOnly=new ReportService.Filter(UUID.fromString(guardId),null,null,null,"MONTH",1405,7,null,null,"ALL",false);
        assertThat(reports.generate(guardOnly).rows()).extracting(ReportService.Row::assignmentId).contains(wanted,third).doesNotContain(another);
        var routeOnly=new ReportService.Filter(null,UUID.fromString(routeId),null,null,"MONTH",1405,7,null,null,"ALL",false);
        assertThat(reports.generate(routeOnly).rows()).extracting(ReportService.Row::assignmentId).containsExactly(wanted,another);
    }
    @Test void exportsUseImmutableSnapshotAndRejectOtherUsers() throws Exception {
        String original=assignment("2026-10-19T04:30:00Z","=HYPERLINK(\"https://invalid\")");
        var preview=preview(filter("ALL",null,null,null,null,"ALL",false));String path="/api/admin/reports/"+preview.path("id").asText();
        assignment("2026-10-19T05:30:00Z","بعد از گزارش");
        byte[] xlsx=mvc.perform(get(path+"/xlsx").header("Authorization",admin)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))){var sheet=b.getSheet("نوبت‌های گشت");assertThat(sheet.getLastRowNum()).isEqualTo(1);assertThat(sheet.isRightToLeft()).isTrue();assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();assertThat(sheet.getRow(1).getCell(4).getCellType()).isEqualTo(CellType.STRING);assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).startsWith("=HYPERLINK");assertThat(sheet.getRow(1).getCell(9).getCellType()).isEqualTo(CellType.NUMERIC);assertThat(sheet.getRow(1).getCell(17).getCellType()).isEqualTo(CellType.BLANK);assertThat(sheet.getRow(1).getCell(20).getStringCellValue()).isEqualTo(original);assertThat(b.getNumberOfSheets()).isEqualTo(2);}
        byte[] pdf=mvc.perform(get(path+"/pdf").header("Authorization",admin)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();try(var d=Loader.loadPDF(pdf)){assertThat(d.getNumberOfPages()).isGreaterThan(0);assertThat(d.getDocumentInformation().getSubject()).isEqualTo(preview.path("id").asText());}
        mvc.perform(get(path+"/pdf").header("Authorization",guard)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/reports").header("Authorization",guard).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(filter("ALL",null,null,null,null,"ALL",false)))).andExpect(status().isForbidden());
        String other=Store.id();s.db.update("INSERT INTO app_user(id,username,name,password_hash,role) SELECT ?,?,name,password_hash,'SUPERVISOR' FROM app_user WHERE username='admin'",other,"super-"+other);String supervisor=login("super-"+other,"Admin@12345");mvc.perform(get(path+"/xlsx").header("Authorization",supervisor)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/reports/"+UUID.randomUUID()+"/pdf").header("Authorization",admin)).andExpect(status().isGone());
    }
    @Test void invalidRangesAndEmptyReportsAreHandled() throws Exception {
        mvc.perform(post("/api/admin/reports").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(filter("MONTH",1405,13,null,null,"ALL",false)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/reports").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(filter("RANGE",null,null,LocalDate.of(2026,10,20),LocalDate.of(2026,10,19),"ALL",false)))).andExpect(status().isBadRequest());
        var empty=reports.generate(filter("ALL",null,null,null,null,"ALL",false));assertThat(empty.rows()).isEmpty();try(var d=Loader.loadPDF(files.pdf(empty))){assertThat(d.getNumberOfPages()).isEqualTo(1);}try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(files.xlsx(empty)))){assertThat(b.getSheet("نوبت‌های گشت").getLastRowNum()).isZero();}
    }
    @Test void multipageFilesPreserveEventDetailsAndRenderForVisualReview() throws Exception {
        for(int i=0;i<32;i++){
            String a=assignment(Instant.parse("2026-10-19T04:30:00Z").plusSeconds(i*3600L).toString(),"نوبت گشت شماره "+(i+1));String t=tour(a,"FINISHED","2026-10-19T04:35:00Z","2026-10-19T05:05:00Z");
            event(t,1,"VISIT",Map.of("checkpointId","gate","stopIndex",0,"location",Map.of("latitude",35.72,"longitude",51.33,"accuracy",42)));
            if(i==0)event(t,2,"INCIDENT",Map.of("note","بررسی حصار کارگاه و قفل انبار انجام شد. ".repeat(70)+"پایان توضیح طولانی","severity","HIGH"));
        }
        var report=reports.generate(filter("MONTH",1405,7,null,null,"ALL",true));var dir=Path.of("target/report-qa");Files.createDirectories(dir);
        byte[] pdf=files.pdf(report),xlsx=files.xlsx(report);Files.write(dir.resolve("report.pdf"),pdf);Files.write(dir.resolve("report.xlsx"),xlsx);
        try(var doc=Loader.loadPDF(pdf)){assertThat(doc.getNumberOfPages()).isGreaterThan(3);var renderer=new PDFRenderer(doc);for(int i=0;i<doc.getNumberOfPages();i++)javax.imageio.ImageIO.write(renderer.renderImageWithDPI(i,100),"png",dir.resolve("page-"+(i+1)+".png").toFile());}
        try(var b=new XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))){assertThat(b.getSheet("رویدادها").getLastRowNum()).isEqualTo(33);assertThat(b.getSheet("رویدادها").getRow(2).getCell(11).getStringCellValue()).endsWith("پایان توضیح طولانی");}
    }
}
