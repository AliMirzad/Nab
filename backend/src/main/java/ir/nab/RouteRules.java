package ir.nab;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Versioned snapshots leave already-issued offline work readable and replayable. */
final class RouteRules {
    static boolean ordered(JsonNode snapshot) { return "ORDERED_V1".equals(snapshot.path("routePolicy").asText()); }

    static void validate(JsonNode snapshot, List<JsonNode> previousVisits, String type, JsonNode data) {
        if (!ordered(snapshot)) return;
        var points = snapshot.path("checkpoints");
        if (!points.isArray() || points.size() < 2) throw ApiException.bad("مسیر باید نقطه شروع و پایان داشته باشد");
        int next = previousVisits.size();
        for (int i = 0; i < next; i++) requirePoint(points, i, previousVisits.get(i));
        switch (type) {
            case "START" -> requirePoint(points, 0, data);
            case "VISIT" -> requirePoint(points, next, data);
            case "FINISH" -> {
                if (next != points.size()) throw ApiException.conflict("پایان موفق فقط پس از اسکن تمام نقاط به ترتیب و ثبت نقطه پایان مجاز است");
            }
            default -> { }
        }
    }

    private static void requirePoint(JsonNode points, int index, JsonNode data) {
        if (index >= points.size()) throw ApiException.conflict("تمام نقاط این نوبت قبلاً ثبت شده‌اند");
        var expected = points.get(index);
        if (!data.path("stopIndex").isIntegralNumber() || data.path("stopIndex").intValue() != index
            || !expected.path("id").asText().equals(data.path("checkpointId").asText())
            || !expected.path("tagCode").asText().equals(data.path("tagCode").asText())
            || !"QR".equals(data.path("method").asText())) {
            throw ApiException.conflict("ترتیب اسکن معتبر نیست؛ نقطه بعدی: " + expected.path("name").asText());
        }
    }
}
