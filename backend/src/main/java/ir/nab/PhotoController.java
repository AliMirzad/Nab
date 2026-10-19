package ir.nab;
import ir.nab.SecurityConfig.Actor;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController
@RequestMapping("/api/photos")
public class PhotoController {
    final Store s;final Path root;
    PhotoController(Store s,@Value("${nab.upload-dir}") String dir) throws IOException {this.s=s;root=Path.of(dir).toAbsolutePath().normalize();Files.createDirectories(root);}
    @PostMapping("/{eventId}") @PreAuthorize("hasRole('GUARD')") @Transactional Map<String,Object> upload(@PathVariable UUID eventId,@RequestParam("file") MultipartFile file,@AuthenticationPrincipal Actor actor) throws IOException {
        var event=s.one("SELECT e.event_type FROM patrol_event e JOIN patrol_tour t ON t.id=e.tour_id WHERE e.id=? AND t.guard_id=? FOR UPDATE",eventId.toString(),actor.id());if(!"INCIDENT".equals(event.get("event_type"))) throw ApiException.bad("تصویر باید متعلق به گزارش حادثه باشد");
        var previous=s.db.queryForList("SELECT id FROM photo WHERE event_id=?",eventId.toString());if(!previous.isEmpty()) return previous.getFirst();byte[] bytes=file.getBytes();if(bytes.length<12||bytes.length>6*1024*1024) throw ApiException.bad("تصویر باید حداکثر ۶ مگابایت باشد");
        boolean jpeg=(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255;boolean png=Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10});if(!jpeg&&!png) throw ApiException.bad("فقط تصویر JPEG و PNG پذیرفته می‌شود");
        String id=Store.id(),name=id+(jpeg?".jpg":".png"),contentType=jpeg?"image/jpeg":"image/png";
        Path target=root.resolve(name);Files.write(target,bytes,StandardOpenOption.CREATE_NEW);
        try{s.db.update("INSERT INTO photo VALUES(?,?,?,?,?)",id,eventId.toString(),actor.id(),name,contentType);}
        catch(RuntimeException ex){try{Files.deleteIfExists(target);}catch(IOException cleanup){ex.addSuppressed(cleanup);}throw ex;}
        return Map.of("id",id);
    }
    @GetMapping("/{id}") ResponseEntity<byte[]> read(@PathVariable UUID id,@AuthenticationPrincipal Actor actor) throws IOException {
        var p=s.one("SELECT * FROM photo WHERE id=?",id.toString());if(actor.role().equals("GUARD")&&!actor.id().equals(p.get("guard_id"))) throw new ApiException(403,"FORBIDDEN","دسترسی ندارید");return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)p.get("content_type"))).header("Cache-Control","private, max-age=3600").body(Files.readAllBytes(root.resolve((String)p.get("file_name"))));
    }
}
