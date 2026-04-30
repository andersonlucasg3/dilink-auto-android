# Архитектура

## Шолу

DiLink-Auto — төрт модульді Gradle жобасы:

```
DiLink-Auto/
├── protocol/        Android кітапханасы — екі қолданбаға ортақ (Gradle модулі)
├── app-client/      Android қолданбасы — телефонда жұмыс істейді (Gradle модулі)
├── app-server/      Android қолданбасы — көлікте жұмыс істейді (Gradle модулі)
├── vd-server/       Android кітапханасы — VirtualDisplay сервері (Gradle модулі)
```

## Virtual Display Архитектурасы

**Телефон** VD серверін жергілікті түрде орналастырады және іске қосады. VD сервері телефон қолданбасына кері қосылады (localhost:19637 бойынша кері қосылым, толық NIO блокталмайтын). Қолданбалар телефонның табиғи DPI (480dpi) бойынша VirtualDisplay-де рендерленеді, H.264 кодтау үшін көліктің көрініс терезесінің рұқсатына GPU арқылы масштабталады. Телефонның физикалық экраны толығымен тәуелсіз.

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

## Неліктен көліктен USB ADB?

`app_process` үшінші тарап қолданбаларын орналастыра алатын VirtualDisplay жасау үшін shell UID (2000) ретінде жұмыс істеуі керек. Көлік телефонның `adbd` қызметіне USB хост режимі арқылы теңшелген ADB протоколын (`protocol/` модуліндегі `UsbAdbConnection`) пайдаланып қосылады.

**Ағымдағы тәсіл:** Көлік USB ADB хост ретінде әрекет етеді. Телефонға тек **USB Debugging** қосулы болуы керек (стандартты Developer Option). Сымсыз реттеу, жұптау кодтары, орнату үшін WiFi тәуелділігі жоқ.

## Модуль жауапкершіліктері

### protocol (Android Library)

Екі қолданбаға ортақ. Құрамында UsbAdbConnection, AdbProtocol, VideoConfig және NioReader бар. Kotlin coroutines-тен басқа тәуелділіктер жоқ.

| Component | File | Purpose |
|-----------|------|---------|
| Frame codec | `FrameCodec.kt` | Екілік кадр кодтау/декодтау, қайта пайдаланылатын тақырып буфері, NIO writeAll |
| Channels | `Channel.kt` | Арна идентификаторлары (control, video, audio, data, input) |
| Message types | `MessageType.kt` | Байт тұрақтылары, соның ішінде `VD_STACK_EMPTY`, `UPDATING_CAR` |
| Messages | `Messages.kt` | Сериализацияланатын деректер кластары (handshake құрамында `appVersionCode`, `vdServerJarPath`, `targetFps`) |
| Connection | `Connection.kt` | Қосымша heartbeat/watchdog бар TCP қосылымы, құлыпсыз жазу кезегі, NioReader |
| VideoConfig | `VideoConfig.kt` | `TARGET_FPS`, `FRAME_INTERVAL_MS` — ортақ уақыт тұрақтылары |
| NioReader | `NioReader.kt` | Selector негізіндегі блокталмайтын оқу құралы, бапталатын select timeout |
| Discovery | `Discovery.kt` | mDNS қызметін тіркеу/табу, порт тұрақтылары (9637/9638/9639) |
| UsbAdbConnection | `adb/UsbAdbConnection.java` | USB арқылы ADB протоколы (CNXN, AUTH, OPEN, WRTE), logSink кері шақыруы |
| AdbProtocol | `adb/AdbProtocol.java` | ADB хабарлама тұрақтылары және сериализациясы |

### app-client (Телефон қолданбасы)

VD серверін орналастыруды, көлікті авто-жаңартуды, 3-қосылым релесін және FileLog басқарады.

| Component | File | Purpose |
|-----------|------|---------|
| ConnectionService | `service/ConnectionService.kt` | 3-порт қабылдау (9637/9638/9639), VD JAR орналастыру, көлік авто-жаңарту, ақылды желі кері шақыруы |
| VirtualDisplayClient | `display/VirtualDisplayClient.kt` | localhost:19637 бойынша NIO қабылдау, бейне релесі (videoConnection), жанасуды жіберу, бос стек (controlConnection) |
| NotificationService | `service/NotificationService.kt` | Прогрессі бар телефон хабарландыруларын алу және жіберу |
| InputInjectionService | `service/InputInjectionService.kt` | Жанасу енгізуінің резервтік әдісі (физикалық дисплей) |
| FileLog | `FileLog.kt` | `/sdcard/DiLinkAuto/client.log` файлына журнал жүргізу, ротация, logcat сүзгісін айналып өту |
| MainActivity | `MainActivity.kt` | UI — іске қосу/тоқтату, рұқсат күйі, Install on Car түймесі |

