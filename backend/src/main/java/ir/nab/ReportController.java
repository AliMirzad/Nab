package ir.nab;

import ir.nab.SecurityConfig.Actor;
import jakarta.validation.Valid;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/reports")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class ReportController {
    final ReportService service;
    final ReportFiles files;
    private record Saved(String owner,Instant expires,ReportService.Report report) {}
    private final Map<String,Saved> snapshots=new LinkedHashMap<>();
    ReportController(ReportService service,ReportFiles files){this.service=service;this.files=files;}
    @PostMapping public ReportService.Report generate(@Valid @RequestBody ReportService.Filter filter,@AuthenticationPrincipal Actor actor) {
        var report=service.generate(filter);
        synchronized(snapshots){
            snapshots.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now()));
            while(snapshots.size()>=30)snapshots.remove(snapshots.keySet().iterator().next());
            snapshots.put(report.id(),new Saved(actor.id(),Instant.now().plusSeconds(900),report));
        }
        return report;
    }
    private ReportService.Report get(UUID id,Actor actor){
        synchronized(snapshots){
            var saved=snapshots.get(id.toString());
            if(saved==null||saved.expires().isBefore(Instant.now()))throw new ApiException(410,"REPORT_EXPIRED","این گزارش منقضی شده است؛ دوباره نمایش گزارش را بزنید");
            if(!saved.owner().equals(actor.id()))throw new ApiException(403,"FORBIDDEN","این خروجی متعلق به حساب دیگری است");
            return saved.report();
        }
    }
    @GetMapping("/{id}/xlsx") public ResponseEntity<byte[]> xlsx(@PathVariable UUID id,@AuthenticationPrincipal Actor actor) throws Exception {
        return download(files.xlsx(get(id,actor)),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","xlsx");
    }
    @GetMapping("/{id}/pdf") public ResponseEntity<byte[]> pdf(@PathVariable UUID id,@AuthenticationPrincipal Actor actor) throws Exception {
        return download(files.pdf(get(id,actor)),"application/pdf","pdf");
    }
    private ResponseEntity<byte[]> download(byte[] bytes,String type,String ext){
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header("Content-Disposition",ContentDisposition.attachment().filename("nab-report."+ext).build().toString())
            .header("Cache-Control","no-store").contentLength(bytes.length).body(bytes);
    }
}
