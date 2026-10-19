import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:nab_guard/local_store.dart';
import 'package:nab_guard/sync_service.dart';
import 'package:nab_guard/route_policy.dart';

// Opt-in: run against a local DEMO_MODE server. Never points at customer data.
void main() {
  const base = String.fromEnvironment('NAB_INTEGRATION_URL');
  test(
    'offline SQLite -> app restart -> lost server receipt -> retry -> photos -> management report',
    () async {
      sqfliteFfiInit();
      final dir = await Directory.systemTemp.createTemp('nab-contract-');
      final path = '${dir.path}/guard.db';
      var local = await LocalStore.open(
        path: path,
        factory: databaseFactoryFfi,
      );
      final transport = http.Client();
      try {
        var service = SyncService(local, client: transport);
        final guard = await service.request(
          base,
          '/auth/login',
          data: {'username': 'guard', 'password': 'Guard@12345'},
        );
        final admin = await service.request(
          base,
          '/auth/login',
          data: {'username': 'admin', 'password': 'Admin@12345'},
        );
        final routesResponse = await transport.get(
          Uri.parse('$base/api/admin/routes'),
          headers: {'Authorization': 'Bearer ${admin['token']}'},
        );
        final routes = jsonDecode(routesResponse.body) as List;
        final assigned = await service.request(
          base,
          '/admin/assignments',
          token: admin['token'],
          data: {
            'guardId': guard['user']['id'],
            'routeId': routes.first['id'],
            'label': 'آزمون یکپارچه آفلاین و بازیابی',
            'startsAt': DateTime.now()
                .subtract(const Duration(minutes: 5))
                .toUtc()
                .toIso8601String(),
            'endsAt': DateTime.now()
                .add(const Duration(hours: 1))
                .toUtc()
                .toIso8601String(),
          },
        );
        final s = {...guard, 'base': base};
        await service.refresh(s);
        final assignment = (await local.assignments(guard['user']['id']))
            .singleWhere((a) => a['id'] == assigned['id']);
        final stops = RoutePolicy.points(assignment);
        final tour = await local.start(
          guard['user']['id'],
          assignment,
          {'location': null, 'locationStatus': 'UNAVAILABLE'},
          firstVisit: RoutePolicy.visit(
            stops.first,
            0,
            stops.first['tagCode'],
            {'location': null},
          ),
        );
        for (final (index, point) in stops.indexed.skip(1)) {
          await local.append(tour['id'], 'VISIT', {
            'checkpointId': point['id'],
            'checkpointName': point['name'],
            'tagCode': point['tagCode'],
            'method': 'QR',
            'stopIndex': index,
            'location': null,
          });
        }
        await local.append(tour['id'], 'LOCATION', {
          'location': {
            'latitude': 35.722,
            'longitude': 51.336,
            'accuracy': 2500.0,
            'fixAt': '2020-01-01T00:00:00Z',
            'source': 'device',
          },
          'locationStatus': 'STALE',
        });
        final image = File('${dir.path}/incident.png');
        await image.writeAsBytes(
          base64Decode(
            'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jS1sAAAAASUVORK5CYII=',
          ),
        );
        await local.append(tour['id'], 'INCIDENT', {
          'note': 'گزارش آزمایشی؛ ذخیره تصویر بدون اینترنت و ارسال پس از اتصال',
          'severity': 'LOW',
          'hasPhoto': true,
          'location': null,
        }, photoPath: image.path);
        await local.append(tour['id'], 'FINISH', {'location': null});
        final count = await local.pendingCount(guard['user']['id']);
        await local.db.close();
        local = await LocalStore.open(path: path, factory: databaseFactoryFfi);
        var lost = false;
        final lossy = SyncService(
          local,
          client: MockClient((req) async {
            final result = await transport.post(
              req.url,
              headers: req.headers,
              body: req.body,
            );
            expect(result.statusCode, 200);
            lost = true;
            throw const SocketException('connection lost after server commit');
          }),
        );
        await expectLater(lossy.sync(s), throwsA(isA<SocketException>()));
        expect(lost, true);
        expect(await local.pendingCount(guard['user']['id']), count);
        service = SyncService(local, client: transport);
        expect(await service.sync(s), true);
        expect(await local.pendingCount(guard['user']['id']), 0);
        expect((await local.events(tour['id'])).length, count);
        final reportsResponse = await transport.get(
          Uri.parse('$base/api/admin/tours'),
          headers: {'Authorization': 'Bearer ${admin['token']}'},
        );
        final reports = jsonDecode(reportsResponse.body) as List;
        final report = reports.singleWhere((t) => t['id'] == tour['id']);
        expect(report['status'], 'FINISHED');
        expect((report['events'] as List).length, count);
        expect(
          (report['events'] as List).singleWhere(
            (e) => e['event_type'] == 'INCIDENT',
          )['photo_id'],
          isNotNull,
        );
      } finally {
        transport.close();
        await local.db.close();
        await dir.delete(recursive: true);
      }
    },
    skip: base.isEmpty
        ? 'Set NAB_INTEGRATION_URL to a local demo server'
        : false,
  );
}
