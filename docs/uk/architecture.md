# Архітектура

## Огляд

DiLink-Auto — це Gradle-проект із чотирьох модулів:

```
DiLink-Auto/
├── protocol/        Android-бібліотека — спільна для обох додатків (модуль Gradle)
├── app-client/      Android-додаток — працює на телефоні (модуль Gradle)
├── app-server/      Android-додаток — працює на автомобілі (модуль Gradle)
├── vd-server/       Android-бібліотека — сервер VirtualDisplay (модуль Gradle)
```

## Архітектура Virtual Display

**Телефон** розгортає та запускає VD-сервер локально. VD-сервер підключається назад до додатку телефону (зворотне з'єднання на localhost:19637, повністю NIO неблокуюче). Додатки рендеряться на VirtualDisplay з рідною щільністю телефону (480dpi), масштабуються GPU до роздільної здатності вікна автомобіля для кодування H.264. Фізичний екран телефону повністю незалежний.

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

## Чому USB ADB з автомобіля?

`app_process` повинен працювати як shell UID (2000) для створення VirtualDisplay, які можуть розміщувати сторонні додатки. Автомобіль підключається до `adbd` телефону через USB host mode, використовуючи власну реалізацію протоколу ADB (`UsbAdbConnection` у модулі protocol/).

**Поточний підхід:** Автомобіль виступає як USB ADB хост. Телефону потрібне лише **USB Debugging** (стандартна опція розробника). Жодного Wireless Debugging, кодів сполучення, залежності від WiFi для налаштування.

## Відповідальність модулів

### protocol (Android Library)

Спільна для обох додатків. Містить UsbAdbConnection, AdbProtocol, VideoConfig та NioReader. Нуль залежностей, окрім Kotlin coroutines.

| Component | File | Purpose |
|-----------|------|---------|
| Frame codec | `FrameCodec.kt` | Двійкове кодування/декодування кадрів, багаторазовий буфер заголовка, NIO writeAll |
| Channels | `Channel.kt` | Ідентифікатори каналів (control, video, audio, data, input) |
| Message types | `MessageType.kt` | Байтові константи, включаючи `VD_STACK_EMPTY`, `UPDATING_CAR` |
| Messages | `Messages.kt` | Серіалізовані класи даних (handshake включає `appVersionCode`, `vdServerJarPath`, `targetFps`) |
| Connection | `Connection.kt` | TCP-з'єднання з опціональним heartbeat/watchdog, неблокуюча черга запису, NioReader |
| VideoConfig | `VideoConfig.kt` | `TARGET_FPS`, `FRAME_INTERVAL_MS` — спільні часові константи |
| NioReader | `NioReader.kt` | Неблокуючий читач на основі Selector, настроюваний select timeout |
| Discovery | `Discovery.kt` | Реєстрація/виявлення сервісів mDNS, константи портів (9637/9638/9639) |
| UsbAdbConnection | `adb/UsbAdbConnection.java` | Протокол ADB через USB (CNXN, AUTH, OPEN, WRTE), зворотний виклик logSink |
| AdbProtocol | `adb/AdbProtocol.java` | Константи та серіалізація повідомлень ADB |

### app-client (Додаток телефону)

Керує розгортанням VD-сервера, автооновленням автомобіля, ретрансляцією 3 з'єднань та FileLog.

| Component | File | Purpose |
|-----------|------|---------|
| ConnectionService | `service/ConnectionService.kt` | Прийом на 3 портах (9637/9638/9639), розгортання VD JAR, автооновлення автомобіля, розумний мережевий зворотний виклик |
| VirtualDisplayClient | `display/VirtualDisplayClient.kt` | NIO прийом на localhost:19637, ретрансляція відео (videoConnection), пересилання дотиків, порожній стек (controlConnection) |
| NotificationService | `service/NotificationService.kt` | Захоплення та пересилання сповіщень телефону з прогресом |
| InputInjectionService | `service/InputInjectionService.kt` | Резервний метод ін'єкції дотиків (фізичний дисплей) |
| FileLog | `FileLog.kt` | Файлове логування до `/sdcard/DiLinkAuto/client.log`, ротація, обхід фільтрації logcat |
| MainActivity | `MainActivity.kt` | UI — старт/стоп, статус дозволів, кнопка Install on Car |

### app-server (Додаток автомобіля)

Паралельна модель з'єднання з треками WiFi (3 з'єднання) та USB.

