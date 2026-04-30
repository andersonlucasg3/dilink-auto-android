# Телефон клиенті қолданбасы (app-client)

## Шолу

Телефон клиенті VD серверін орналастыруды, көлікті авто-жаңартуды және 3-қосылым релесін басқарады. Телефон қолданбасы:

1. Көліктен TCP қосылымдарын 9637 портында тыңдайды (control, NIO ServerSocketChannel)
2. Handshake-ке құрылғы ақпараты, vdServerJarPath және `targetFps` оқу арқылы жауап береді
3. Handshake-тен appVersionName салыстырады (versionCode-қа fallback-пен) — нұсқа сәйкес келмесе `UPDATING_CAR` жібереді және көлік қолданбасын dadb арқылы авто-жаңартады
4. Handshake-тен кейін көліктен бейне (9638) және енгізу (9639) қосылымдарын қабылдайды
5. vd-server.jar файлын `/sdcard/DiLinkAuto/` ішіне орналастырады және VD серверін іске қосады (FPS аргументімен)
6. VD серверінен localhost:19637 бойынша кері қосылымды қабылдайды (NIO ServerSocketChannel)
7. H.264 бейнені VD серверінен көлікке бейне қосылымы арқылы жібереді
8. Көліктен жанасу оқиғаларын (енгізу қосылымы) VD серверіне жібереді, `Dispatchers.IO` бойынша өңделеді

**Экранды түсіру жоқ. MediaProjection жоқ.** Барлық бейне VD сервер процесінен келеді.

## Компоненттер

### ClientApp

Application класы. Хабарландыру арналарын жасайды (`dilinkauto_service`, `dilinkauto_update`), жасау кезінде `UpdateManager` және `ShizukuManager` инициализациялайды.

### UpdateManager

GitHub Releases-тен жаңа нұсқаларды тексеретін өзін-өзі жаңарту механизмі.
- `checkForUpdate(force)`: `https://api.github.com/repos/andersonlucasg3/dilink-auto-android/releases/latest` сұрайды, тег атауындағы semver-ді орнатылған versionName-мен салыстырады. Мәжбүрлі болмаса 6 сағаттық үзілісті сақтайды.
- `downloadUpdate()`: Прогресс көрсету арқылы `HttpsURLConnection` арқылы APK жүктейді. `PackageManager.getPackageArchiveInfo()` арқылы тексереді.
- `installUpdate(context)`: Shizuku қолжетімді болғанда үнсіз орнату үшін `pm install -r` пайдаланады; әйтпесе `FileProvider` URI арқылы жүйелік пакет орнатушысын ашады.
- Күйлер: Idle, Checking, Available, Downloading, ReadyToInstall, Installing, Installed, UpToDate, Error. `StateFlow` арқылы ұсынылады.

### MainActivity

Екі экраны бар кіру нүктесі:

- **OnboardingScreen** (алғашқы іске қосу): 7 қадамдық шебер — Welcome, All Files Access, Battery Optimization, Accessibility Service, Notification Access, Car Setup, Done. Әр рұқсат қадамы онсыз не бұзылатынын түсіндіреді. Рұқсат берілгенде автоматты түрде алға жылжиды. Қолданушы кез келген қадамды өткізіп жібере алады.
- **ClientScreen** (кейінгі іске қосулар): күй карточкасы, start/stop түймесі, Install on Car, өзін-өзі жаңарту карточкасы, Share Logs түймесі және onboarding кезінде өткізілген рұқсаттардың қалған күйі.

### ConnectionService

3 арнайы қосылымы бар телефон-көлік қосылымының өмірлік циклін басқаратын алдыңғы план қызметі. Телефон қолданбасы ашылғанда автоматты түрде іске қосылады (мысалы, көлік USB ADB арқылы).

- **Control connection** (порт 9637): `0.0.0.0` бойынша NIO TCP сервері, handshake, heartbeat, қолданба командалары, DATA арнасын өңдейді
- **Video connection** (порт 9638): handshake-тен кейін қабылданады, бейне релесі үшін VirtualDisplayClient-ке беріледі
- **Input connection** (порт 9639): handshake-тен кейін қабылданады, INPUT кадр тыңдаушысы localhost жанасу жазуларында NetworkOnMainThreadException болдырмау үшін `Dispatchers.IO` бойынша өңделеді
- `deployAssets()`: vd-server.jar файлын sdcard-қа, app-server.apk файлын filesDir-ге шығарады
- Нұсқа сәйкессіздігін анықтайды → `UPDATING_CAR` жібереді → көлік қолданбасын dadb арқылы авто-жаңартады (WiFi ADB, dadb 1.2.10)
- Ақылды желі кері шақыруы: `TRANSPORT_WIFI` бойынша сүзіледі — тек WiFi өзгерістеріне әрекет етеді, 3G/4G мобильді деректер ауытқуларын елемейді
- Бейне кадрларды (H.264 CONFIG + FRAME) VD-ден көлікке бейне қосылымы арқылы жібереді
- Көліктен жанасу оқиғаларын (енгізу қосылымы) VD серверіне бағыттайды
- Қолданбалар тізімін 96x96 PNG белгішелерімен көлікке control қосылымы арқылы жібереді
- LAUNCH_APP, GO_BACK, GO_HOME командаларын өңдейді және VD серверіне жібереді
- VD сервері әлі қосылмаған болса, қолданбаларды іске қосуды кезекке қояды
- Көліктің авто-табуы үшін mDNS қызметін тіркейді
- Қызмет іске қосылғанда `FileLog.rotate()` — алдыңғы сессия журналын мұрағаттайды
- "Install on Car" түймесі: қолмен орнату + handshake нұсқа сәйкессіздігінде автоматты