### app-server (Көлік қолданбасы)

WiFi (3 қосылым) және USB тректерімен параллель қосылым моделі.

| Component | File | Purpose |
|-----------|------|---------|
| CarConnectionService | `service/CarConnectionService.kt` | Параллель күй машинасы, 3-қосылым WiFi + USB тректері, UPDATING_CAR өңдеу |
| VideoDecoder | `decoder/VideoDecoder.kt` | H.264 декодтау, 30 кадр кезегі, экраннан тыс бетте ерте іске қосу, logSink кері шақыруы |
| CarLaunchScreen | `ui/screen/CarLaunchScreen.kt` | Толық экранды іске қосу/қосылу экраны (навигациясыз), брендинг, нұсқаулықтар, қолмен IP |
| MirrorScreen | `ui/screen/MirrorScreen.kt` | TextureView + жанасуды жіберу, бет қолжетімді болғанда декодерді қайта іске қосу |
| HomeContent | `ui/screen/HomeScreen.kt` | Қолданбалар торы (64dp белгішелер, 160dp ұяшықтар) немесе қосылу күйі, ағынды режимде көрсетіледі |
| LauncherScreen | `ui/screen/LauncherScreen.kt` | SideNavBar, CarStatusBar, AppGrid бар ескі интеграцияланған экран |
| NotificationScreen | `ui/screen/NotificationScreen.kt` | Прогресс жолақтары бар хабарландырулар тізімі, түрту арқылы іске қосу |
| PersistentNavBar | `ui/nav/PersistentNavBar.kt` | 76dp навигация панелі (40dp белгішелер, 14sp мәтін), соңғы қолданбалар (тазаланады), тек ағынды режимде |
| RecentAppsState | `ui/nav/RecentAppsState.kt` | Соңғы қолданбаларды бақылайды, қолжетімсіздерін жояды |
| MainActivity | `MainActivity.kt` | Толық экранды immersive, USB intent жіберу, екі режимді экран бағдары (launch vs streaming) |

### vd-server (Shell-артықшылықты процесс)

Android кітапхана модулі (`com.android.library`), `bundleLibRuntimeToJarDebug` арқылы, содан кейін `app-client/build.gradle.kts` ішіндегі `buildVdServer` тапсырмасы арқылы D8 көмегімен JAR-ға жиналады. `:protocol` және `kotlinx-coroutines-core` тәуелді. Телефон `/sdcard/DiLinkAuto/` ішіне орналастырады.

| Component | File | Purpose |
|-----------|------|---------|
| VirtualDisplayServer | `VirtualDisplayServer.kt` | VD жасайды, NIO жазу кезегі + Selector оқу құралы, H.264 кодтаушы, бапталатын FPS, backpressure |
| FakeContext | `FakeContext.kt` | DisplayManager қатынауы үшін `com.android.shell` жалған көрсетеді |
| SurfaceScaler | `SurfaceScaler.kt` | EGL/GLES GPU кішірейту конвейері, бос тұрғанда GL жұмысын өткізіп жібереді (кодтаушының repeat-previous-frame-after қызметіне сүйенеді) |

## Қосылу ағыны

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

Күйлер: IDLE -> CONNECTING -> CONNECTED -> STREAMING

## Негізгі дизайн шешімдері

