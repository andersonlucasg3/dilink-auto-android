# Telefon Klient Ilovasi (app-client)

## Umumiy ko'rinish

Telefon klienti VD serverini joylashtirishni, avtomobilni avto-yangilashni va 3-ulanish releysini boshqaradi. Telefon ilovasi:

1. Avtomobildan TCP ulanishlarini 9637 portida tinglaydi (control, NIO ServerSocketChannel)
2. Handshake'ga qurilma ma'lumoti, vdServerJarPath va `targetFps` ni o'qib javob beradi
3. Handshake'dan appVersionName ni taqqoslaydi (versionCode ga fallback bilan) — versiya mos kelmasa `UPDATING_CAR` yuboradi va avtomobil ilovasini dadb orqali avto-yangilaydi
4. Handshake'dan keyin avtomobildan video (9638) va kiritish (9639) ulanishlarini qabul qiladi
5. vd-server.jar ni `/sdcard/DiLinkAuto/` ga joylashtiradi va VD serverini ishga tushiradi (FPS argumenti bilan)
6. VD serveridan localhost:19637 da teskari ulanishni qabul qiladi (NIO ServerSocketChannel)
7. H.264 videoni VD serveridan avtomobilga video ulanishi orqali uzatadi
8. Avtomobildan teginish hodisalarini (kiritish ulanishi) VD serveriga uzatadi, `Dispatchers.IO` da ishlov beriladi

**Ekran yozib olish yo'q. MediaProjection yo'q.** Barcha video VD server jarayonidan keladi.

## Komponentlar

### ClientApp

Application klassi. Xabarnoma kanallarini yaratadi (`dilinkauto_service`, `dilinkauto_update`), yaratish paytida `UpdateManager` va `ShizukuManager` ni initsializatsiya qiladi.

### UpdateManager

GitHub Releases'dan yangi versiyalarni tekshiradigan o'z-o'zini yangilash mexanizmi.
- `checkForUpdate(force)`: `https://api.github.com/repos/andersonlucasg3/dilink-auto-android/releases/latest` so'raydi, teg nomidagi semver'ni o'rnatilgan versionName bilan solishtiradi. Majburiy bo'lmasa 6 soatlik tanaffusga rioya qiladi.
- `downloadUpdate()`: Progress ko'rsatish bilan `HttpsURLConnection` orqali APK yuklaydi. `PackageManager.getPackageArchiveInfo()` orqali tekshiradi.
- `installUpdate(context)`: Shizuku mavjud bo'lganda ovozsiz o'rnatish uchun `pm install -r` dan foydalanadi; aks holda `FileProvider` URI orqali tizim paket o'rnatuvchisini ochadi.
- Holatlar: Idle, Checking, Available, Downloading, ReadyToInstall, Installing, Installed, UpToDate, Error. `StateFlow` orqali taqdim etiladi.

### MainActivity

Ikki ekranli kirish nuqtasi:

- **OnboardingScreen** (birinchi ishga tushirish): 7 bosqichli sehrgar — Welcome, All Files Access, Battery Optimization, Accessibility Service, Notification Access, Car Setup, Done. Har bir ruxsat bosqichi usiz nima ishlamasligini tushuntiradi. Ruxsat berilganda avtomatik ravishda oldinga o'tadi. Foydalanuvchi istalgan bosqichni o'tkazib yuborishi mumkin.
- **ClientScreen** (keyingi ishga tushirishlar): holat kartochkasi, start/stop tugmasi, Install on Car, o'z-o'zini yangilash kartochkasi, Share Logs tugmasi va onboarding paytida o'tkazib yuborilgan ruxsatlarning qolgan holati.

### ConnectionService

3 ajratilgan ulanish bilan telefon-avtomobil ulanishining hayotiy siklini boshqaradigan oldingi plan xizmati. Telefon ilovasi ochilganda avtomatik ravishda ishga tushadi (masalan, avtomobil USB ADB orqali).

- **Control connection** (port 9637): `0.0.0.0` da NIO TCP serveri, handshake, heartbeat, ilova buyruqlari, DATA kanalini boshqaradi
- **Video connection** (port 9638): handshake'dan keyin qabul qilinadi, video releyi uchun VirtualDisplayClient'ga beriladi
- **Input connection** (port 9639): handshake'dan keyin qabul qilinadi, INPUT kadr tinglovchisi localhost teginish yozuvlarida NetworkOnMainThreadException oldini olish uchun `Dispatchers.IO` da ishlov beriladi
- `deployAssets()`: vd-server.jar ni sdcard ga, app-server.apk ni filesDir ga chiqaradi
- Versiya nomuvofiqligini aniqlaydi → `UPDATING_CAR` yuboradi → avtomobil ilovasini dadb orqali avto-yangilaydi (WiFi ADB, dadb 1.2.10)
- Aqlli tarmoq qayta chaqiruvi: `TRANSPORT_WIFI` orqali filtrlanadi — faqat WiFi o'zgarishlariga reaksiya qiladi, 3G/4G mobil ma'lumotlar tebranishlarini e'tiborsiz qoldiradi
- Video kadrlarni (H.264 CONFIG + FRAME) VD dan avtomobilga video ulanishi orqali uzatadi
- Avtomobildan teginish hodisalarini (kiritish ulanishi) VD serveriga yo'naltiradi
- Ilovalar ro'yxatini 96x96 PNG ikonkalari bilan avtomobilga control ulanishi orqali yuboradi
- LAUNCH_APP, GO_BACK, GO_HOME buyruqlarini boshqaradi va VD serveriga yo'naltiradi
- VD serveri hali ulanmagan bo'lsa, ilovalarni ishga tushirishni navbatga qo'yadi
- Avtomobil avto-aniqlash uchun mDNS xizmatini ro'yxatdan o'tkazadi
- Xizmat ishga tushganda `FileLog.rotate()` — oldingi sessiya jurnalini arxivlaydi
- "Install on Car" tugmasi: qo'lda o'rnatish + handshake versiya nomuvofiqligida avtomatik

