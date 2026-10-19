package ir.nab;
import ir.nab.SecurityConfig.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api")
public class AuthController {
    private final Store s;private final PasswordEncoder passwords;private final int days;
    private final Map<String,Attempts> attempts=new ConcurrentHashMap<>();
    private record Attempts(int count,Instant until) {}
    AuthController(Store s,PasswordEncoder passwords,@Value("${nab.session-days}") int days) {this.s=s;this.passwords=passwords;this.days=days;}
    public record Login(@NotBlank @Size(max=80) String username,@NotBlank @Size(max=100) String password) {}
    @GetMapping("/health") Map<String,String> health() {return Map.of("status","UP");}
    @PostMapping("/auth/login") synchronized Map<String,Object> login(@Valid @RequestBody Login in,HttpServletRequest http) {
        if(in.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new ApiException(401,"INVALID_CREDENTIALS","نام کاربری یا رمز عبور نادرست است");
        Instant now=Instant.now();attempts.entrySet().removeIf(e->e.getValue().until().isBefore(now));String key=http.getRemoteAddr();Attempts a=attempts.get(key);
        if(a!=null&&a.count()>=15) throw new ApiException(429,"RATE_LIMIT","تعداد تلاش‌ها زیاد است؛ ده دقیقه دیگر تلاش کنید");
        var rows=s.db.queryForList("SELECT * FROM app_user WHERE username=? AND active=TRUE",in.username().trim());
        if(rows.isEmpty()||!passwords.matches(in.password(),(String)rows.getFirst().get("password_hash"))) {attempts.put(key,new Attempts(a==null?1:a.count()+1,now.plusSeconds(600)));throw new ApiException(401,"INVALID_CREDENTIALS","نام کاربری یا رمز عبور اشتباه است");}
        attempts.remove(key);var row=rows.getFirst();byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);String expires=now.plus(Duration.ofDays(days)).toString();
        s.db.update("INSERT INTO app_session VALUES (?,?,?,?)",SecurityConfig.hash(token),row.get("id"),expires,now.toString());s.audit((String)row.get("id"),"LOGIN",null);
        return Map.of("token",token,"expiresAt",expires,"user",Map.of("id",row.get("id"),"username",row.get("username"),"name",row.get("name"),"role",row.get("role")));
    }
    @GetMapping("/auth/me") Actor me(@AuthenticationPrincipal Actor actor) {return actor;}
    @PostMapping("/auth/logout") void logout(@RequestHeader("Authorization") String auth) {s.db.update("DELETE FROM app_session WHERE token_hash=?",SecurityConfig.hash(auth.substring(7)));}
}
