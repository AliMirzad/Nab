package ir.nab;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    public record Actor(String id,String username,String name,String role) {}
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    @Bean SecurityFilterChain filterChain(HttpSecurity http,Store store,@Value("${nab.allowed-origins}") String origins) throws Exception {
        var cors=new CorsConfiguration();cors.setAllowedOrigins(Arrays.asList(origins.split(",")));
        cors.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));cors.setAllowedHeaders(List.of("Authorization","Content-Type"));
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/api/**",cors);
        http.csrf(c->c.disable()).cors(c->c.configurationSource(source)).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.OPTIONS,"/**").permitAll().requestMatchers("/api/auth/login","/api/health","/","/index.html","/assets/**","/favicon.svg").permitAll().requestMatchers("/api/**").authenticated().anyRequest().denyAll())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->{res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"برای همگام‌سازی دوباره وارد شوید؛ اطلاعات روی گوشی محفوظ است\"}");}).accessDeniedHandler((req,res,ex)->{res.setStatus(403);res.setContentType("application/json");res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Access denied\"}");}))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                    String h=req.getHeader("Authorization");
                    if(h!=null&&h.startsWith("Bearer ")&&h.length()<300) {
                        var rows=store.db.queryForList("SELECT u.*,s.expires_at FROM app_session s JOIN app_user u ON u.id=s.user_id WHERE s.token_hash=? AND u.active=TRUE",hash(h.substring(7)));
                        if(!rows.isEmpty()&&Instant.parse((String)rows.getFirst().get("expires_at")).isAfter(Instant.now())) {
                            var r=rows.getFirst();var actor=new Actor((String)r.get("id"),(String)r.get("username"),(String)r.get("name"),(String)r.get("role"));
                            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_"+actor.role()))));
                        }
                    }
                    chain.doFilter(req,res);
                }
            },UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
