import 'dart:ui';

import 'package:flutter/widgets.dart';
import 'package:workmanager/workmanager.dart';

import 'local_store.dart';
import 'sync_service.dart';

@pragma('vm:entry-point')
void backgroundDispatcher() {
  Workmanager().executeTask((task, input) async {
    WidgetsFlutterBinding.ensureInitialized();
    DartPluginRegistrant.ensureInitialized();
    LocalStore? local;
    try {
      final session = await SyncService.session();
      if (session == null) return true;
      local = await LocalStore.open();
      return await SyncService(local).sync(session);
    } catch (_) {
      return false;
    } finally {
      await local?.db.close();
    }
  });
}

Future<void> initializeBackground() async {
  await Workmanager().initialize(backgroundDispatcher);
  await Workmanager().registerPeriodicTask(
    'ir.nab.sync',
    'ir.nab.sync',
    frequency: const Duration(minutes: 15),
    constraints: Constraints(networkType: NetworkType.connected),
    existingWorkPolicy: ExistingPeriodicWorkPolicy.keep,
  );
}
