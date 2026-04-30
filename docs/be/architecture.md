# Архітэктура

## Агляд

DiLink-Auto — гэта Gradle-праект з чатырох модуляў:

```
DiLink-Auto/
├── protocol/        Android-бібліятэка — агульная для абодвух прыкладанняў (модуль Gradle)
├── app-client/      Android-прыкладанне — працуе на тэлефоне (модуль Gradle)
├── app-server/      Android-прыкладанне — працуе на аўтамабілі (модуль Gradle)
├── vd-server/       Android-бібліятэка — сервер VirtualDisplay (модуль Gradle)
```

## Архітэктура Virtual Display

**Тэлефон** разгортвае і запускае VD-сервер лакальна. VD-сервер падключаецца назад да прыкладання тэлефона (зваротнае злучэнне на localhost:19637, цалкам NIO неблакавальнае). Прыкладанні рэндэрынгу на VirtualDisplay з родным DPI тэлефона (480dpi), маштабуюцца GPU да дазволу вакна аўтамабіля для кадавання H.264. Фізічны экран тэлефона цалкам незалежны.

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

## Чаму USB ADB з аўтамабіля?

`app_process` павінен працаваць як shell UID (2000) для стварэння VirtualDisplay, якія могуць размяшчаць іншыя прыкладанні. Аўтамабіль падключаецца да `adbd` тэлефона праз USB host mode, выкарыстоўваючы ўласную рэалізацыю пратаколу ADB (`UsbAdbConnection` у модулі protocol/).

**Бягучы падыход:** Аўтамабіль выступае ў якасці USB ADB хоста. Тэлефону патрэбна толькі **USB Debugging** (стандартная опцыя распрацоўшчыка). Ніякага Wireless Debugging, кодаў сувязі, залежнасці ад WiFi для наладкі.

## Адказнасць модуляў

### protocol (Android Library)

Агульны для абодвух прыкладанняў. Змяшчае UsbAdbConnection, AdbProtocol, VideoConfig і NioReader. Нулявыя залежнасці, акрамя Kotlin coroutines.

| Component | File | Purpose |
|-----------|------|---------|
| Frame codec | `FrameCodec.kt` | Двайковае кадаванне/дэкадаванне кадраў, шматразовы буфер загалоўка, NIO writeAll |
| Channels | `Channel.kt` | Ідэнтыфікатары каналаў (control, video, audio, data, input) |
| Message types | `MessageType.kt` | Байтавыя канстанты, уключаючы `VD_STACK_EMPTY`, `UPDATING_CAR` |
| Messages | `Messages.kt` | Серыялізаваныя класы даных (handshake уключае `appVersionCode`, `vdServerJarPath`, `targetFps`) |
| Connection | `Connection.kt` | TCP-злучэнне з апцыянальным heartbeat/watchdog, безблакавальная чарга запісу, NioReader |
| VideoConfig | `VideoConfig.kt` | `TARGET_FPS`, `FRAME_INTERVAL_MS` — агульныя канстанты часу |
| NioReader | `NioReader.kt` | Неблакавальны чытальнік на аснове Selector, наладжвальны select timeout |
| Discovery | `Discovery.kt` | Рэгістрацыя/выяўленне сэрвісаў mDNS, канстанты партоў (9637/9638/9639) |
| UsbAdbConnection | `adb/UsbAdbConnection.java` | Пратакол ADB праз USB (CNXN, AUTH, OPEN, WRTE), зваротны выклік logSink |
| AdbProtocol | `adb/AdbProtocol.java` | Канстанты і серыялізацыя паведамленняў ADB |

### app-client (Прыкладанне тэлефона)

Кіруе разгортваннем VD-сервера, аўтаабнаўленнем аўтамабіля, рэтрансляцыяй 3 злучэнняў і FileLog.

| Component | File | Purpose |
|-----------|------|---------|
| ConnectionService | `service/ConnectionService.kt` | Прыём на 3 партах (9637/9638/9639), разгортванне VD JAR, аўтаабнаўленне аўтамабіля, разумны сеткавы зваротны выклік |
| VirtualDisplayClient | `display/VirtualDisplayClient.kt` | NIO прыём на localhost:19637, рэтрансляцыя відэа (videoConnection), перасылка дотыкаў, пусты стэк (controlConnection) |
| NotificationService | `service/NotificationService.kt` | Захоп і перасылка апавяшчэнняў тэлефона з прагрэсам |
| InputInjectionService | `service/InputInjectionService.kt` | Рэзервовы метад ін'екцыі дотыкаў (фізічны дысплей) |
| FileLog | `FileLog.kt` | Файлавае лагаванне ў `/sdcard/DiLinkAuto/client.log`, ратацыя, абыходзіць фільтрацыю logcat |
| MainActivity | `MainActivity.kt` | UI — старт/стоп, статус дазволаў, кнопка Install on Car |

