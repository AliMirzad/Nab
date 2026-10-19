package ir.nab;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
public class ApiException extends RuntimeException {
    final int status; final String code;
    public ApiException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    static ApiException bad(String message) { return new ApiException(400,"INVALID_REQUEST",message); }
    static ApiException conflict(String message) { return new ApiException(409,"SYNC_CONFLICT",message); }
    static ApiException missing() { return new ApiException(404,"NOT_FOUND","مورد پیدا نشد"); }
}
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e) { return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    ResponseEntity<?> validation(Exception e) { return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","اطلاعات واردشده معتبر نیست")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> integrity(Exception e) { return ResponseEntity.status(409).body(Map.of("code","CONFLICT","message","اطلاعات تکراری است یا همزمان تغییر کرده؛ دوباره تلاش کنید")); }
}
