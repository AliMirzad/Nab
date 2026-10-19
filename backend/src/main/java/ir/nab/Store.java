package ir.nab;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
public class Store {
    final JdbcTemplate db; final ObjectMapper json;
    Store(JdbcTemplate db,ObjectMapper json) { this.db=db;this.json=json; }
    static String id() { return UUID.randomUUID().toString(); }
    static String now() { return Instant.now().toString(); }
    String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    JsonNode decode(String value) { try { return json.readTree(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    Map<String,Object> one(String sql,Object... params) { var rows=db.queryForList(sql,params);if(rows.isEmpty()) throw ApiException.missing();return rows.getFirst(); }
    void audit(String actor,String action,String entity) { db.update("INSERT INTO audit_log VALUES (?,?,?,?,?)",id(),actor,action,entity,now()); }
}
