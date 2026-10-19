package ir.nab;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
@Component
public class Seed implements ApplicationRunner {
    final Store s;final PasswordEncoder passwords;final String password;final boolean demo;
    Seed(Store s,PasswordEncoder passwords,@Value("${nab.admin-password}") String password,@Value("${nab.demo}") boolean demo) {this.s=s;this.passwords=passwords;this.password=password;this.demo=demo;}
    @Override public void run(ApplicationArguments args) {
        if(s.db.queryForObject("SELECT COUNT(*) FROM app_user",Integer.class)>0) return;
        if(!demo&&(password.length()<12||password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)) throw new IllegalStateException("Set ADMIN_PASSWORD to at least 12 characters and at most 72 UTF-8 bytes for first startup, or DEMO_MODE=true locally.");
        String admin=Store.id();s.db.update("INSERT INTO app_user(id,username,name,password_hash,role) VALUES(?,?,?,?,?)",admin,"admin","مدیر ناب",passwords.encode(demo?"Admin@12345":password),"ADMIN");if(!demo) return;
        String guard=Store.id();s.db.update("INSERT INTO app_user(id,username,name,password_hash,role) VALUES(?,?,?,?,?)",guard,"guard","علی رضایی",passwords.encode("Guard@12345"),"GUARD");
        var c=new AdminController(s,passwords);var actor=new SecurityConfig.Actor(admin,"admin","مدیر ناب","ADMIN");String site=c.site(new AdminController.SiteInput("مجتمع صنعتی سپهر","تهران، شهرک صنعتی؛ پروژه نمایشی"),actor).get("id");var ids=new java.util.ArrayList<String>();String[] names={"ورودی اصلی","انبار مرکزی","ساختمان اداری","پارکینگ شمالی","اتاق تأسیسات"};
        for(int i=0;i<names.length;i++) ids.add(c.point(new AdminController.PointInput(site,names[i],35.7219+i*0.0006,51.3347+i*0.0008),actor).get("id"));String route=c.route(new AdminController.RouteInput(site,"گشت پیرامونی",ids,45),actor).get("id");Instant now=Instant.now();
        for(int i=0;i<3;i++) c.assign(new AdminController.AssignmentInput(guard,route,i==0?"گشت اول شیفت":"گشت شماره "+(i+1),now.plusSeconds(i*3600L-1800),now.plusSeconds((i+1)*3600L)),actor);
    }
}