| Decision | Rationale |
|----------|-----------|
| **3 арнайы TCP қосылымы** | Control/video/input бөлек сокеттерде. Арна аралық backpressure жояды (қолданбалар тізімі бейнені тоқтата алмайды). |
| **Параллель WiFi + USB тректері** | Екеуі бір уақытта жұмыс істейді; checkAndAdvance() кез келген алғышарт өзгергенде күйді бағалайды. |
| **Телефон VD JAR орналастырады** | Көлік жағынан push қажет емес. Телефон deployAssets() арқылы vd-server.jar файлын sdcard-қа шығарады. |
| **Кері VD қосылымы** | VD сервері телефонҒА қосылады (телефон VD-ге емес), брандмауэр/NAT өңдеуді жеңілдетеді. |
| **NIO блокталмайтын барлық жерде** | Барлық сокеттер блокталмайтын: WiFi қосылымдары, VD сервері localhost, Selector негізіндегі оқу. Конвейерде блокталатын I/O жоқ. |
| **Бапталатын FPS** | Көлік handshake-те `targetFps` жібереді, VD сервері оны пайдаланады. Барлық конвейер тайм-ауттары `FRAME_INTERVAL_MS = 1000/fps` негізінде есептеледі. |
| **Кодтаушы repeat-previous-frame-after** | SurfaceScaler бос кадрларда GL жұмысын өткізіп жібереді. Кодтаушы статикалық мазмұн үшін соңғы кадрды 500ms дейін қайталауға бапталған, GPU үстеме шығынынсыз аштықты болдырмайды. |
| **Декодерді ерте іске қосу** | Декодер бірінші CONFIG келгенде экраннан тыс SurfaceTexture-де іске қосылады, MirrorScreen TextureView жасалғанға дейін. UI композициясы кезінде кілттік кадрлардың жоғалуын болдырмайды. |
| **Ақылды желі кері шақыруы** | `onLost` байланыссыз желі үзілістерін елемейді (мобильді деректер). Тек белсенді қосылым желісі үзілгенде сессияны тоқтатады. |
| **Хабарламамен көлік авто-жаңарту** | Телефон орнату алдында UPDATING_CAR жібереді. Көлік күйді көрсетеді, соқыр қайта қосылмайды. |
| **Көлік APK телефон APK ішіне ендірілген** | Құрастыру жүйесі app-server.apk файлын app-client ішіне бумалайды, Install on Car мүмкіндігін қосады. |
| **GPU SurfaceScaler** | VD телефонның 480dpi бойынша рендерленеді (compat масштабтауынсыз). GPU көлік көрініс терезесіне масштабтайды. |
| **FakeContext** | ActivityThread + getSystemContext() нақты жүйелік Context үшін. UserManager NPE-ін mDisplayIdToMirror reflection арқылы айналып өтеді. |
| **Trusted VD flags** | `OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED` activity көшірілуін болдырмайды. |
| **Жұп көрініс терезесі ені** | Навигация панелінің ені H.264 үйлесімді жұп өлшемдерге кепілдік беру үшін реттеледі. |
| **Heartbeat тек control-да** | Бейне және енгізу қосылымдарында heartbeat үстеме шығыны жоқ. Control қосылым watchdog-ы өлі түйіндерді анықтайды. |
| **FileLog** | HyperOS logcat сүзгісін айналып өтеді. Файлдық журнал жүргізу, `/sdcard/DiLinkAuto/` бойынша ротациямен. |
| **logSink кері шақырулары** | VideoDecoder және UsbAdbConnection журналдарды протокол арқылы телефонның FileLog-ына бағыттайды. |
| **ADB алдын ала хэштелген аутентификация** | AUTH_SIGNATURE `NONEwithRSA` + SHA-1 DigestInfo префиксін пайдаланады (алдын ала хэштелген). AOSP `RSA_sign(NID_sha1)` сәйкес келеді. "Always allow" дұрыс сақталады. |
| **SurfaceControl арқылы дисплей қуатын басқару** | `DisplayControl` `services.jar` ішінен `ClassLoaderFactory` арқылы жүктеледі (Android 14+). `cmd display power-off/on` резерві. Телефон VD ажыратылғанда дисплейді қалпына келтіреді. |
| **Decoder catchup** | Төрт сатылы жылдамдату аймағы: normal (0-6 кадр), gentle 1.5x (7-12), medium 2x (13-20), aggressive 3x (21+). Кілттік кадрлар ешқашан өткізілмейді. |
| **App launch dedup** | `am start` `--activity-clear-task` жоқ. Бар қолданбалар қайта іске қосылудың орнына жалғасады. |
| **VD server backpressure** | Жазу кезегі 6 кадрдан асқанда кодтаушыда кілттік емес кадрларды тастайды. Шексіз жад өсуін болдырмайды. |
| **User disconnect** | IDLE күйінде қалады, авто-қайта қосылусыз. SharedPreferences-те сақталады. |

## Технология стегі

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