| Component | File | Purpose |
|-----------|------|---------|
| AppIconCache | `AppIconCache.kt` | Кеш іконок на стороні автомобіля — декодує вихідні PNG 192x192 один раз, `prepareAll()` змінює розмір усіх іконок у фоновому потоці, `getPrepared()` це O(1) пошук у ConcurrentHashMap без I/O під час прокручування |
| CarConnectionService | `service/CarConnectionService.kt` | Паралельний скінченний автомат, 3 з'єднання WiFi + USB трек, обробка UPDATING_CAR |
| VideoDecoder | `decoder/VideoDecoder.kt` | Декодування H.264, черга з 30 кадрів, ранній старт на позаекранній поверхні, зворотний виклик logSink |
| CarLaunchScreen | `ui/screen/CarLaunchScreen.kt` | Повноекранний екран запуску/з'єднання (без навігації), брендинг, інструкції, ручний IP |
| MirrorScreen | `ui/screen/MirrorScreen.kt` | TextureView + пересилання дотиків, перезапуск декодера при доступності поверхні |
| HomeContent | `ui/screen/HomeScreen.kt` | Сітка додатків (64dp іконки, 160dp комірки) або статус з'єднання, показується в режимі потокової передачі |
| LauncherScreen | `ui/screen/LauncherScreen.kt` | Застарілий інтегрований екран з SideNavBar, CarStatusBar, AppGrid |
| NotificationScreen | `ui/screen/NotificationScreen.kt` | Список сповіщень зі смугами прогресу, запуск по дотику |
| PersistentNavBar | `ui/nav/PersistentNavBar.kt` | 76dp панель навігації (40dp іконки, 14sp текст), нещодавні додатки (очищуються), лише в режимі потокової передачі |
| RecentAppsState | `ui/nav/RecentAppsState.kt` | Відстежує нещодавні додатки, видаляє недоступні |
| MainActivity | `MainActivity.kt` | Повноекранний immersive, пересилання USB intent, двоножимна маршрутизація екранів (launch проти streaming) |

### vd-server (Процес із привілеями Shell)

Модуль Android-бібліотеки (`com.android.library`), компілюється через `bundleLibRuntimeToJarDebug`, потім D8 у JAR за допомогою задачі `buildVdServer` у `app-client/build.gradle.kts`. Залежить від `:protocol` та `kotlinx-coroutines-core`. Розгортається телефоном у `/sdcard/DiLinkAuto/`.

| Component | File | Purpose |
|-----------|------|---------|
| VirtualDisplayServer | `VirtualDisplayServer.kt` | Створює VD, NIO черга запису + читач Selector, кодувальник H.264, настроюваний FPS, backpressure |
| FakeContext | `FakeContext.kt` | Підробляє `com.android.shell` для доступу до DisplayManager |
| SurfaceScaler | `SurfaceScaler.kt` | Конвеєр GPU масштабування EGL/GLES, пропускає роботу GL у бездіяльності (покладається на repeat-previous-frame-after кодувальника) |

## Потік з'єднання

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

Стани: IDLE -> CONNECTING -> CONNECTED -> STREAMING

## Ключові дизайнерські рішення

