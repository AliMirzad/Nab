import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:image_picker/image_picker.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import 'local_store.dart';
import 'sync_service.dart';
import 'location_service.dart';
import 'background.dart';
import 'route_policy.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final local = await LocalStore.open();
  try {
    await initializeBackground();
  } catch (_) {}
  runApp(NabApp(local: local));
}

class NabApp extends StatelessWidget {
  final LocalStore local;
  const NabApp({super.key, required this.local});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'ناب — نظم آفرینان بیدار',
    debugShowCheckedModeBanner: false,
    locale: const Locale('fa'),
    supportedLocales: const [Locale('fa')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    theme: ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff187a65)),
      scaffoldBackgroundColor: const Color(0xfff4f7f5),
      appBarTheme: const AppBarTheme(
        backgroundColor: Color(0xfff4f7f5),
        centerTitle: false,
      ),
      inputDecorationTheme: const InputDecorationTheme(
        border: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(12)),
        ),
      ),
    ),
    home: GuardHome(local: local),
  );
}

class GuardHome extends StatefulWidget {
  final LocalStore local;
  const GuardHome({super.key, required this.local});
  @override
  State<GuardHome> createState() => _GuardHomeState();
}

class _GuardHomeState extends State<GuardHome> with WidgetsBindingObserver {
  late final syncer = SyncService(widget.local);
  final gps = LocationService();
  Json? session, active;
  List<Json> assignments = [], tours = [], events = [];
  int pending = 0;
  bool ready = false, syncing = false, working = false, connected = true;
  String message = '', lastSync = '', syncError = '';
  Timer? timer;
  Timer? locationTimer;
  StreamSubscription<List<ConnectivityResult>>? network;
  final base = TextEditingController(
    text: const String.fromEnvironment(
      'API_URL',
      defaultValue: 'http://10.0.2.2:8080',
    ),
  );
  final username = TextEditingController(), password = TextEditingController();
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    boot();
  }

  Future<void> boot() async {
    session = await SyncService.session();
    final server = await widget.local.meta('enrolledServer');
    if (server != null) base.text = server;
    await reload();
    unawaited(resumeGps());
    timer = Timer.periodic(
      const Duration(seconds: 60),
      (_) => sync(manual: false),
    );
    locationTimer = Timer.periodic(const Duration(minutes: 2), (_) async {
      final tour = active;
      if (tour == null) return;
      try {
        await widget.local.append(tour['id'], 'LOCATION', gps.evidence());
        await reload();
      } catch (_) {}
    });
    network = Connectivity().onConnectivityChanged.listen((states) {
      connected = !states.contains(ConnectivityResult.none);
      if (mounted) setState(() {});
      if (connected) sync(manual: false);
    });
    if (mounted) setState(() => ready = true);
    unawaited(sync(manual: false));
  }

  Future<void> reload() async {
    if (session == null) return;
    final id = session!['user']['id'] as String;
    assignments = await widget.local.assignments(id);
    tours = await widget.local.tours(id);
    active = tours.where((t) => t['status'] == 'ACTIVE').firstOrNull;
    events = active == null ? [] : await widget.local.events(active!['id']);
    pending = await widget.local.pendingCount(id);
    lastSync = await widget.local.meta('lastSync:$id') ?? '';
    syncError = await widget.local.meta('syncError:$id') ?? '';
    if (mounted) setState(() {});
  }

  Future<void> resumeGps() async {
    if (active == null) return;
    final id = active!['id'] as String;
    try {
      await gps.start((data) async {
        await widget.local.append(id, 'LOCATION', data);
        await reload();
      });
    } catch (_) {}
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(reload());
      unawaited(sync(manual: false));
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    timer?.cancel();
    locationTimer?.cancel();
    network?.cancel();
    gps.stop();
    base.dispose();
    username.dispose();
    password.dispose();
    super.dispose();
  }

  Future<void> sync({bool manual = true}) async {
    if (session == null || syncing) return;
    if (mounted) setState(() => syncing = true);
    try {
      final done = await syncer.sync(session!);
      if (manual && done) notify('اطلاعات ارسال و برنامه‌ها دریافت شدند');
    } catch (e) {
      if (manual) {
        notify(
          e is ApiError
              ? e.message
              : 'اتصال برقرار نشد؛ اطلاعات روی گوشی محفوظ است',
        );
      }
    } finally {
      await reload();
      if (mounted) setState(() => syncing = false);
    }
  }

  void notify(String text) {
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
    }
  }

  Future<void> login() async {
    setState(() {
      working = true;
      message = '';
    });
    try {
      session = await syncer.login(base.text, username.text, password.text);
      password.clear();
      await reload();
      await resumeGps();
    } catch (e) {
      session = await SyncService.session();
      message = e is ApiError
          ? e.message
          : e is StateError
          ? e.message.toString()
          : 'ارتباط برقرار نشد؛ آدرس سرور و اینترنت را بررسی کنید';
      await reload();
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  Future<void> action(Future<void> Function() fn) async {
    if (working) return;
    setState(() => working = true);
    try {
      await fn();
      await reload();
    } catch (e) {
      notify(
        e is StateError
            ? e.message.toString()
            : 'ثبت انجام نشد؛ دوباره تلاش کنید',
      );
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  Json? get assignment => active == null
      ? null
      : assignments
            .where((a) => a['id'] == active!['assignment_id'])
            .firstOrNull;
  Set<String> get visited => events
      .where((e) => e['event']['type'] == 'VISIT')
      .map((e) => e['event']['data']['checkpointId'] as String)
      .toSet();
  int get completedStops =>
      events.where((e) => e['event']['type'] == 'VISIT').length;
  bool get strictRoute =>
      assignment != null && RoutePolicy.ordered(assignment!);
  Future<void> begin(Json a) async {
    Json? first;
    if (RoutePolicy.ordered(a)) {
      final point = RoutePolicy.points(a).first;
      final code = await Navigator.of(context).push<String>(
        MaterialPageRoute(
          builder: (_) => ScannerPage(title: 'شروع مسیر: ${point['name']}'),
        ),
      );
      if (code == null || !mounted) return;
      first = RoutePolicy.visit(point, 0, code, gps.evidence());
    }
    await action(() async {
      await widget.local.start(
        session!['user']['id'],
        a,
        gps.evidence(),
        firstVisit: first,
      );
      await reload();
      unawaited(resumeGps());
    });
  }

  Future<void> scan() async {
    if (active == null) return;
    final tourId = active!['id'];
    final code = await Navigator.of(context)
        .push<String>(MaterialPageRoute(builder: (_) => const ScannerPage()));
    if (code == null || !mounted) return;
    await action(() async {
      final points = (assignment!['snapshot']['checkpoints'] as List)
          .cast<Json>();
      final point = strictRoute
          ? (completedStops < points.length ? points[completedStops] : null)
          : points.where((p) => p['tagCode'] == code.trim()).firstOrNull;
      if (strictRoute && point != null && point['tagCode'] != code.trim()) {
        throw StateError(
          'نقطه بعدی ${point['name']} است؛ اسکن خارج از ترتیب ثبت نمی‌شود',
        );
      }
      if (point == null) throw StateError('این QR متعلق به مسیر گشت نیست');
      if (!strictRoute && visited.contains(point['id'])) {
        notify('این نقطه قبلاً ثبت شده است');
        return;
      }
      await widget.local.append(tourId, 'VISIT', {
        ...gps.evidence(),
        'checkpointId': point['id'],
        'checkpointName': point['name'],
        'tagCode': code.trim(),
        'method': 'QR',
        if (strictRoute) 'stopIndex': completedStops,
      });
      notify('${point['name']} روی گوشی ثبت شد');
    });
  }

  Future<void> incident() async {
    if (active == null) return;
    final tourId = active!['id'];
    final result = await Navigator.of(context)
        .push<Json>(MaterialPageRoute(builder: (_) => const IncidentPage()));
    if (result == null) return;
    await action(() async {
      await widget.local.append(tourId, 'INCIDENT', {
        ...gps.evidence(),
        'note': result['note'],
        'severity': result['severity'],
        'hasPhoto': result['photoPath'] != null,
      }, photoPath: result['photoPath']);
      notify('گزارش روی گوشی ذخیره شد');
    });
  }

  Future<void> finish() async {
    final missing =
        (assignment!['snapshot']['checkpoints'] as List).length -
        (strictRoute ? completedStops : visited.length);
    if (strictRoute && missing > 0) {
      notify(
        'هنوز $missing نقطه باقی مانده است؛ نقطه پایان باید به ترتیب اسکن شود',
      );
      return;
    }
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('پایان گشت'),
        content: Text(
          missing > 0
              ? '$missing نقطه ثبت نشده است. گشت پایان یابد؟ اطلاعات ثبت‌شده محفوظ می‌ماند.'
              : 'گشت تمام شود؟ اطلاعات در گوشی ذخیره و همگام‌سازی امتحان می‌شود.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('ادامه گشت'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('پایان گشت'),
          ),
        ],
      ),
    );
    if (confirm != true) return;
    await action(() async {
      await widget.local.append(active!['id'], 'FINISH', gps.evidence());
      await gps.stop();
      notify('پایان گشت روی گوشی ثبت شد');
    });
    unawaited(sync());
  }

  Future<void> abort() async {
    final note = TextEditingController();
    final reason = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('توقف گشت ناقص'),
        content: TextField(
          controller: note,
          maxLength: 4000,
          maxLines: 3,
          decoration: const InputDecoration(labelText: 'علت توقف (اجباری)'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('ادامه گشت'),
          ),
          FilledButton(
            onPressed: () {
              if (note.text.trim().isNotEmpty) {
                Navigator.pop(ctx, note.text.trim());
              }
            },
            child: const Text('ثبت به‌عنوان گشت متوقف‌شده'),
          ),
        ],
      ),
    );
    note.dispose();
    if (reason == null) return;
    await action(() async {
      await widget.local.append(active!['id'], 'ABORT', {
        ...gps.evidence(),
        'note': reason,
      });
      await gps.stop();
      notify('گشت متوقف شد و موفق محسوب نمی‌شود');
    });
    unawaited(sync());
  }

  String when(String? value) {
    final d = DateTime.tryParse(value ?? '')?.toLocal();
    return d == null
        ? '—'
        : '${d.year}/${d.month.toString().padLeft(2, '0')}/${d.day.toString().padLeft(2, '0')} · ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
  }

  Widget card(Widget child) => Card(
    elevation: 0,
    color: Colors.white,
    margin: const EdgeInsets.only(bottom: 14),
    child: Padding(padding: const EdgeInsets.all(20), child: child),
  );
  @override
  Widget build(BuildContext context) {
    if (!ready) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (session == null) {
      return Scaffold(
        body: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(28),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 430),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Icon(
                      Icons.shield_outlined,
                      size: 66,
                      color: Color(0xff187a65),
                    ),
                    const SizedBox(height: 15),
                    const Text(
                      'ناب',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 32,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const Text(
                      'همراه گشت شما، حتی بدون اینترنت',
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 35),
                    TextField(
                      controller: base,
                      textDirection: TextDirection.ltr,
                      decoration: const InputDecoration(labelText: 'آدرس سرور'),
                    ),
                    const SizedBox(height: 16),
                    TextField(
                      controller: username,
                      textDirection: TextDirection.ltr,
                      decoration: const InputDecoration(
                        labelText: 'نام کاربری',
                      ),
                    ),
                    const SizedBox(height: 16),
                    TextField(
                      controller: password,
                      textDirection: TextDirection.ltr,
                      obscureText: true,
                      decoration: const InputDecoration(labelText: 'رمز عبور'),
                      onSubmitted: (_) => working ? null : login(),
                    ),
                    const SizedBox(height: 20),
                    if (message.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 16),
                        child: Text(
                          message,
                          style: const TextStyle(color: Colors.red),
                        ),
                      ),
                    FilledButton(
                      onPressed: working ? null : login,
                      child: Padding(
                        padding: const EdgeInsets.all(13),
                        child: Text(
                          working
                              ? 'دریافت برنامه‌ها…'
                              : 'ورود و دریافت برنامه‌ها',
                        ),
                      ),
                    ),
                    const SizedBox(height: 22),
                    const Text(
                      'اولین ورود و دریافت مأموریت‌ها اینترنت می‌خواهد. پس از آن گشت‌ها به‌صورت آفلاین انجام می‌شوند.',
                      style: TextStyle(fontSize: 12, color: Colors.grey),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
    }
    return Scaffold(
      appBar: AppBar(
        title: const Row(
          children: [
            Icon(Icons.shield_outlined, color: Color(0xff187a65)),
            SizedBox(width: 8),
            Text('ناب', style: TextStyle(fontWeight: FontWeight.bold)),
          ],
        ),
        actions: [
          IconButton(
            tooltip: 'حساب و اتصال',
            onPressed: account,
            icon: const Icon(Icons.person_outline),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: sync,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(18, 8, 18, 32),
          children: [
            Text(
              'سلام، ${session!['user']['name']}',
              style: const TextStyle(fontSize: 23, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 6),
            const Text(
              'با خیال راحت گشت بزنید؛ هر ثبت ابتدا روی گوشی ذخیره می‌شود.',
              style: TextStyle(color: Colors.grey, fontSize: 12),
            ),
            const SizedBox(height: 22),
            card(
              Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Icon(
                        connected ? Icons.cloud_done_outlined : Icons.cloud_off,
                        color: const Color(0xff187a65),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          pending == 0
                              ? 'همه ثبت‌ها ارسال شده‌اند'
                              : '$pending ثبت در انتظار ارسال',
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                      ),
                      if (syncing)
                        const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'آخرین تلاش موفق: ${when(lastSync)}',
                    style: const TextStyle(fontSize: 11, color: Colors.grey),
                  ),
                  if (syncError.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 9),
                      child: Text(
                        syncError,
                        style: const TextStyle(
                          color: Colors.deepOrange,
                          fontSize: 12,
                        ),
                      ),
                    ),
                  const SizedBox(height: 12),
                  OutlinedButton.icon(
                    onPressed: syncing ? null : () => sync(),
                    icon: const Icon(Icons.sync),
                    label: const Text('همگام‌سازی و دریافت برنامه‌ها'),
                  ),
                ],
              ),
            ),
            if (active != null && assignment != null)
              activeCard()
            else ...[
              const Padding(
                padding: EdgeInsets.only(bottom: 12),
                child: Text(
                  'برنامه گشت‌های شما',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
              ),
              if (assignments.isEmpty)
                card(
                  const Text(
                    'مأموریتی دریافت نشده است. پس از تخصیص مدیر، دکمه همگام‌سازی را بزنید.',
                  ),
                ),
              for (final a in assignments) assignmentCard(a),
            ],
            const SizedBox(height: 12),
            if (tours.any((t) => t['status'] != 'ACTIVE')) ...[
              const Text(
                'گشت‌های پایان‌یافته',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 12),
              for (final t in tours.where((t) => t['status'] != 'ACTIVE'))
                card(
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(
                      Icons.task_alt,
                      color: Color(0xff187a65),
                    ),
                    title: Text(
                      assignments
                              .where((a) => a['id'] == t['assignment_id'])
                              .firstOrNull?['label'] ??
                          'گشت',
                    ),
                    subtitle: Text(
                      '${t['status'] == 'ABORTED' ? 'متوقف‌شده · ' : ''}${when(t['finished_at'])}',
                    ),
                    trailing: const Icon(Icons.chevron_left),
                    onTap: () => history(t),
                  ),
                ),
            ],
          ],
        ),
      ),
    );
  }

  Widget assignmentCard(Json a) {
    final done =
        tours.any((t) => t['assignment_id'] == a['id']) ||
        a['serverTour'] != null;
    return card(
      Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            a['label'],
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 6),
          Text(
            '${a['snapshot']['siteName']} · ${a['snapshot']['routeName']}',
            style: const TextStyle(color: Colors.grey, fontSize: 12),
          ),
          const SizedBox(height: 10),
          Text(
            '${when(a['startsAt'])}\nتا ${when(a['endsAt'])}',
            style: const TextStyle(fontSize: 12),
          ),
          const SizedBox(height: 10),
          Text(
            '${(a['snapshot']['checkpoints'] as List).length} نقطه · ${a['snapshot']['expectedMinutes']} دقیقه',
          ),
          const SizedBox(height: 14),
          FilledButton.icon(
            onPressed: done || working ? null : () => begin(a),
            icon: Icon(done ? Icons.check : Icons.play_arrow),
            label: Text(
              done
                  ? 'قبلاً شروع شده'
                  : RoutePolicy.ordered(a)
                  ? 'اسکن نقطه شروع و آغاز گشت'
                  : 'شروع گشت آفلاین',
            ),
          ),
        ],
      ),
    );
  }

  Widget activeCard() {
    final points = (assignment!['snapshot']['checkpoints'] as List)
        .cast<Json>();
    return Column(
      children: [
        card(
          Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                'گشت در حال اجرا',
                style: TextStyle(color: Color(0xff187a65)),
              ),
              const SizedBox(height: 8),
              Text(
                assignment!['label'],
                style: const TextStyle(
                  fontSize: 23,
                  fontWeight: FontWeight.bold,
                ),
              ),
              Text(assignment!['snapshot']['siteName']),
              const SizedBox(height: 16),
              LinearProgressIndicator(
                value: points.isEmpty
                    ? 0
                    : (strictRoute ? completedStops : visited.length) /
                          points.length,
                borderRadius: BorderRadius.circular(8),
                minHeight: 7,
              ),
              const SizedBox(height: 8),
              Text(
                '${strictRoute ? completedStops : visited.length} از ${points.length} نقطه ثبت شده',
              ),
              if (strictRoute && completedStops < points.length)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(
                    'نقطه بعدی: ${points[completedStops]['name']}',
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                ),
              const SizedBox(height: 18),
              FilledButton.icon(
                onPressed:
                    working || (strictRoute && completedStops == points.length)
                    ? null
                    : scan,
                icon: const Icon(Icons.qr_code_scanner),
                label: const Padding(
                  padding: EdgeInsets.all(14),
                  child: Text('اسکن QR نقطه'),
                ),
              ),
              const SizedBox(height: 9),
              OutlinedButton.icon(
                onPressed: working ? null : incident,
                icon: const Icon(Icons.add_a_photo_outlined),
                label: const Text('ثبت حادثه و تصویر'),
              ),
              TextButton(
                onPressed: working ? null : finish,
                child: const Text('پایان گشت و تلاش برای سینک'),
              ),
              if (strictRoute)
                TextButton(
                  onPressed: working ? null : abort,
                  child: const Text('توقف گشت ناقص با ذکر علت'),
                ),
            ],
          ),
        ),
        card(
          Column(
            children: [
              for (final (index, point) in points.indexed)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(
                    (strictRoute
                            ? index < completedStops
                            : visited.contains(point['id']))
                        ? Icons.check_circle
                        : Icons.radio_button_unchecked,
                    color:
                        (strictRoute
                            ? index < completedStops
                            : visited.contains(point['id']))
                        ? const Color(0xff187a65)
                        : Colors.grey,
                  ),
                  title: Text(
                    '${index + 1}. ${point['name']}${strictRoute ? (index == 0
                              ? ' · شروع'
                              : index == points.length - 1
                              ? ' · پایان'
                              : '') : ''}',
                  ),
                  subtitle: Text(
                    (strictRoute
                            ? index < completedStops
                            : visited.contains(point['id']))
                        ? 'روی گوشی ثبت شده'
                        : 'منتظر اسکن QR',
                  ),
                ),
            ],
          ),
        ),
        const Text(
          'موقعیت ناموجود یا کم‌دقت مانع ثبت نیست. موقعیت‌های موجود فقط در طول گشت ذخیره می‌شوند.',
          style: TextStyle(color: Colors.grey, fontSize: 11),
        ),
      ],
    );
  }

  Future<void> history(Json tour) async {
    final rows = await widget.local.events(tour['id']);
    if (!mounted) return;
    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => SafeArea(
        child: SizedBox(
          height: MediaQuery.sizeOf(ctx).height * .75,
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              const Text(
                'ثبت‌های ذخیره‌شده روی گوشی',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
              ),
              for (final r in rows)
                ListTile(
                  title: Text(
                    ({
                          'START': 'شروع گشت',
                          'VISIT': 'بازدید نقطه',
                          'LOCATION': 'موقعیت',
                          'INCIDENT': 'حادثه',
                          'FINISH': 'پایان گشت',
                          'ABORT': 'توقف گشت ناقص',
                        })[r['event']['type']] ??
                        '',
                  ),
                  subtitle: Text(
                    '${r['event']['data']['checkpointName'] ?? r['event']['data']['note'] ?? ''}\n${when(r['event']['capturedAt'])}',
                  ),
                  trailing: Icon(
                    r['synced'] == 1 &&
                            (r['photo_path'] == null || r['photo_synced'] == 1)
                        ? Icons.cloud_done
                        : Icons.cloud_upload_outlined,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> account() async {
    await showModalBottomSheet(
      context: context,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                session!['user']['name'],
                style: const TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.bold,
                ),
              ),
              Text(session!['base'], textDirection: TextDirection.ltr),
              const SizedBox(height: 15),
              const Text(
                'اگر نشست سرور منقضی شد، با همان حساب دوباره وارد شوید؛ گشت‌ها و صف ارسال پاک نمی‌شوند.',
              ),
              TextButton(
                onPressed: () {
                  Navigator.pop(ctx);
                  reauth();
                },
                child: const Text('ورود مجدد برای اتصال به سرور'),
              ),
              TextButton(
                onPressed: () async {
                  if (active != null || pending > 0) {
                    notify('ابتدا گشت را تمام و اطلاعات را همگام‌سازی کنید');
                    return;
                  }
                  await SyncService.vault.delete(key: 'session');
                  session = null;
                  if (ctx.mounted) Navigator.pop(ctx);
                  if (mounted) {
                    setState(() {});
                  }
                },
                child: const Text('خروج از حساب'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> reauth() async {
    final pass = TextEditingController();
    final value = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('ورود مجدد'),
        content: TextField(
          controller: pass,
          obscureText: true,
          decoration: InputDecoration(
            labelText: 'رمز ${session!['user']['username']}',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('انصراف'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, pass.text),
            child: const Text('ورود'),
          ),
        ],
      ),
    );
    pass.dispose();
    if (value == null) return;
    try {
      session = await syncer.login(
        session!['base'],
        session!['user']['username'],
        value,
      );
      await sync();
    } catch (e) {
      notify(e.toString());
    }
  }
}

class ScannerPage extends StatefulWidget {
  final String title;
  const ScannerPage({super.key, this.title = 'اسکن نقطه کنترل'});
  @override
  State<ScannerPage> createState() => _ScannerPageState();
}

class _ScannerPageState extends State<ScannerPage> {
  bool handled = false;
  final controller = MobileScannerController(formats: [BarcodeFormat.qrCode]);
  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(widget.title),
      actions: [
        IconButton(
          onPressed: () => controller.toggleTorch(),
          icon: const Icon(Icons.flashlight_on),
        ),
      ],
    ),
    body: Column(
      children: [
        Expanded(
          child: MobileScanner(
            controller: controller,
            onDetect: (capture) {
              final value = capture.barcodes.firstOrNull?.rawValue;
              if (value != null && !handled) {
                handled = true;
                Navigator.pop(context, value);
              }
            },
            errorBuilder: (context, error) => const Center(
              child: Padding(
                padding: EdgeInsets.all(30),
                child: Text(
                  'دوربین در دسترس نیست. مجوز دوربین را در تنظیمات گوشی بررسی کنید.',
                ),
              ),
            ),
          ),
        ),
        const Padding(
          padding: EdgeInsets.all(25),
          child: Text(
            'QR نصب‌شده در محل را مقابل دوربین بگیرید. اسکن به اینترنت نیاز ندارد.',
          ),
        ),
      ],
    ),
  );
}

class IncidentPage extends StatefulWidget {
  const IncidentPage({super.key});
  @override
  State<IncidentPage> createState() => _IncidentPageState();
}

class _IncidentPageState extends State<IncidentPage> {
  final note = TextEditingController();
  String severity = 'MEDIUM';
  String? photoPath;
  bool busy = false;
  @override
  void dispose() {
    note.dispose();
    super.dispose();
  }

  Future<void> photo() async {
    setState(() => busy = true);
    try {
      final shot = await ImagePicker().pickImage(
        source: ImageSource.camera,
        imageQuality: 70,
        maxWidth: 1600,
        requestFullMetadata: false,
      );
      if (shot == null) return;
      final directory = await getApplicationDocumentsDirectory();
      final path = p.join(directory.path, 'incident-${uuid.v4()}.jpg');
      await File(shot.path).copy(path);
      if (mounted) setState(() => photoPath = path);
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'دوربین در دسترس نیست؛ می‌توانید گزارش را بدون تصویر ثبت کنید.',
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('ثبت گزارش حادثه')),
    body: ListView(
      padding: const EdgeInsets.all(22),
      children: [
        TextField(
          controller: note,
          maxLines: 6,
          maxLength: 4000,
          decoration: const InputDecoration(labelText: 'شرح حادثه'),
        ),
        const SizedBox(height: 18),
        DropdownButtonFormField<String>(
          initialValue: severity,
          decoration: const InputDecoration(labelText: 'اهمیت'),
          items: const [
            DropdownMenuItem(value: 'LOW', child: Text('کم')),
            DropdownMenuItem(value: 'MEDIUM', child: Text('متوسط')),
            DropdownMenuItem(value: 'HIGH', child: Text('زیاد')),
          ],
          onChanged: (v) => severity = v!,
        ),
        const SizedBox(height: 18),
        if (photoPath != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 15),
            child: Image.file(File(photoPath!), height: 220),
          ),
        OutlinedButton.icon(
          onPressed: busy ? null : photo,
          icon: const Icon(Icons.camera_alt_outlined),
          label: Text(photoPath == null ? 'افزودن تصویر' : 'گرفتن تصویر جدید'),
        ),
        const SizedBox(height: 20),
        FilledButton(
          onPressed: busy
              ? null
              : () {
                  if (note.text.trim().isEmpty) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('شرح حادثه را بنویسید')),
                    );
                    return;
                  }
                  Navigator.pop(context, {
                    'note': note.text.trim(),
                    'severity': severity,
                    'photoPath': photoPath,
                  });
                },
          child: const Padding(
            padding: EdgeInsets.all(13),
            child: Text('ثبت گزارش روی گوشی'),
          ),
        ),
        const SizedBox(height: 14),
        const Text(
          'متن و تصویر بدون اینترنت ذخیره می‌شوند و در همگام‌سازی بعدی ارسال خواهند شد.',
          style: TextStyle(fontSize: 12, color: Colors.grey),
        ),
      ],
    ),
  );
}