### app-server (Прыкладанне аўтамабіля)

Паралельная мадэль злучэння з трэкамі WiFi (3 злучэнні) і USB.

| Component | File | Purpose |
|-----------|------|---------|
| CarConnectionService | `service/CarConnectionService.kt` | Паралельны станавы аўтамат, 3 злучэнні WiFi + USB трэк, апрацоўка UPDATING_CAR |
| VideoDecoder | `decoder/VideoDecoder.kt` | Дэкадаванне H.264, чарга з 30 кадраў, ранні старт на пазаэкраннай паверхні, зваротны выклік logSink |
| CarLaunchScreen | `ui/screen/CarLaunchScreen.kt` | Поўнаэкранны экран запуску/злучэння (без навігацыі), брэндынг, інструкцыі, ручны IP |
| MirrorScreen | `ui/screen/MirrorScreen.kt` | TextureView + перасылка дотыкаў, перазапуск дэкодэра пры даступнасці паверхні |
| HomeContent | `ui/screen/HomeScreen.kt` | Сетка прыкладанняў (64dp іконкі, 160dp ячэйкі) або статус злучэння, паказваецца ў рэжыме струменевай перадачы |
| LauncherScreen | `ui/screen/LauncherScreen.kt` | Састарэлы інтэграваны экран з SideNavBar, CarStatusBar, AppGrid |
| NotificationScreen | `ui/screen/NotificationScreen.kt` | Спіс апавяшчэнняў з паласамі прагрэсу, запуск па націску |
| PersistentNavBar | `ui/nav/PersistentNavBar.kt` | 76dp панэль навігацыі (40dp іконкі, 14sp тэкст), нядаўнія прыкладанні (ачышчаюцца), толькі ў рэжыме струменевай перадачы |
| RecentAppsState | `ui/nav/RecentAppsState.kt` | Адсочвае нядаўнія прыкладанні, выдаляе недаступныя |
| MainActivity | `MainActivity.kt` | Поўнаэкранны immersive, перасылка USB intent, двухрэжымная маршрутызацыя экранаў (launch супраць streaming) |

### vd-server (Працэс з прывілеямі Shell)

Модуль Android-бібліятэкі (`com.android.library`), кампілюецца праз `bundleLibRuntimeToJarDebug`, затым D8 у JAR з дапамогай задачы `buildVdServer` у `app-client/build.gradle.kts`. Залежыць ад `:protocol` і `kotlinx-coroutines-core`. Разгортваецца тэлефонам у `/sdcard/DiLinkAuto/`.

| Component | File | Purpose |
|-----------|------|---------|
| VirtualDisplayServer | `VirtualDisplayServer.kt` | Стварае VD, NIO чарга запісу + чытальнік Selector, кадавальнік H.264, наладжвальны FPS, backpressure |
| FakeContext | `FakeContext.kt` | Падроблівае `com.android.shell` для доступу да DisplayManager |
| SurfaceScaler | `SurfaceScaler.kt` | Канвеер GPU маштабавання EGL/GLES, прапускае працу GL у бяздзейнасці (абапіраецца на repeat-previous-frame-after кадавальніка) |

## Паток злучэння

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

Станы: IDLE -> CONNECTING -> CONNECTED -> STREAMING

## Ключавыя дызайнерскія рашэнні