| Decision | Rationale |
|----------|-----------|
| **3 виділені TCP-з'єднання** | Control/video/input на окремих сокетах. Усуває міжканальний backpressure (список додатків не може зупинити відео). |
| **Паралельні треки WiFi + USB** | Обидва працюють одночасно; checkAndAdvance() оцінює стан при зміні будь-якої передумови. |
| **Телефон розгортає VD JAR** | Не потрібен push з боку автомобіля. Телефон видобуває vd-server.jar на sdcard через deployAssets(). |
| **Зворотне VD з'єднання** | VD-сервер підключається ДО телефону (не телефон до VD), спрощуючи обробку брандмауера/NAT. |
| **NIO неблокуюче всюди** | Усі сокети неблокуючі: WiFi-з'єднання, VD-сервер localhost, читання на основі Selector. Жодного блокуючого I/O в конвеєрі. |
| **Настроюваний FPS** | Автомобіль надсилає `targetFps` у handshake, VD-сервер використовує його. Усі тайм-аути конвеєра виводяться з `FRAME_INTERVAL_MS = 1000/fps`. |
| **Кодувальник repeat-previous-frame-after** | SurfaceScaler пропускає роботу GL на бездіяльних кадрах. Кодувальник налаштований повторювати останній кадр до 500ms на статичному вмісті, запобігаючи голодуванню без накладних витрат GPU. |
| **Ранній старт декодера** | Декодер запускається на позаекранному SurfaceTexture при надходженні першого CONFIG, до створення TextureView MirrorScreen. Запобігає втраті ключових кадрів під час компонування UI. |
| **Розумний мережевий зворотний виклик** | `onLost` ігнорує незв'язані втрати мережі (мобільні дані). Розриває сесію лише при втраті мережі активного з'єднання. |
| **Автооновлення автомобіля з повідомленням** | Телефон надсилає UPDATING_CAR перед встановленням. Автомобіль показує статус, не перепідключається наосліп. |
| **APK автомобіля вбудований в APK телефону** | Система збірки пакує app-server.apk всередині app-client, включаючи функцію Install on Car. |
| **GPU SurfaceScaler** | VD рендериться при 480dpi телефону (без масштабування сумісності). GPU масштабує до вікна автомобіля. |
| **FakeContext** | ActivityThread + getSystemContext() для справжнього системного Context. Обходить UserManager NPE через mDisplayIdToMirror reflection. |
| **Trusted VD flags** | `OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED` запобігають міграції activity. |
| **Парна ширина вікна** | Ширина панелі навігації підганяється для гарантії H.264-сумісних парних розмірів. |
| **Heartbeat лише на control** | Відео та вхідні з'єднання не мають накладних витрат heartbeat. Watchdog control-з'єднання виявляє мертві вузли. |
| **FileLog** | Обходить фільтрацію logcat у HyperOS. Файлове логування з ротацією на `/sdcard/DiLinkAuto/`. |
| **Кеш іконок застосунків** | Кеш на стороні автомобіля зберігає вихідні PNG (192x192) на диск. `prepareAll()` декодує та змінює розмір усіх іконок до розміру сітки у фоновому потоці після отримання APP_LIST. `getPrepared()` це пошук ConcurrentHashMap з нульовою вартістю — без корутин, без I/O, без декодування під час прокручування. |
| **Відхилення/очищення сповіщень** | Кнопка відхилення для кожного елемента та заголовок "Очистити все" на екрані сповіщень автомобіля. Автомобіль надсилає NOTIFICATION_CLEAR / NOTIFICATION_CLEAR_ALL через канал даних на телефон, який очищає відповідні сповіщення Android. |
| **Зворотні виклики logSink** | VideoDecoder та UsbAdbConnection маршрутизують логи через протокол до FileLog телефону. |
| **ADB попередньо хешована автентифікація** | AUTH_SIGNATURE використовує `NONEwithRSA` + префікс SHA-1 DigestInfo (попередньо хешований). Відповідає `RSA_sign(NID_sha1)` в AOSP. "Always allow" зберігається коректно. |
| **Керування живленням дисплея через SurfaceControl** | `DisplayControl` завантажений з `services.jar` через `ClassLoaderFactory` (Android 14+). Резервний варіант: `cmd display power-off/on`. Телефон відновлює дисплей при відключенні VD. |
| **Decoder catchup** | Чотири зони прискорення: normal (0-6 кадрів), gentle 1.5x (7-12), medium 2x (13-20), aggressive 3x (21+). Ключові кадри ніколи не пропускаються. |
| **App launch dedup** | `am start` без `--activity-clear-task`. Існуючі додатки відновлюються замість перезапуску. |
| **VD server backpressure** | Відкидає неключові кадри в кодувальнику, коли глибина черги запису перевищує 6 кадрів. Запобігає необмеженому зростанню пам'яті. |
| **User disconnect** | Залишається IDLE, без автоперепідключення. Зберігається в SharedPreferences. |

## Технологічний стек

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
| App Version | versionName compared via semver (shared in gradle.properties); versionCode still sent for backward compatibility |
| Protocol Version | PROTOCOL_VERSION = 1 |
