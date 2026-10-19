import 'dart:async';
import 'dart:io';

import 'package:geolocator/geolocator.dart';

import 'local_store.dart';

class LocationService {
  Position? last;
  StreamSubscription<Position>? subscription;
  DateTime? lastStored;
  Json? current() => last == null
      ? null
      : {
          'latitude': last!.latitude,
          'longitude': last!.longitude,
          'accuracy': last!.accuracy,
          'fixAt': last!.timestamp.toUtc().toIso8601String(),
          'source': 'device',
          'isMocked': last!.isMocked,
        };
  Json evidence() => {
    'location': current(),
    'locationStatus': last == null
        ? 'UNAVAILABLE'
        : DateTime.now().difference(last!.timestamp).inMinutes > 5
        ? 'STALE'
        : last!.accuracy > 100
        ? 'LOW_ACCURACY'
        : 'AVAILABLE',
  };
  Future<void> start(Future<void> Function(Json) onFix) async {
    await stop();
    if (!await Geolocator.isLocationServiceEnabled()) return;
    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }
    if (permission == LocationPermission.denied ||
        permission == LocationPermission.deniedForever) {
      return;
    }
    last = await Geolocator.getLastKnownPosition();
    LocationSettings settings = const LocationSettings(
      accuracy: LocationAccuracy.high,
      distanceFilter: 15,
    );
    if (Platform.isAndroid) {
      settings = AndroidSettings(
        accuracy: LocationAccuracy.high,
        distanceFilter: 15,
        intervalDuration: const Duration(seconds: 60),
        foregroundNotificationConfig: const ForegroundNotificationConfig(
          notificationTitle: 'گشت ناب فعال است',
          notificationText: 'ثبت موقعیت فقط در طول گشت انجام می‌شود',
          enableWakeLock: true,
        ),
      );
    }
    if (Platform.isIOS) {
      settings = AppleSettings(
        accuracy: LocationAccuracy.high,
        distanceFilter: 15,
        activityType: ActivityType.fitness,
        pauseLocationUpdatesAutomatically: false,
        showBackgroundLocationIndicator: true,
        allowBackgroundLocationUpdates: true,
      );
    }
    subscription = Geolocator.getPositionStream(locationSettings: settings)
        .listen((p) async {
          last = p;
          if (lastStored == null ||
              DateTime.now().difference(lastStored!).inSeconds >= 60) {
            lastStored = DateTime.now();
            try {
              await onFix(evidence());
            } catch (_) {
              /* A finish may commit while the final fix is in flight. */
            }
          }
        }, onError: (Object error) {});
  }

  Future<void> stop() async {
    await subscription?.cancel();
    subscription = null;
    lastStored = null;
  }
}