### VirtualDisplayClient

VD server jarayonidan `localhost:19637` da teskari ulanishni qabul qiladi. Ikki Connection parametrini qabul qiladi: `videoConnection` va `controlConnection`.

- NIO ServerSocketChannel accept (bloklanmaydigan) — VD serveri telefonga ulanadi
- O'qiydi: `MSG_VIDEO_CONFIG`, `MSG_VIDEO_FRAME`, `MSG_STACK_EMPTY`, `MSG_DISPLAY_READY`
- Yozadi: `CMD_LAUNCH_APP`, `CMD_GO_BACK`, `CMD_GO_HOME`, `CMD_INPUT_TOUCH` (0x32)
- Video kadrlar `videoConnection.sendVideo()` orqali uzatiladi (boshqaruv trafigidan ajratilgan)
- Bo'sh stek signali (`MSG_STACK_EMPTY`) avtomobilga `controlConnection.sendControl()` orqali yo'naltiriladi
- Localhost'ga teginish yozuvlari `writeLock` ostida `FrameCodec.writeAll()` bilan sinxron
- Uzilganda: fizik displeyni tiklaydi (`cmd display power-on 0` + `KEYCODE_WAKEUP`) VD server jarayoni tozalashdan oldin o'ldirilganda xavfsizlik tarmog'i sifatida

### AdbBridge

Zaxira shell buyruq yordamchisi. VD server operatsiyalari va to'g'ridan-to'g'ri API reflection muvaffaqiyatsiz bo'lganda displey quvvatini boshqarish uchun `Runtime.exec()` yordamida `execShell()` va `execFast()` ni taqdim etadi.

### VirtualDisplayManager

VD ishlatilmayotganda fizik displeyda ilovalarni ishga tushirishni boshqaradi. Imo-ishora asosidagi kiritish inyeksiyasi uchun `InputInjectionService` ga ko'prik qiladi.

### VideoEncoder

`AUTO_MIRROR` virtual displeydan foydalanadigan MediaProjection + MediaCodec H.264 kodlovchi. Alternativ kodlash yo'li (VD serveri orqali o'tadigan asosiy oqim konveyerida ishlatilmaydi).

### FileLog

Android logcat filtrlashini aylanib o'tadigan fayl asosidagi jurnal yuritish vositasi (HyperOS tizim bo'lmagan ilovalar uchun `Log.i/d` ni filtrlaydi).

- `/sdcard/DiLinkAuto/client.log` ga yozadi
- `rotate()`: joriy jurnalni `client-YYYYMMDD-HHmmss.log` sifatida arxivlaydi, yangisini boshlaydi
- `zipLogs()`: almashish uchun barcha `.log` fayllaridan `dilinkauto-logs.zip` yaratadi
- Ko'pi bilan 10 ta jurnal saqlaydi (9 arxivlangan + joriy)
- Oqim-xavfsiz: yozish oqimi tomonidan to'kiladigan qulfsiz ConcurrentLinkedQueue
- Standart logcat chiqishi uchun `android.util.Log.*` ni ham chaqiradi

### Multi-Touch Relay

Teginish hodisalari avtomobildan kiritish ulanishi orqali CMD_INPUT_TOUCH (0x32) sifatida xom MotionEvent ma'lumotlari bilan keladi. `handleInputFrame` localhost soket yozuvlariga ruxsat berish uchun `Dispatchers.IO` (Main emas) da ishlov beriladi. Telefon DOWN/MOVE/UP hodisalarini pointerId bilan to'g'ridan-to'g'ri VD serveriga oqimlaydi, u barcha faol ko'rsatkichlar bilan to'liq MotionEvent qurilishini boshqaradi.

## Talab qilinadigan ruxsatlar

| Permission | Purpose |
|-----------|---------|
| MANAGE_EXTERNAL_STORAGE | VD JAR ni sdcard ga joylashtirish uchun All Files Access |
| Accessibility Service | dispatchGesture orqali virtual displeyga teginish inyeksiyasi (hodisalarni kuzatishsiz) |
| Notification Access | Avtomobilga xabarnomalarni yo'naltirish (progress bilan) |
| API Shizuku | ADB-siz VD serverini joylashtirish va ovozsiz o'z-o'zini yangilash uchun yuqori darajali shell kirish |
| QUERY_ALL_PACKAGES | Ilovalar ishga tushirgich to'ri |
| REQUEST_INSTALL_PACKAGES | Avtomobil ilovasini dadb orqali avto-yangilash |

## Bog'liqliklar

- Jetpack Compose + Material 3
- kotlinx-coroutines
- dadb 1.2.10 (avtomobil avto-yangilash uchun WiFi ADB)
- Shizuku api/provider/aidl 13.1.5
- Protocol module (avtomobil ilovasi bilan umumiy)