### VirtualDisplayClient

VD сервер процесінен `localhost:19637` бойынша кері қосылымды қабылдайды. Екі Connection параметрін қабылдайды: `videoConnection` және `controlConnection`.

- NIO ServerSocketChannel accept (блокталмайтын) — VD сервері телефонҒА қосылады
- Оқиды: `MSG_VIDEO_CONFIG`, `MSG_VIDEO_FRAME`, `MSG_STACK_EMPTY`, `MSG_DISPLAY_READY`
- Жазады: `CMD_LAUNCH_APP`, `CMD_GO_BACK`, `CMD_GO_HOME`, `CMD_INPUT_TOUCH` (0x32)
- Бейне кадрлар `videoConnection.sendVideo()` арқылы жіберіледі (басқару трафигінен оқшауланған)
- Бос стек сигналы (`MSG_STACK_EMPTY`) көлікке `controlConnection.sendControl()` арқылы жіберіледі
- localhost-қа жанасу жазулары `writeLock` астында `FrameCodec.writeAll()` арқылы синхронды
- Ажыратылғанда: физикалық дисплейді қалпына келтіреді (`cmd display power-on 0` + `KEYCODE_WAKEUP`) VD сервер процесі тазалау алдында өлтірілген кездегі қауіпсіздік желісі ретінде

### AdbBridge

Резервтік shell команда көмекшісі. VD сервер операциялары және тікелей API reflection сәтсіз болғанда дисплей қуатын басқару үшін `Runtime.exec()` пайдаланып `execShell()` және `execFast()` ұсынады.

### VirtualDisplayManager

VD пайдаланылмаған кезде физикалық дисплейде қолданбаларды іске қосуды басқарады. Ым-ишара негізіндегі енгізуді енгізу үшін `InputInjectionService`-ке көпір жасайды.

### VideoEncoder

`AUTO_MIRROR` виртуалды дисплейін пайдаланатын MediaProjection + MediaCodec H.264 кодтаушы. Балама кодтау жолы (VD сервері арқылы өтетін негізгі ағынды конвейерде пайдаланылмайды).

### FileLog

Android logcat сүзгісін айналып өтетін файл негізіндегі журнал жүргізу құралы (HyperOS жүйелік емес қолданбалар үшін `Log.i/d` сүзгілейді).

- `/sdcard/DiLinkAuto/client.log` файлына жазады
- `rotate()`: ағымдағы журналды `client-YYYYMMDD-HHmmss.log` ретінде мұрағаттайды, жаңасын бастайды
- `zipLogs()`: бөлісу үшін барлық `.log` файлдарынан `dilinkauto-logs.zip` жасайды
- Ең көбі 10 журнал сақтайды (9 мұрағатталған + ағымдағы)
- Ағын-қауіпсіз: жазу ағынымен төгілетін құлыпсыз ConcurrentLinkedQueue
- Стандартты logcat шығысы үшін `android.util.Log.*` шақырады

### Multi-Touch Relay

Жанасу оқиғалары көліктен енгізу қосылымы арқылы CMD_INPUT_TOUCH (0x32) ретінде шикі MotionEvent деректерімен келеді. `handleInputFrame` localhost сокет жазуларына мүмкіндік беру үшін `Dispatchers.IO` (Main емес) бойынша өңделеді. Телефон DOWN/MOVE/UP оқиғаларын pointerId-мен тікелей VD серверіне жібереді, ол барлық белсенді көрсеткіштермен толық MotionEvent құрастыруды өңдейді.

## Қажетті рұқсаттар

| Permission | Purpose |
|-----------|---------|
| MANAGE_EXTERNAL_STORAGE | VD JAR sdcard-қа орналастыру үшін All Files Access |
| Accessibility Service | dispatchGesture арқылы виртуалды дисплейге жанасуды енгізу (оқиғаларды бақылаусыз) |
| Notification Access | Көлікке хабарландыруларды жіберу (прогрессімен) |
| API Shizuku | ADB-сіз VD серверін орналастыру және үнсіз өзін-өзі жаңарту үшін жоғары деңгейлі shell қолжетімділігі |
| QUERY_ALL_PACKAGES | Қолданбалар іске қосқышының торы |
| REQUEST_INSTALL_PACKAGES | Көлік қолданбасын dadb арқылы авто-жаңарту |

## Тәуелділіктер

- Jetpack Compose + Material 3
- kotlinx-coroutines
- dadb 1.2.10 (көлік авто-жаңартуы үшін WiFi ADB)
- Shizuku api/provider/aidl 13.1.5
- Protocol module (көлік қолданбасымен ортақ)
