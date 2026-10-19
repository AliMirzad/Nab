import 'local_store.dart' show Json;

class RoutePolicy {
  static bool ordered(Json assignment) =>
      assignment['snapshot']['routePolicy'] == 'ORDERED_V1';
  static List<Json> points(Json assignment) =>
      (assignment['snapshot']['checkpoints'] as List).cast<Json>();
  static void validate(
    Json assignment,
    List<Json> visits,
    String type,
    Json data,
  ) {
    if (!ordered(assignment)) return;
    final stops = points(assignment);
    if (stops.length < 2) {
      throw StateError('مسیر باید نقطه شروع و پایان داشته باشد');
    }
    void check(int index, Json value) {
      if (index >= stops.length) {
        throw StateError('همه نقاط این نوبت ثبت شده‌اند');
      }
      final expected = stops[index];
      if (value['stopIndex'] != index ||
          value['checkpointId'] != expected['id'] ||
          value['tagCode'] != expected['tagCode'] ||
          value['method'] != 'QR') {
        throw StateError(
          'اسکن خارج از ترتیب پذیرفته نیست؛ نقطه بعدی: ${expected['name']}',
        );
      }
    }

    for (int i = 0; i < visits.length; i++) {
      check(i, visits[i]);
    }
    if (type == 'START') check(0, data);
    if (type == 'VISIT') check(visits.length, data);
    if (type == 'FINISH' && visits.length != stops.length) {
      throw StateError(
        'پایان موفق فقط بعد از ثبت همه نقاط به ترتیب و اسکن نقطه پایان ممکن است',
      );
    }
  }

  static Json visit(Json point, int index, String code, Json evidence) => {
    ...evidence,
    'checkpointId': point['id'],
    'checkpointName': point['name'],
    'tagCode': code.trim(),
    'method': 'QR',
    'stopIndex': index,
  };
}
