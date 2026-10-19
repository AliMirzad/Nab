import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:nab_guard/local_store.dart';
import 'package:nab_guard/sync_service.dart';
import 'package:nab_guard/route_policy.dart';

void main() {
  sqfliteFfiInit();
  late LocalStore store;
  late Directory directory;
  late String path;
  final assignment = {
    'id': 'assignment-1',
    'label': 'Night patrol',
    'serverTour': null,
    'snapshot': {'checkpoints': []},
    'startsAt': '2026-10-19T00:00:00Z',
  };
  setUp(() async {
    directory = await Directory.systemTemp.createTemp('nab-test-');
    path = '${directory.path}/offline.db';
    store = await LocalStore.open(path: path, factory: databaseFactoryFfi);
  });
  tearDown(() async {
    await store.db.close();
    await directory.delete(recursive: true);
  });
  Json orderedAssignment({bool loop = false}) => {
    ...assignment,
    'id': 'ordered-${loop ? 'loop' : 'linear'}',
    'snapshot': {
      'routePolicy': 'ORDERED_V1',
      'checkpoints': [
        {'id': 'a', 'name': 'Gate', 'tagCode': 'QR-A', 'stopIndex': 0},
        {'id': 'b', 'name': 'Warehouse', 'tagCode': 'QR-B', 'stopIndex': 1},
        {
          'id': loop ? 'a' : 'c',
          'name': 'End',
          'tagCode': loop ? 'QR-A' : 'QR-C',
          'stopIndex': 2,
        },
      ],
    },
  };
  Json stop(Json a, int i) => RoutePolicy.visit(
    RoutePolicy.points(a)[i],
    i,
    RoutePolicy.points(a)[i]['tagCode'],
    {'location': null},
  );
  test(
    'ordered start requires the start QR and commits both records atomically',
    () async {
      final a = orderedAssignment();
      await expectLater(
        store.start('guard', a, {}, firstVisit: stop(a, 1)),
        throwsStateError,
      );
      expect(await store.tours('guard'), isEmpty);
      final t = await store.start('guard', a, {}, firstVisit: stop(a, 0));
      expect((await store.events(t['id'])).map((e) => e['event']['type']), [
        'START',
        'VISIT',
      ]);
    },
  );
  test('out of order and early finish leave no accepted local event, even after restart', () async {
    final a = orderedAssignment();
    final t = await store.start('guard', a, {}, firstVisit: stop(a, 0));
    await expectLater(
      store.append(t['id'], 'VISIT', stop(a, 2)),
      throwsStateError,
    );
    await expectLater(store.append(t['id'], 'FINISH', {}), throwsStateError);
    expect((await store.events(t['id'])).length, 2);
    await store.db.close();
    store = await LocalStore.open(path: path, factory: databaseFactoryFfi);
    await store.append(t['id'], 'VISIT', stop(a, 1));
    await store.append(t['id'], 'VISIT', stop(a, 2));
    await store.append(t['id'], 'FINISH', {'location': null});
    expect((await store.tours('guard')).single['status'], 'FINISHED');
  });
  test(
    'circular route needs another visit of the same QR as the final stop',
    () async {
      final a = orderedAssignment(loop: true);
      final t = await store.start('guard', a, {}, firstVisit: stop(a, 0));
      await store.append(t['id'], 'VISIT', stop(a, 1));
      await expectLater(store.append(t['id'], 'FINISH', {}), throwsStateError);
      await expectLater(
        store.append(t['id'], 'VISIT', stop(a, 0)),
        throwsStateError,
      );
      await store.append(t['id'], 'VISIT', stop(a, 2));
      await store.append(t['id'], 'FINISH', {});
      expect(
        (await store.events(t['id']))
            .where((e) => e['event']['type'] == 'VISIT')
            .length,
        3,
      );
    },
  );
  test(
    'an incomplete tour can only stop as unsuccessful with a reason',
    () async {
      final a = orderedAssignment();
      final t = await store.start('guard', a, {}, firstVisit: stop(a, 0));
      await expectLater(
        store.append(t['id'], 'ABORT', {'note': ''}),
        throwsStateError,
      );
      await store.append(t['id'], 'ABORT', {'note': 'Gate inaccessible'});
      expect((await store.tours('guard')).single['status'], 'ABORTED');
      await expectLater(store.append(t['id'], 'FINISH', {}), throwsStateError);
    },
  );
  test(
    'an entire offline tour survives app restart with stable ordered ids',
    () async {
      await store.cacheAssignments('guard', [assignment]);
      final tour = await store.start('guard', assignment, {'location': null});
      await store.append(tour['id'], 'VISIT', {
        'checkpointId': 'p1',
        'location': null,
      });
      await store.append(tour['id'], 'INCIDENT', {
        'note': 'Offline report',
        'severity': 'HIGH',
      });
      await store.append(tour['id'], 'FINISH', {'location': null});
      final before = await store.pending('guard');
      await store.db.close();
      store = await LocalStore.open(path: path, factory: databaseFactoryFfi);
      final after = await store.pending('guard');
      expect(after.map((e) => e['id']), before.map((e) => e['id']));
      expect(after.map((e) => e['sequence_no']), [1, 2, 3, 4]);
      expect((await store.tours('guard')).single['status'], 'FINISHED');
      expect(await store.pendingCount('guard'), 4);
    },
  );
  test(
    'failed network never acknowledges or removes locally committed events',
    () async {
      await store.start('guard', assignment, {});
      final service = SyncService(
        store,
        client: MockClient((_) async => throw const SocketException('offline')),
      );
      await expectLater(
        service.sync({
          'user': {'id': 'guard'},
          'base': 'https://example.com',
          'token': 't',
        }),
        throwsA(isA<SocketException>()),
      );
      expect(await store.pendingCount('guard'), 1);
    },
  );
  test(
    'receipts are required; an incomplete acknowledgement keeps the outbox',
    () async {
      await store.start('guard', assignment, {});
      final service = SyncService(
        store,
        client: MockClient(
          (_) async => http.Response('{"acceptedIds":[]}', 200),
        ),
      );
      await expectLater(
        service.sync({
          'user': {'id': 'guard'},
          'base': 'https://example.com',
          'token': 't',
        }),
        throwsStateError,
      );
      expect(await store.pendingCount('guard'), 1);
    },
  );
  test(
    'successful sync clears pending state but preserves local history',
    () async {
      final t = await store.start('guard', assignment, {});
      await store.append(t['id'], 'FINISH', {});
      final service = SyncService(
        store,
        client: MockClient((request) async {
          if (request.url.path.endsWith('bootstrap')) {
            return http.Response(
              jsonEncode({
                'assignments': [assignment],
              }),
              200,
            );
          }
          final events = jsonDecode(request.body)['events'] as List;
          return http.Response(
            jsonEncode({'acceptedIds': events.map((e) => e['id']).toList()}),
            200,
          );
        }),
      );
      expect(
        await service.sync({
          'user': {'id': 'guard'},
          'base': 'https://example.com',
          'token': 't',
        }),
        true,
      );
      expect(await store.pendingCount('guard'), 0);
      expect((await store.events(t['id'])).length, 2);
    },
  );
  test('foreground and background sync cannot hold the same lease', () async {
    expect(await store.lease('a'), true);
    expect(await store.lease('b'), false);
    await store.release('b');
    expect(await store.lease('b'), false);
    await store.release('a');
    expect(await store.lease('b'), true);
  });
  test('a photo is not acknowledged without a valid upload receipt', () async {
    final tour = await store.start('guard', assignment, {});
    final image = File('${directory.path}/incident.jpg');
    await image.writeAsBytes([255, 216, 255, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    await store.append(tour['id'], 'INCIDENT', {
      'note': 'Test',
      'severity': 'LOW',
    }, photoPath: image.path);
    final service = SyncService(
      store,
      client: MockClient((request) async {
        if (request.url.path.contains('/photos/')) {
          return http.Response('{}', 200);
        }
        final events = jsonDecode(request.body)['events'] as List;
        return http.Response(
          jsonEncode({'acceptedIds': events.map((e) => e['id']).toList()}),
          200,
        );
      }),
    );
    await expectLater(
      service.sync({
        'user': {'id': 'guard'},
        'base': 'https://example.com',
        'token': 't',
      }),
      throwsStateError,
    );
    expect(await store.pendingCount('guard'), 1);
    expect((await store.pendingPhotos('guard')).single['photo_synced'], 0);
  });
  test(
    'a bounded sync run does not report complete while events remain',
    () async {
      final tour = await store.start('guard', assignment, {});
      for (int i = 0; i < 2000; i++) {
        await store.append(tour['id'], 'LOCATION', {'location': null});
      }
      final service = SyncService(
        store,
        client: MockClient((request) async {
          if (request.url.path.endsWith('/bootstrap')) {
            return http.Response(
              jsonEncode({
                'assignments': [assignment],
              }),
              200,
            );
          }
          final events = jsonDecode(request.body)['events'] as List;
          return http.Response(
            jsonEncode({'acceptedIds': events.map((e) => e['id']).toList()}),
            200,
          );
        }),
      );
      final session = {
        'user': {'id': 'guard'},
        'base': 'https://example.com',
        'token': 't',
      };
      expect(await service.sync(session), false);
      expect(await store.pendingCount('guard'), 1);
      expect(await service.sync(session), true);
      expect(await store.pendingCount('guard'), 0);
    },
  );
  test(
    'one active tour and one execution per assignment are enforced',
    () async {
      final t = await store.start('guard', assignment, {});
      await expectLater(
        store.start('guard', {...assignment, 'id': 'a2'}, {}),
        throwsStateError,
      );
      await store.append(t['id'], 'FINISH', {});
      await expectLater(store.append(t['id'], 'VISIT', {}), throwsStateError);
      await expectLater(
        store.start('guard', assignment, {}),
        throwsA(isA<DatabaseException>()),
      );
    },
  );
  test('one guard never sees or sends another guards queue', () async {
    await store.cacheAssignments('a', [assignment]);
    await store.start('a', assignment, {});
    expect(await store.pendingCount('b'), 0);
    expect(await store.pending('b'), isEmpty);
    expect(await store.assignments('b'), isEmpty);
  });
}
