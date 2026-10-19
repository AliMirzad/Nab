import 'dart:convert';

import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';
import 'package:uuid/uuid.dart';

import 'route_policy.dart';

typedef Json = Map<String, dynamic>;
const uuid = Uuid();

class LocalStore {
  final Database db;
  LocalStore(this.db);
  static Future<LocalStore> open({
    String? path,
    DatabaseFactory? factory,
  }) async {
    final f = factory ?? databaseFactory;
    final db = await f.openDatabase(
      path ?? p.join(await f.getDatabasesPath(), 'nab-v1.db'),
      options: OpenDatabaseOptions(
        singleInstance: false,
        version: 1,
        onConfigure: (db) async {
          await db.execute('PRAGMA foreign_keys = ON');
          await db.execute('PRAGMA busy_timeout = 10000');
        },
        onCreate: (db, version) async {
          await db.execute(
            'CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)',
          );
          await db.execute(
            'CREATE TABLE assignments (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, body TEXT NOT NULL)',
          );
          await db.execute(
            'CREATE TABLE tours (id TEXT PRIMARY KEY, assignment_id TEXT NOT NULL UNIQUE, user_id TEXT NOT NULL, status TEXT NOT NULL, started_at TEXT NOT NULL, finished_at TEXT)',
          );
          await db.execute(
            'CREATE TABLE events (local_order INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, tour_id TEXT NOT NULL REFERENCES tours(id), user_id TEXT NOT NULL, sequence_no INTEGER NOT NULL, payload TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0, photo_path TEXT, photo_synced INTEGER NOT NULL DEFAULT 0, UNIQUE(tour_id,sequence_no))',
          );
          await db.execute(
            'CREATE INDEX events_pending ON events(user_id,synced,local_order)',
          );
        },
      ),
    );
    return LocalStore(db);
  }

  Future<String?> meta(String key) async {
    final rows = await db.query('meta', where: 'key=?', whereArgs: [key]);
    return rows.isEmpty ? null : rows.first['value'] as String;
  }

  Future<void> setMeta(String key, String value) => db
      .insert('meta', {
        'key': key,
        'value': value,
      }, conflictAlgorithm: ConflictAlgorithm.replace)
      .then((_) {});
  Future<String> deviceId() async {
    return db.transaction((txn) async {
      final rows = await txn.query(
        'meta',
        where: 'key=?',
        whereArgs: ['deviceId'],
      );
      if (rows.isNotEmpty) return rows.first['value'] as String;
      final id = uuid.v4();
      await txn.insert('meta', {'key': 'deviceId', 'value': id});
      return id;
    });
  }

  Future<void> cacheAssignments(
    String userId,
    List<dynamic> assignments,
  ) async {
    await db.transaction((txn) async {
      for (final a in assignments) {
        await txn.insert('assignments', {
          'id': a['id'],
          'user_id': userId,
          'body': jsonEncode(a),
        }, conflictAlgorithm: ConflictAlgorithm.replace);
      }
    });
  }

  Future<List<Json>> assignments(String userId) async =>
      (await db.query(
        'assignments',
        where: 'user_id=?',
        whereArgs: [userId],
      )).map((r) => jsonDecode(r['body'] as String) as Json).toList()..sort(
        (a, b) => (a['startsAt'] as String).compareTo(b['startsAt'] as String),
      );
  Future<List<Json>> tours(String userId) async => (await db.query(
    'tours',
    where: 'user_id=?',
    whereArgs: [userId],
    orderBy: 'started_at DESC',
  )).map((r) => Map<String, dynamic>.from(r)).toList();
  Future<List<Json>> events(String tourId) async => (await db.query(
    'events',
    where: 'tour_id=?',
    whereArgs: [tourId],
    orderBy: 'sequence_no',
  )).map((r) => {...r, 'event': jsonDecode(r['payload'] as String)}).toList();
  Future<Json> start(
    String userId,
    Json assignment,
    Json data, {
    Json? firstVisit,
  }) async {
    return db.transaction((txn) async {
      final startData = {...data, ...?firstVisit};
      RoutePolicy.validate(assignment, [], 'START', startData);
      if ((await txn.query(
        'tours',
        where: "user_id=? AND status='ACTIVE'",
        whereArgs: [userId],
      )).isNotEmpty) {
        throw StateError('ابتدا گشت فعلی را تمام کنید');
      }
      if (assignment['serverTour'] != null) {
        throw StateError('این مأموریت قبلاً روی سرور شروع شده است');
      }
      await txn.insert('assignments', {
        'id': assignment['id'],
        'user_id': userId,
        'body': jsonEncode(assignment),
      }, conflictAlgorithm: ConflictAlgorithm.ignore);
      final tour = {
        'id': uuid.v4(),
        'assignment_id': assignment['id'],
        'user_id': userId,
        'status': 'ACTIVE',
        'started_at': DateTime.now().toUtc().toIso8601String(),
      };
      await txn.insert('tours', tour);
      await _append(txn, tour, 'START', startData);
      if (RoutePolicy.ordered(assignment)) {
        await _append(txn, tour, 'VISIT', startData);
      }
      return tour;
    });
  }

