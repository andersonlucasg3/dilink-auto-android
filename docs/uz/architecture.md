# Arxitektura

## Umumiy ko'rinish

DiLink-Auto — to'rt modulli Gradle loyihasi:

```
DiLink-Auto/
├── protocol/        Android kutubxonasi — ikkala ilova uchun umumiy (Gradle moduli)
├── app-client/      Android ilovasi — telefonda ishlaydi (Gradle moduli)
├── app-server/      Android ilovasi — avtomobilda ishlaydi (Gradle moduli)
├── vd-server/       Android kutubxonasi — VirtualDisplay serveri (Gradle moduli)
```

## Virtual Display Arxitekturasi

**Telefon** VD serverini mahalliy ravishda joylashtiradi va ishga tushiradi. VD serveri telefon ilovasiga qayta ulanadi (localhost:19637 da teskari ulanish, to'liq NIO bloklanmaydigan). Ilovalar telefonning tabiiy DPI (480dpi) da VirtualDisplay'da renderlanadi, H.264 kodlash uchun avtomobilning ko'rish oynasi o'lchamiga GPU orqali masshtablanadi. Telefonning fizik ekrani butunlay mustaqil.

```
+- Phone -----------------------------------------------------------+
|                                                                   |
|  +---------------------+                                          |
|  | Physical Screen      |  Independent -- user can use phone      |
|  | (shows DiLink Auto   |  normally                               |
|  |  app or anything)    |                                          |
|  +---------------------+                                          |
|                                                                   |
|  +---------------------+      +--------------------------------+  |
|  | ConnectionService    |      | VD Server (shell UID 2000)     |  |
|  | 3 NIO TCP servers    |<----+| Deployed by PHONE app          |  |
|  |  9637: control       | 19637| Reverse-connects to phone      |  |
|  |  9638: video         |  NIO | NIO non-blocking localhost     |  |
|  |  9639: input         |      |                                |  |
|  | VD JAR deploy        |      | VirtualDisplay (phone DPI)     |  |
|  | Car auto-update      |      | GPU SurfaceScaler (downscale)  |  |
|  | FileLog              |      |   periodic re-draw on idle     |  |
|  | Relays video to car  |      | H.264 encoder (car viewport)   |  |
|  | Forwards touch to VD |      | App launcher (am start)        |  |
|  +----------+-----------+      | Input injector (IInputManager) |  |
|             |                  | NIO write queue + Selector read |  |
|             |                  +--------------------------------+  |
+-------------+------------------------------------------------------+
              | WiFi TCP (3 connections)
              | 9637: control + data
              | 9638: H.264 video
              | 9639: touch input
              v
+- Car (BYD DiLink 3.0) -------------------------------------------+
|                                                                   |
|  CarConnectionService                                             |
|  Parallel connection model: WiFi + USB tracks run simultaneously  |
|                                                                   |
|  Track A (WiFi):                                                  |
|  +-- Gateway IP + mDNS discovery                                 |
|  +-- Control connect (9637) → handshake (viewport+DPI+FPS)       |
|  +-- Video (9638) + Input (9639) connect after handshake          |
|  +-- Receives H.264 video → VideoDecoder → TextureView            |
|  +-- Sends touch events via input connection                      |
|                                                                   |
|  Track B (USB):                                                   |
|  +-- Scan USB devices → USB ADB connect (logSink for diagnostics)|
|  +-- Launch phone app (am start)                                 |
|                                                                   |
|  Phone auto-updates car app via dadb when version mismatch       |
|  Car receives UPDATING_CAR message → shows status, stops reconnect|
|                                                                   |
|  UI: CarLaunchScreen → (app icons arrive) → CarShell + NavBar    |
|  Two modes: launch (connection-focused, no nav) and streaming    |
|  Nav bar (76dp): Notifications (badge+progress), Home, Back      |
|  40dp icons, 14sp text                                            |
+-------------------------------------------------------------------+
```

## Nima uchun avtomobildan USB ADB?

`app_process` uchinchi tomon ilovalarini joylashtira oladigan VirtualDisplay yaratish uchun shell UID (2000) sifatida ishlashi kerak. Avtomobil telefonning `adbd` xizmatiga USB xost rejimi orqali maxsus ADB protokoli (`protocol/` modulidagi `UsbAdbConnection`) yordamida ulanadi.

**Joriy yondashuv:** Avtomobil USB ADB xost sifatida ishlaydi. Telefonga faqat **USB Debugging** yoqilgan bo'lishi kerak (standart Developer Option). Hech qanday Wireless Debugging, juftlash kodlari, sozlash uchun WiFi bog'liqligi yo'q.

## Modullar javobgarligi

### protocol (Android Library)

Ikkala ilova uchun umumiy. Tarkibida UsbAdbConnection, AdbProtocol, VideoConfig va NioReader mavjud. Kotlin coroutines'dan tashqari nol bog'liqliklar.

| Component | File | Purpose |
|-----------|------|---------|
| Frame codec | `FrameCodec.kt` | Ikkilik kadr kodlash/dekodlash, qayta ishlatiladigan sarlavha buferi, NIO writeAll |
| Channels | `Channel.kt` | Kanal identifikatorlari (control, video, audio, data, input) |
| Message types | `MessageType.kt` | Bayt konstantalari, jumladan `VD_STACK_EMPTY`, `UPDATING_CAR` |
| Messages | `Messages.kt` | Serializatsiyalanadigan ma'lumotlar klasslari (handshake tarkibida `appVersionCode`, `vdServerJarPath`, `targetFps`) |
| Connection | `Connection.kt` | Ixtiyoriy heartbeat/watchdog bilan TCP ulanishi, qulfsiz yozish navbati, NioReader |
| VideoConfig | `VideoConfig.kt` | `TARGET_FPS`, `FRAME_INTERVAL_MS` — umumiy vaqt konstantalari |
| NioReader | `NioReader.kt` | Selector asosidagi bloklanmaydigan o'qish vositasi, sozlanadigan select timeout |
| Discovery | `Discovery.kt` | mDNS xizmatini ro'yxatdan o'tkazish/aniqlash, port konstantalari (9637/9638/9639) |
| UsbAdbConnection | `adb/UsbAdbConnection.java` | USB orqali ADB protokoli (CNXN, AUTH, OPEN, WRTE), logSink qayta chaqiruvi |
| AdbProtocol | `adb/AdbProtocol.java` | ADB xabar konstantalari va seriyalizatsiyasi |

### app-client (Telefon ilovasi)

VD serverini joylashtirishni, avtomobilni avto-yangilashni, 3-ulanish releysini va FileLog-ni boshqaradi.

| Component | File | Purpose |
|-----------|------|---------|
| ConnectionService | `service/ConnectionService.kt` | 3-port qabul qilish (9637/9638/9639), VD JAR joylashtirish, avtomobil avto-yangilash, aqlli tarmoq qayta chaqiruvi |
| VirtualDisplayClient | `display/VirtualDisplayClient.kt` | localhost:19637 da NIO qabul qilish, video releyi (videoConnection), teginishlarni yo'naltirish, bo'sh stek (controlConnection) |
| NotificationService | `service/NotificationService.kt` | Progress bilan telefon xabarnomalarini olish va yo'naltirish |
| InputInjectionService | `service/InputInjectionService.kt` | Teginish inyeksiyasining zaxira usuli (fizik displey) |
| FileLog | `FileLog.kt` | `/sdcard/DiLinkAuto/client.log` ga faylga jurnal yozish, rotatsiya, logcat filtrlashini aylanib o'tish |
| MainActivity | `MainActivity.kt` | UI — ishga tushirish/to'xtatish, ruxsat holati, Install on Car tugmasi |

### app-server (Avtomobil ilovasi)

WiFi (3 ulanish) va USB treklari bilan parallel ulanish modeli.

| Component | File | Purpose |
|-----------|------|---------|
| CarConnectionService | `service/CarConnectionService.kt` | Parallel holat mashinasi, 3-ulanish WiFi + USB treki, UPDATING_CAR ishlovi |
| VideoDecoder | `decoder/VideoDecoder.kt` | H.264 dekodlash, 30 kadr navbati, ekrandan tashqari yuzada erta ishga tushirish, logSink qayta chaqiruvi |
| CarLaunchScreen | `ui/screen/CarLaunchScreen.kt` | To'liq ekranli ishga tushirish/ulanish ekrani (navigatsiyasiz), brendlash, yo'riqnomalar, qo'lda IP |
| MirrorScreen | `ui/screen/MirrorScreen.kt` | TextureView + teginishlarni yo'naltirish, yuza mavjud bo'lganda dekoderni qayta ishga tushirish |
| HomeContent | `ui/screen/HomeScreen.kt` | Ilovalar to'ri (64dp ikonkalar, 160dp katakchalar) yoki ulanish holati, oqim rejimida ko'rsatiladi |
| LauncherScreen | `ui/screen/LauncherScreen.kt` | SideNavBar, CarStatusBar, AppGrid bilan eski integratsiyalashgan ekran |
| NotificationScreen | `ui/screen/NotificationScreen.kt` | Progress barlar bilan xabarnomalar ro'yxati, tegib ishga tushirish |
| PersistentNavBar | `ui/nav/PersistentNavBar.kt` | 76dp navigatsiya paneli (40dp ikonkalar, 14sp matn), so'nggi ilovalar (tozalanadi), faqat oqim rejimida |
| RecentAppsState | `ui/nav/RecentAppsState.kt` | So'nggi ilovalarni kuzatadi, mavjud bo'lmaganlarni o'chiradi |
| MainActivity | `MainActivity.kt` | To'liq ekranli immersive, USB intent yo'naltirish, ikki rejimli ekran marshrutlash (launch vs streaming) |

### vd-server (Shell-Imtiyozli Jarayon)

Android kutubxona moduli (`com.android.library`), `bundleLibRuntimeToJarDebug` orqali, so'ngra `app-client/build.gradle.kts` ichidagi `buildVdServer` topshirig'i orqali D8 yordamida JAR ga yig'iladi. `:protocol` va `kotlinx-coroutines-core` ga bog'liq. Telefon tomonidan `/sdcard/DiLinkAuto/` ga joylashtiriladi.

| Component | File | Purpose |
|-----------|------|---------|
| VirtualDisplayServer | `VirtualDisplayServer.kt` | VD yaratadi, NIO yozish navbati + Selector o'qish vositasi, H.264 kodlovchi, sozlanadigan FPS, backpressure |
| FakeContext | `FakeContext.kt` | DisplayManager kirish uchun `com.android.shell` ni soxtalashtiradi |
| SurfaceScaler | `SurfaceScaler.kt` | EGL/GLES GPU kichraytirish konveyeri, bo'sh turgan paytda GL ishini o'tkazib yuboradi (kodlovchining repeat-previous-frame-after xususiyatiga tayanadi) |

## Ulanish oqimi

```
1. Phone and car on same network (or phone plugged into car USB)
2. Car app launches, starts parallel WiFi + USB tracks

   Track A (WiFi):
   a. Gateway IP discovery + mDNS lookup
   b. NIO connect to phone control port (9637)
   c. Handshake: car sends viewport + DPI + appVersionCode + targetFps
   d. Phone responds with device info + vdServerJarPath
   e. Phone checks appVersionCode -- if mismatch, sends UPDATING_CAR, auto-updates via dadb
   f. Car connects video (9638) + input (9639) in parallel after handshake
   g. Phone accepts both, session fully established

   Track B (USB):
   a. Scan USB devices for ADB interface
   b. USB ADB connect (CNXN -> AUTH -> connected), logSink routes to carLogSend
   c. Launch phone app via am start

3. Phone deploys vd-server.jar to /sdcard/DiLinkAuto/
4. Car starts: CLASSPATH=jar app_process / VirtualDisplayServer W H DPI PORT EW EH FPS
5. VD server creates VirtualDisplay (phone DPI) + GPU SurfaceScaler (periodic re-draw)
6. VD server reverse-connects TO phone on localhost:19637 (NIO non-blocking)
7. Phone accepts VD server connection via NIO ServerSocketChannel
8. Car starts VideoDecoder on offscreen surface immediately on first CONFIG frame
9. MirrorScreen shows, restarts decoder with real TextureView surface
10. Video streams: VD -> SurfaceScaler -> encoder -> NIO write queue -> localhost -> phone NioReader -> videoConnection write queue -> WiFi TCP -> car NioReader -> VideoDecoder -> TextureView
```

Holatlar: IDLE -> CONNECTING -> CONNECTED -> STREAMING

## Asosiy dizayn qarorlari

| Decision | Rationale |
|----------|-----------|
| **3 ajratilgan TCP ulanishi** | Control/video/input alohida soketlarda. Kanallararo backpressure yo'q qiladi (ilovalar ro'yxati videoni to'xtata olmaydi). |
| **Parallel WiFi + USB treklari** | Ikkalasi bir vaqtda ishlaydi; checkAndAdvance() har qanday shart o'zgarganda holatni baholaydi. |
| **Telefon VD JAR joylashtiradi** | Avtomobil tomonidan push kerak emas. Telefon deployAssets() orqali vd-server.jar ni sdcard ga chiqaradi. |
| **Teskari VD ulanishi** | VD serveri telefonga ulanadi (telefon VD ga emas), xavfsizlik devori/NAT ishlovini soddalashtiradi. |
| **NIO bloklanmaydigan hamma joyda** | Barcha soketlar bloklanmaydigan: WiFi ulanishlari, VD serveri localhost, Selector asosidagi o'qish. Konveyerda bloklanadigan I/O yo'q. |
| **Sozlanadigan FPS** | Avtomobil handshakeda `targetFps` yuboradi, VD serveri undan foydalanadi. Barcha konveyer taym-autlari `FRAME_INTERVAL_MS = 1000/fps` dan hisoblanadi. |
| **Kodlovchi repeat-previous-frame-after** | SurfaceScaler bo'sh kadrlarda GL ishini o'tkazib yuboradi. Kodlovchi statik tarkibda 500ms gacha oxirgi kadrni takrorlashga sozlangan, GPU qo'shimcha xarajatlarisiz ochlikni oldini oladi. |
| **Dekoderni erta ishga tushirish** | Dekoder birinchi CONFIG kelganda ekrandan tashqari SurfaceTexture'da ishga tushadi, MirrorScreen TextureView yaratilishidan oldin. UI kompozitsiyasi paytida kalit kadrlar yo'qolishini oldini oladi. |
| **Aqlli tarmoq qayta chaqiruvi** | `onLost` bog'liq bo'lmagan tarmoq uzilishlarini e'tiborsiz qoldiradi (mobil ma'lumotlar). Faqat faol ulanish tarmog'i yo'qolganda sessiyani to'xtatadi. |
| **Xabarlashgan avtomobil avto-yangilash** | Telefon o'rnatishdan oldin UPDATING_CAR yuboradi. Avtomobil holatni ko'rsatadi, ko'r-ko'rona qayta ulanmaydi. |
| **Avtomobil APK telefon APK ichiga o'rnatilgan** | Qurish tizimi app-server.apk ni app-client ichiga o'raydi, Install on Car funksiyasini qo'shadi. |
| **GPU SurfaceScaler** | VD telefonning 480dpi da renderlanadi (compat masshtablashsiz). GPU avtomobil ko'rish oynasiga masshtablaydi. |
| **FakeContext** | ActivityThread + getSystemContext() haqiqiy tizim Context uchun. UserManager NPE ni mDisplayIdToMirror reflection orqali aylanib o'tadi. |
| **Trusted VD flags** | `OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED` activity migratsiyasini oldini oladi. |
| **Juft ko'rish oynasi eni** | Navigatsiya paneli eni H.264 mos keluvchi juft o'lchamlarni kafolatlash uchun sozlanadi. |
| **Heartbeat faqat controlda** | Video va kiritish ulanishlarida heartbeat qo'shimcha xarajati yo'q. Control ulanish watchdog'i o'lik tugunlarni aniqlaydi. |
| **FileLog** | HyperOS logcat filtrlashini aylanib o'tadi. Faylli jurnallash, `/sdcard/DiLinkAuto/` da rotatsiya bilan. |
| **logSink qayta chaqiruvlari** | VideoDecoder va UsbAdbConnection jurnallarni protokol orqali telefon FileLog'iga yo'naltiradi. |
| **ADB oldindan heshlangan autentifikatsiya** | AUTH_SIGNATURE `NONEwithRSA` + SHA-1 DigestInfo prefiksini ishlatadi (oldindan heshlangan). AOSP `RSA_sign(NID_sha1)` mos keladi. "Always allow" to'g'ri saqlanadi. |
| **SurfaceControl orqali displey quvvatini boshqarish** | `DisplayControl` `services.jar` dan `ClassLoaderFactory` orqali yuklanadi (Android 14+). `cmd display power-off/on` zaxirasi. Telefon VD uzilganda displeyni tiklaydi. |
| **Decoder catchup** | To'rt bosqichli tezlashtirish zonasi: normal (0-6 kadr), gentle 1.5x (7-12), medium 2x (13-20), aggressive 3x (21+). Kalit kadrlar hech qachon o'tkazilmaydi. |
| **App launch dedup** | `am start` `--activity-clear-task`siz. Mavjud ilovalar qayta ishga tushish o'rniga davom etadi. |
| **VD server backpressure** | Yozish navbati 6 kadrdan oshganda kodlovchida kalit bo'lmagan kadrlarni tashlaydi. Cheksiz xotira o'sishini oldini oladi. |
| **User disconnect** | IDLE holatida qoladi, avto-qayta ulanishsiz. SharedPreferences'da saqlanadi. |

## Texnologiya Steki

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9.22 (all modules) |
| Build | Gradle 8.7, AGP 8.2.2 |
| UI | Jetpack Compose + Material 3 |
| Video | MediaCodec H.264 (encoder: VD server 8Mbps CBR Main, decoder: car) |
| GPU | EGL14 + GLES20 + SurfaceTexture (SurfaceScaler with periodic re-draw) |
| Networking | NIO ServerSocketChannel / SocketChannel / Selector, Android NSD (mDNS) |
| USB ADB | Custom protocol in protocol/ module (shared), logSink for diagnostics |
| WiFi ADB | dadb 1.2.10 (car auto-update) |
| Async | Kotlin Coroutines + Flow |
| Min API | 29 (Android 10) |
| App Version | versionCode read at runtime via PackageManager (shared in gradle.properties) |
| Protocol Version | PROTOCOL_VERSION = 1 |
