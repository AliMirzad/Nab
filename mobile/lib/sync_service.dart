import 'dart:convert';
import 'dart:io';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;

import 'local_store.dart';

class ApiError implements Exception {
  final String message;
  final int status;
  ApiError(this.message, this.status);
  @override
  String toString() => message;
}

class SyncService {
  final LocalStore local;
  final http.Client client;
  SyncService(this.local, {http.Client? client})
    : client = client ?? http.Client();
  static const vault = FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
    iOptions: IOSOptions(
      accessibility: KeychainAccessibility.first_unlock_this_device,
    ),
  );
  static Future<Json?> session() async {
    final s = await vault.read(key: 'session');
    return s == null ? null : jsonDecode(s) as Json;
  }

  static Future<void> saveSession(Json s) =>
      vault.write(key: 'session', value: jsonEncode(s));
  static String normalizeBase(String input) {
    final uri = Uri.tryParse(input.trim());
    if (uri == null ||
        !uri.hasAuthority ||
        !['http', 'https'].contains(uri.scheme) ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        !(uri.path.isEmpty || uri.path == '/')) {
      throw ArgumentError('آدرس سرور باید مثل https://patrol.example.com باشد');
    }
    return '${uri.scheme}://${uri.authority}';
  }

  Future<Json> request(
    String base,
    String path, {
    String? token,
    Object? data,
  }) async {
    final headers = {
      'Content-Type': 'application/json',
      if (token != null) 'Authorization': 'Bearer $token',
    };
    final response =
        await (data == null
                ? client.get(Uri.parse('$base/api$path'), headers: headers)
                : client.post(
                    Uri.parse('$base/api$path'),
                    headers: headers,
                    body: jsonEncode(data),
                  ))
            .timeout(const Duration(seconds: 20));
    Json body = {};
    try {
      body = jsonDecode(response.body) as Json;
    } catch (_) {}
    if (response.statusCode >= 300) {
      throw ApiError(
        body['message'] ?? 'ارتباط با سرور برقرار نشد',
        response.statusCode,
      );
    }
    return body;
  }

  Future<Json> login(String base, String username, String password) async {
    base = normalizeBase(base);
    final enrolled = await local.meta('enrolledServer');
    if (enrolled != null && enrolled != base) {
      throw StateError(
        'این نصب به سرور $enrolled متصل است؛ برای تغییر سرور از نصب جداگانه استفاده کنید',
      );
    }
    final result = await request(
      base,
      '/auth/login',
      data: {'username': username.trim(), 'password': password},
    );
    if (result['user']['role'] != 'GUARD') {
      throw StateError('این اپ برای حساب نگهبان است');
    }
    final old = await session();
    if (old != null && old['user']['id'] != result['user']['id']) {
      final oldId = old['user']['id'] as String;
      if (await local.pendingCount(oldId) > 0 ||
          (await local.tours(oldId)).any((t) => t['status'] == 'ACTIVE')) {
        throw StateError(
          'گشت یا اطلاعات ارسال‌نشده حساب قبلی موجود است؛ با همان حساب وارد شوید',
        );
      }
    }
    final s = {...result, 'base': base};
    await local.setMeta('enrolledServer', base);
    await saveSession(s);
    // Save credentials first; failed bootstrap remains visibly retryable without losing events.
    await refresh(s);
    return s;
  }

  Future<void> refresh(Json s) async {
    final result = await request(
      s['base'],
      '/guard/bootstrap',
      token: s['token'],
    );
    await local.cacheAssignments(s['user']['id'], result['assignments']);
  }

  Future<bool> sync(Json s) async {
    final owner = uuid.v4();
    if (!await local.lease(owner)) return false;
    try {
      for (int page = 0; page < 20; page++) {
        if (!await local.lease(owner)) return false;
        final pending = await local.pending(s['user']['id']);
        if (pending.isEmpty) break;
        final response = await request(
          s['base'],
          '/guard/sync',
          token: s['token'],
          data: {
            'deviceId': await local.deviceId(),
            'events': pending.map((e) => jsonDecode(e['payload'])).toList(),
          },
        );
        final receipts = (response['acceptedIds'] as List)
            .cast<String>()
            .toSet();
        final sent = pending.map((e) => e['id'] as String).toSet();
        if (receipts.length != sent.length || !receipts.containsAll(sent)) {
          throw StateError('تأیید سرور کامل نیست؛ اطلاعات محلی حفظ شد');
        }
        await local.acknowledge(sent.toList());
      }
      for (final photo in await local.pendingPhotos(s['user']['id'])) {
        if (!await local.lease(owner)) return false;
        final file = File(photo['photo_path']);
        if (!await file.exists()) {
          throw StateError(
            'فایل تصویر گزارش در حافظه موجود نیست؛ رکورد حذف نشد',
          );
        }
        final req =
            http.MultipartRequest(
                'POST',
                Uri.parse('${s['base']}/api/photos/${photo['id']}'),
              )
              ..headers['Authorization'] = 'Bearer ${s['token']}'
              ..files.add(await http.MultipartFile.fromPath('file', file.path));
        final response = await client
            .send(req)
            .timeout(const Duration(seconds: 30));
        final body = await response.stream.bytesToString().timeout(
          const Duration(seconds: 20),
        );
        if (response.statusCode >= 300) {
          String message = 'ارسال تصویر ناموفق بود';
          try {
            message = jsonDecode(body)['message'] ?? message;
          } catch (_) {}
          throw ApiError(message, response.statusCode);
        }
        final receipt = jsonDecode(body);
        if (receipt is! Map ||
            receipt['id'] is! String ||
            (receipt['id'] as String).isEmpty) {
          throw StateError(
            'تأیید دریافت عکس معتبر نیست؛ تصویر در صف ارسال باقی می‌ماند',
          );
        }
        await local.db.update(
          'events',
          {'photo_synced': 1},
          where: 'id=?',
          whereArgs: [photo['id']],
        );
      }
      final complete = await local.pendingCount(s['user']['id']) == 0;
      await local.setMeta(
        'lastSync:${s['user']['id']}',
        DateTime.now().toUtc().toIso8601String(),
      );
      await local.setMeta('syncError:${s['user']['id']}', '');
      await refresh(s);
      return complete;
    } catch (e) {
      await local.setMeta(
        'syncError:${s['user']['id']}',
        e is ApiError
            ? e.message
            : 'اتصال کامل نشد؛ همه اطلاعات در گوشی محفوظ است',
      );
      rethrow;
    } finally {
      await local.release(owner);
    }
  }
}