  Future<String> append(
    String tourId,
    String type,
    Json data, {
    String? photoPath,
  }) async => db.transaction((txn) async {
    final rows = await txn.query(
      'tours',
      where: "id=? AND status='ACTIVE'",
      whereArgs: [tourId],
    );
    if (rows.isEmpty) throw StateError('گشت فعالی وجود ندارد');
    final stored = await txn.query(
      'assignments',
      where: 'id=?',
      whereArgs: [rows.first['assignment_id']],
    );
    if (stored.isNotEmpty) {
      final assignment = jsonDecode(stored.first['body'] as String) as Json;
      if (RoutePolicy.ordered(assignment)) {
        final previous = await txn.query(
          'events',
          columns: ['payload'],
          where: 'tour_id=?',
          whereArgs: [tourId],
          orderBy: 'sequence_no',
        );
        final visits = previous
            .map((r) => jsonDecode(r['payload'] as String) as Json)
            .where((e) => e['type'] == 'VISIT')
            .map((e) => e['data'] as Json)
            .toList();
        RoutePolicy.validate(assignment, visits, type, data);
      }
    }
    if (type == 'ABORT' && (data['note']?.toString().trim().isEmpty ?? true)) {
      throw StateError('علت توقف گشت را بنویسید');
    }
    final id = await _append(
      txn,
      Map<String, dynamic>.from(rows.first),
      type,
      data,
      photoPath: photoPath,
    );
    if (type == 'FINISH' || type == 'ABORT') {
      await txn.update(
        'tours',
        {
          'status': type == 'FINISH' ? 'FINISHED' : 'ABORTED',
          'finished_at': DateTime.now().toUtc().toIso8601String(),
        },
        where: 'id=?',
        whereArgs: [tourId],
      );
    }
    return id;
  });
  Future<String> _append(
    Transaction txn,
    Json tour,
    String type,
    Json data, {
    String? photoPath,
  }) async {
    final seq =
        (Sqflite.firstIntValue(
              await txn.rawQuery(
                'SELECT COALESCE(MAX(sequence_no),0) FROM events WHERE tour_id=?',
                [tour['id']],
              ),
            ) ??
            0) +
        1;
    final event = {
      'id': uuid.v4(),
      'tourId': tour['id'],
      'assignmentId': tour['assignment_id'],
      'sequence': seq,
      'type': type,
      'capturedAt': DateTime.now().toUtc().toIso8601String(),
      'data': data,
    };
    await txn.insert('events', {
      'id': event['id'],
      'tour_id': tour['id'],
      'user_id': tour['user_id'],
      'sequence_no': seq,
      'payload': jsonEncode(event),
      'photo_path': photoPath,
    });
    return event['id'] as String;
  }

  Future<List<Json>> pending(String userId) async => (await db.query(
    'events',
    where: 'user_id=? AND synced=0',
    whereArgs: [userId],
    orderBy: 'local_order',
    limit: 100,
  )).map((r) => Map<String, dynamic>.from(r)).toList();
  Future<List<Json>> pendingPhotos(String userId) async => (await db.query(
    'events',
    where:
        'user_id=? AND synced=1 AND photo_path IS NOT NULL AND photo_synced=0',
    whereArgs: [userId],
    orderBy: 'local_order',
  )).map((r) => Map<String, dynamic>.from(r)).toList();
  Future<int> pendingCount(String userId) async =>
      Sqflite.firstIntValue(
        await db.rawQuery(
          'SELECT COUNT(*) FROM events WHERE user_id=? AND (synced=0 OR (photo_path IS NOT NULL AND photo_synced=0))',
          [userId],
        ),
      ) ??
      0;
  Future<void> acknowledge(List<String> ids) async {
    await db.transaction((txn) async {
      for (final id in ids) {
        await txn.update(
          'events',
          {'synced': 1},
          where: 'id=?',
          whereArgs: [id],
        );
      }
    });
  }

  // SQLite lease prevents foreground and background isolates uploading concurrently.
  Future<bool> lease(String owner) => db.transaction((txn) async {
    final rows = await txn.query(
      'meta',
      where: 'key=?',
      whereArgs: ['syncLease'],
    );
    if (rows.isNotEmpty) {
      final old = jsonDecode(rows.first['value'] as String);
      if (old['owner'] != owner &&
          old['until'] > DateTime.now().millisecondsSinceEpoch) {
        return false;
      }
    }
    await txn.insert('meta', {
      'key': 'syncLease',
      'value': jsonEncode({
        'owner': owner,
        'until': DateTime.now()
            .add(const Duration(minutes: 2))
            .millisecondsSinceEpoch,
      }),
    }, conflictAlgorithm: ConflictAlgorithm.replace);
    return true;
  });
  Future<void> release(String owner) => db.transaction((txn) async {
    final rows = await txn.query(
      'meta',
      where: 'key=?',
      whereArgs: ['syncLease'],
    );
    if (rows.isNotEmpty &&
        jsonDecode(rows.first['value'] as String)['owner'] == owner) {
      await txn.delete('meta', where: 'key=?', whereArgs: ['syncLease']);
    }
  });
}