| Decision | Rationale |
|----------|-----------|
| **3 спецыялізаваныя TCP-злучэнні** | Control/video/input на асобных сокетах. Выключае міжканальны backpressure (спіс прыкладанняў не можа спыніць відэа). |
| **Паралельныя трэкі WiFi + USB** | Абодва працуюць адначасова; checkAndAdvance() ацэньвае стан пры змене любога прадумовы. |
| **Тэлефон разгортвае VD JAR** | Не патрэбен push з боку аўтамабіля. Тэлефон здабывае vd-server.jar на sdcard праз deployAssets(). |
| **Зваротнае VD злучэнне** | VD-сервер падключаецца ДА тэлефона (не тэлефон да VD), спрашчаючы апрацоўку брандмаўэра/NAT. |
| **NIO неблакавальнае ўсюды** | Усе сокеты неблакавальныя: WiFi-злучэнні, VD-сервер localhost, чытанні на аснове Selector. Ніякага блакавальнага I/O у канвееры. |
| **Наладжвальны FPS** | Аўтамабіль адпраўляе `targetFps` у handshake, VD-сервер выкарыстоўвае яго. Усе тайм-аўты канвеера вылічваюцца з `FRAME_INTERVAL_MS = 1000/fps`. |
| **Кадавальнік repeat-previous-frame-after** | SurfaceScaler прапускае працу GL на бяздзейных кадрах. Кадавальнік наладжаны паўтараць апошні кадр да 500ms на статычным змесце, прадухіляючы starvation без накладных выдаткаў GPU. |
| **Ранні старт дэкодэра** | Дэкодэр запускаецца на пазаэкранным SurfaceTexture пры паступленні першага CONFIG, да стварэння TextureView MirrorScreen. Прадухіляе страту ключавых кадраў падчас кампазіцыі UI. |
| **Разумны сеткавы зваротны выклік** | `onLost` ігнаруе незвязаныя страты сеткі (мабільныя даныя). Разрывае сесію толькі пры страце сеткі актыўнага злучэння. |
| **Аўтаабнаўленне аўтамабіля з паведамленнем** | Тэлефон адпраўляе UPDATING_CAR перад устаноўкай. Аўтамабіль паказвае статус, не перападключаецца ўсляпую. |
| **APK аўтамабіля ўбудаваны ў APK тэлефона** | Сістэма зборкі пакетуе app-server.apk унутры app-client, уключаючы функцыю Install on Car. |
| **GPU SurfaceScaler** | VD рэндэрыцца пры 480dpi тэлефона (без маштабавання сумяшчальнасці). GPU маштабуе да вакна аўтамабіля. |
| **FakeContext** | ActivityThread + getSystemContext() для сапраўднага сістэмнага Context. Абыходзіць UserManager NPE праз mDisplayIdToMirror reflection. |
| **Trusted VD flags** | `OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED` прадухіляюць міграцыю activity. |
| **Цотная шырыня вакна** | Шырыня панэлі навігацыі падганяецца для гарантыі H.264-сумяшчальных цотных памераў. |
| **Heartbeat толькі на control** | Відэа і ўваходныя злучэнні не маюць накладных выдаткаў heartbeat. Watchdog control-злучэння выяўляе мёртвыя вузлы. |
| **FileLog** | Абыходзіць фільтрацыю logcat у HyperOS. Файлавае лагаванне з ратацыяй на `/sdcard/DiLinkAuto/`. |
| **Зваротныя выклікі logSink** | VideoDecoder і UsbAdbConnection маршрутызуюць лагі праз пратакол да FileLog тэлефона. |
| **ADB папярэдне хэшаваная аўтэнтыфікацыя** | AUTH_SIGNATURE выкарыстоўвае `NONEwithRSA` + прэфікс SHA-1 DigestInfo (папярэдне хэшаваны). Адпавядае `RSA_sign(NID_sha1)` у AOSP. "Always allow&rdquo; захоўваецца карэктна. |
| **Кіраванне харчаваннем дысплея праз SurfaceControl** | `DisplayControl` загружаны з `services.jar` праз `ClassLoaderFactory` (Android 14+). Рэзервовы варыянт: `cmd display power-off/on`. Тэлефон аднаўляе дысплей пры адключэнні VD. |
| **Decoder catchup** | Чатыры зоны паскарэння: normal (0-6 кадраў), gentle 1.5x (7-12), medium 2x (13-20), aggressive 3x (21+). Ключавыя кадры ніколі не прапускаюцца. |
| **App launch dedup** | `am start` без `--activity-clear-task`. Існуючыя прыкладанні аднаўляюцца замест перазапуску. |
| **VD server backpressure** | Адкідвае неключавыя кадры ў кадавальніку, калі глыбіня чаргі запісу перавышае 6 кадраў. Прадухіляе неабмежаваны рост памяці. |
| **User disconnect** | Застаецца IDLE, без аўтаматычнага перападключэння. Захоўваецца ў SharedPreferences. |

## Тэхналагічны стэк

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
