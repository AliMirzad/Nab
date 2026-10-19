# ناب — اپ نگهبان اندروید

کد Flutter با ذخیره SQLite و صف ارسال پایدار. نام بسته Dart: `nab_guard`؛ شناسه Android: `ir.nab.guard`. iOS فعلاً متوقف است و برای آن ساخت یا تحویل تأییدشده‌ای ادعا نمی‌شود.

راهنمای ابزارها، اجرا و ساخت در [README اصلی](../README.md) و [تحویل پروژه](../docs/HANDOFF.md) است. Android SDK باید نصب و در `flutter doctor -v` قابل شناسایی باشد.

| فایل | مسئولیت |
| --- | --- |
| `lib/main.dart` | ورود، مأموریت‌ها، QR، گشت، حادثه و تاریخچه |
| `lib/local_store.dart` | تراکنش‌های SQLite، UUID، ترتیب رویداد، صف، قفل سینک |
| `lib/route_policy.dart` | کنترل ترتیب مسیر، شروع/پایان و مسیر حلقه‌ای |
| `lib/sync_service.dart` | احراز هویت، ارسال دسته‌ای، receipt و آپلود عکس |
| `lib/location_service.dart` | ثبت موقعیت بدون مسدودکردن گشت |
| `lib/background.dart` | درخواست سینک پس‌زمینه تحت کنترل سیستم‌عامل |

ورود و دریافت اولیه برنامه آنلاین است؛ اجرای برنامه دریافت‌شده آفلاین می‌ماند. پاک‌کردن داده اپ یا حذف نصب، صف محلی ارسال‌نشده را از بین می‌برد. قبل از این اقدامات، سینک و رسید دریافت را بررسی کنید.

```powershell
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
```

تست اتصال واقعی، با `--dart-define=NAB_INTEGRATION_URL=http://localhost:8080` فعال می‌شود و فقط باید به سرور دمو وصل شود. تست‌های SQLite جای آزمون دوربین، GPS و عملکرد پس‌زمینه روی گوشی واقعی را نمی‌گیرند.
