# Прогресс трекері

Ағымдағы нұсқа: **v0.17.0-dev-02** (pre-release)
Соңғы жаңарту: 2026-05-01

## Кезеңдер

### v0.17.0-dev-02 (2026-05-01)

- **Телефонның қызып кетуін түзету**: Ағынды тарату конвейеріндегі телефонның қызып кетуіне әкелетін CPU белсенді күту үлгілері жойылды. `delay(1)` busy-waits блоктаушы/selector негізіндегі механизмдермен ауыстырылды.
- **AppIconCache көлік жағына жылжытылды**: Көлік жағындағы иконка кэші бастапқы PNG (192x192) дискіге сақтайды. `prepareAll()` тор пайда болғанға дейін фондық ағында барлық иконкаларды декодтап+өлшемін өзгертеді; `getPrepared()` айналдыру кезінде I/O-сыз O(1) ConcurrentHashMap іздеу. Плитка бойынша декодтау және жылдам айналдыру кезіндегі құлау жойылды.
- **AppTile жеңілдетілді**: Плитка бойынша StateFlow collect, lazy DropdownMenu және click ripple эффектілері жойылды. Негізгі түрту үшін combinedClickable орнына clickable бар жеңіл плиткалар.
- **App grid дедубликациясы**: LazyGrid құлауы packageName бойынша элементтерді дедубликациялау арқылы түзетілді.

### v0.17.0-dev-01 (2026-04-30)

- **Хабарландыруларды бір-бірден жабу және Барлығын тазарту**: Көлік хабарландыру экранында енді slide-out анимациясымен элемент бойынша жабу түймелері және тақырыпта "Clear All" түймесі бар. Жаңа протокол хабарламалары: деректер арнасында `NOTIFICATION_CLEAR` (0x04) және `NOTIFICATION_CLEAR_ALL` (0x05). Телефонның `iconPng` жүктемесінен элемент иконкалары.
- **Қолданба контекстік әрекеттері**: Қолданба плиткаларын (іске қосқыш) және навигация панеліндегі соңғы қолданбаларды ұзақ басу Жою және Қолданба туралы ақпарат бар ашылмалы мәзірді көрсетеді. Жою `APP_UNINSTALL` (0x1B) / `APP_UNINSTALLED` (0x06) арқылы таралады. Қолданба туралы ақпарат телефоннан `APP_INFO_DATA` (0x07) метадеректерімен көлік жағындағы диалогты көрсетеді. Контекстік мәзір әрекеттері shell деңгейінде қол жеткізу үшін VD сервері арқылы бағытталады.
- **Қолданба жарлықтары инфрақұрылымы** (UI-де өшірілген): VD сервері сұрауы + APK XML резервімен `APP_SHORTCUTS` (0x18) / `APP_SHORTCUTS_LIST` (0x19) / `APP_SHORTCUT_ACTION` (0x1A) протокол хабарламалары. Белгілерді ажырату нақтыланғанша өшірілген (issue #57).
- **Артқа түймесін түзету**: GO_BACK енді басты мәзірге оралмас бұрын әрекеттерді бір-бірден жабады, дұрыс стек бақылауын және `FOCUSED_APP` (0x16) хабарламаларын пайдаланады.
- **Samsung DeX / Жұмыс үстелі режимі DPI** (кері қайтарылды): `UiModeManager.currentModeType` анықтауын және 213dpi пайдаланатын бастапқы іске асыру dev-02-де кері қайтарылды. VD деңгейіндегі жалаушаны жою тәсілімен ауыстырылды.

### v0.16.0 (2026-04-29)

- **Shizuku**: Қолданба енді Shizuku авторизацияланған қолданбалар тізімінде көрінеді (ShizukuProvider ContentProvider қосылды). Баптау карточкасы рұқсаттарды басқару үшін Shizuku қолданбасын тікелей ашады.
- **Shizuku exec түзету**: Оқу алдында FD дублирлеу арқылы binder транзакцияларындағы ParcelFileDescriptors-тан EBADF түзетілді. Үнсіз өзін-өзі жаңарту үшін Shizuku арқылы `pm install`.
- **Автокөліктегі Shizuku режимі**: Shizuku белсенді болғанда автокөлік қосылымы енді "WiFi күту" күйінде тұрып қалмайды — gateway IP қайта әрекет циклі бірінші әрекеттен кейін тоқтамайды.
- **Нұсқа тексеру versionName-ге ауыстырылды**: Автокөлік қолданбасын жаңартулар енді versionCode бүтін сандарының орнына versionName жолдарын салыстырады (semver-сәйкес), бұл автокөлік үшін алдын ала жаңартуларды қосады.
- **Қауіпсіздікті күшейту**: Қолданылмайтын `RECORD_AUDIO` және `SYSTEM_ALERT_WINDOW` рұқсаттары жойылды. Арнайы мүмкіндіктер қызметі енді `typeAllMask` оқиғаларын тыңдамайды (тек `dispatchGesture` пайдаланады).
- **Қолданбалар торы өнімділігі**: Автокөлік дисплейінде жылдам айналдыру кезіндегі ақау түзетілді. `GridCells.Adaptive` → `GridCells.Fixed` есептелген бағандармен. Әр плитка үшін `inSampleSize=2` + `RGB_565` арқылы lazy bitmap декодтау.
- **Желі тұрақтылығы**: Телефон жағындағы `NetworkCallback` енді тек `TRANSPORT_WIFI` бойынша сүзгіленеді, 3G/4G мобильді деректер ауытқуларын елемейді.

### v0.15.0 (2026-04-28)

- **Phone service auto-start**: `ConnectionService` телефон қолданбасы ашылғанда автоматты түрде іске қосылады (мысалы, көлік USB ADB арқылы), Start түймесін қолмен басу қажеттілігін жояды. ✅ Done
- **Car no longer clears phone task**: Көліктің USB ADB телефонды іске қосуынан `--activity-clear-task` жойылды. Егер телефон қолданбасы бұрыннан ашық болса, көлік оны бұзбай алға жылжиды. ✅ Done
- **Share Logs button**: Негізгі экрандағы "Share Logs" түймесі `/sdcard/DiLinkAuto/` ішіндегі барлық `*.log` файлдарын архивтеп, Android бөлісу парағы арқылы бөліседі. `FileLog.zipLogs()` `dilinkauto-logs.zip` жасайды. ✅ Done
- **Encoder configuration**: Кеңірек құрылғы үйлесімділігі үшін 8Mbps CBR Main профиліне реттелді. Backpressure қосылды (жазу кезегі 6 кадрдан асқанда кілттік емес кадрларды тастайды). ✅ Done
- **VideoDecoder catchup**: Бірқалыпты кідірісті қалпына келтіру үшін төрт сатылы жылдамдату аймағы (normal, gentle 1.5x, medium 2x, aggressive 3x). ✅ Done
- **French translation**: Бар 7 тілге француз тілі (fr) қосылды (енді барлығы 8). ✅ Done
- **Update check on app open**: Өзін-өзі жаңарту тексеруі қолданба ашылғанда бірден іске қосылады, жаңарту хабарландыруы және қайта тексеру түймесімен. ✅ Done
- **Distribution channel selector**: Өзін-өзі жаңарту үшін тұрақты релиздер мен dev алдын ала релиздер арасында таңдау карточкасы. ✅ Done
- **CarLaunchScreen redesign**: Кең көлік дисплейлері үшін оңтайландырылған екі бағанды макет. ✅ Done
- **Phone app UI refactor**: Негізгі экран қайта ұйымдастырылды, орнату ағыны қателері түзетілді. ✅ Done
- **Onboarding improvements**: Көлік орнату алғышарттары, жақсартылған орнату прогресі UI, жақсартылған How to Connect карточкасы. ✅ Done
- **Car UI two-mode separation**: Іске қосу экраны (толық экранды, қосылуға бағытталған) және ағынды режим (навигация панелі + мазмұн). Қолданбалар тізімі келгенде тегіс ауысу. ✅ Done
- **Video artifact fixes**: Ақылды декодер тастаулары + сатылы catchup + кодтаушы backpressure визуалды артефактілерді жояды. ✅ Done
- **Touch input fixes**: Тұрақты 480dpi VD сервер DPI кезінде дұрыс координаталық сәйкестендіру, MOVE кезінде инкрементті жанасуды жіберу, түрту ым-ишарасы және қолмен IP түзетулері. ✅ Done
- **Screen restore and network stability**: USB ажыратылғаннан кейін дисплейді қалпына келтіру, желі кері шақыру жақсартулары. ✅ Done
- **Internationalization**: Барлық жаңа UI жолдары 8 тілге аударылды (en, pt-BR, ru, be, fr, kk, uk, uz). ✅ Done
- **CI/CD automation**: 6 арнайы жұмыс процесі — тексеру (`build.yml`, `build-develop.yml`), `-dev` тегтерінде алдын ала релиз (`build-pre-release.yml`), `vX.Y.Z` тегтерінде релиз (`build-release.yml`), main→develop кері синхрондау (`sync-main-to-develop.yml`), және автономды issue-agent (`issue-agent.yml`). ✅ Done

### v0.14.0

- **Shared version source**: Нұсқа коды/атауы енді gradle.properties-те — екі қолданба үшін бір өңдеу.
- **MAX_PAYLOAD_SIZE 2MB → 128MB**: 136+ PNG белгішелері бар қолданбалар тізімі 2MB асып, ProtocolException және қосылым үзілістерін тудырды.
- **Display restore fix**: `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` `ACQUIRE_CAUSES_WAKEUP` арқылы USB ажыратылғаннан кейін дисплейді қалпына келтіреді, тіпті VD сервері тазалаусыз өлсе де.
- **POCO F5 compatibility**: `FLAG_KEEP_SCREEN_ON` ағынды режим кезінде экран құлпын болдырмайды. Xiaomi 17 Pro Max бар POCO F5-те жанасу енгізуі жұмыс істейтіні расталды.
- **Car-side touch logging**: MirrorScreen жанасу оқиғалары және sendTouchEvent сәттілігі жөндеу үшін журналданады.
- **Developer credit in About**: "Developed with ❤" GitHub сілтемесімен барлық 7 тілде.

### v0.13.1 — First Release (2026-04-26)

- **Onboarding flow**: Алғашқы іске қосу рұқсаттарын бағытталған орнату (All Files, Battery, Accessibility, Notifications). Рұқсаттарды автоматты түрде анықтайды, диалогтық баптаулар үшін резервтік сұрау.
- **Self-update (UpdateManager)**: GitHub Releases API тексеру, прогресспен APK жүктеу, жүйелік пакет орнатушысы арқылы орнату. 6 сағаттық үзіліс.
- **Main view reorganization**: Негізгі экран күнделікті пайдалануға бағытталған (қосылу нұсқаулығы, күй, start/stop, жаңартулар). Рұқсаттар, көлікке орнату, about және қайырымдылық сілтемелері бар баптаулар экраны.
- **USB + WiFi install on car**: Параллель ішкі желі сканері көлік ADB үшін барлық 254 IP тексереді. ARP/neighbor/gateway табумен біріктірілген. USB хост әрекеті жасалды, бірақ көлік USB-A тек хост.
- **VD server now Kotlin Gradle module**: :protocol және kotlinx-coroutines тәуелді. NioReader, FrameCodec.writeAll ортақ пайдаланады.
- **Performance**: Кодтаушыда бір кадрға аралық ByteArray бөлу жойылды. NioReader бастапқы сыйымдылығы 256KB. isKeyFrame FrameData-да кэштеледі.
- **Encoder**: CBR 8Mbps, Main профилі, бапталатын FPS (әдепкі 30, көлік 60 сұратады), PRIORITY 0 (нақты уақыт). I_FRAME_INTERVAL=1s. Статикалық мазмұн үшін `repeat-previous-frame-after`=500ms.
- **Donations**: GitHub Sponsors және Pix (Brazil) README және қолданба баптауларында белгішелер.
- **Adaptive vector icon**: Сымсыз сигналдары бар көлік силуэті, екі қолданбаға да қолданылды.
- **Internationalization**: Жол ресурстары English, Portuguese (pt-BR), Russian (ru), Belarusian (be), French (fr), Kazakh (kk), Ukrainian (uk), және Uzbek (uz) тілдерінде.
- **Release signing**: Күшті құпия сөзбен бекітілген keystore. CI жиналымдары GitHub Secrets арқылы қол қойылған релиз APK шығарады.

### v0.13.0 — USB ADB Auth Fix (2026-04-25)

Негізгі себеп табылды және түзетілді: `Signature.getInstance("SHA1withRSA")` ADB AUTH_TOKEN екі рет хэштейды. ADB 20-байттық токені алдын ала хэштелген мән — AOSP `RSA_sign(NID_sha1)` оны бұрыннан хэштелген деп қарастырады. Енді қолмен SHA-1 DigestInfo ASN.1 префиксімен `NONEwithRSA` пайдаланылады (алдын ала хэштелген қол қою). "Always allow" енді дұрыс сақталады — AUTH_SIGNATURE диалогсыз қайта қосылуда қабылданады.

### v0.13.0 — Display Power + Key Encoding (2026-04-25)

- **Display power via SurfaceControl (Android 14+)**: `DisplayControl`-ты `/system/framework/services.jar` ішінен `ClassLoaderFactory.createClassLoader()` + `android_servers` native кітапханасы арқылы жүктейді. Reflection сәтсіз болса, `cmd display power-off/on` резерві.
- **Screen restore on disconnect**: Телефонның `VirtualDisplayClient.disconnect()` VD сервер процесі тазалау алдында өлтірілгенде қауіпсіздік желісі ретінде `cmd display power-on 0` + `KEYCODE_WAKEUP` орындайды.
- **ADB key encoding rewrite**: AOSP эталонына дәл сәйкес `encodePublicKey()` қайта жазылды — тұрақтылар түзетілді, айқын `bigIntToLEPadded()`, struct тақырыбын журналдау.
- **Decoder catchup**: Кезек `100ms * TARGET_FPS / 1000` кадрдан асқанда (60fps кезінде 6), әрбір екінші кілттік емес кадрды өткізіп жібереді. Кескін секірмей, біртіндеп қуып жетіп, 2x жылдамдықпен қозғалады.
- **Car log buffer**: 200 → 10,000 хабарлама. USB ADB auth журналдары енді control қосылымы оларды тазартқанға дейін сақталады.

### v0.12.5 — Connection Stability (2026-04-24)

- **Smart network callback**: `onLost` енді үзілген желінің қосылымды тасымалдайтын желі екенін тексереді. Байланыссыз үзілістерді елемейді (мобильді деректер циклі). Бұрын кез келген желі үзілісі ағынды сессияны өлтіретін.
- **USB ADB auth diagnostics**: Толық auth ағынын журналдау carLogSend → телефон FileLog арқылы бағытталды. AUTH_SIGNATURE әр уақытта қабылданбайтынын анықтады (телефон adbd сақталған кілтті танымайды). Зерттелуде.
- **AUTH_RSAPUBLICKEY key preview logging**: Стандартты ADB пішімімен салыстыру үшін телефонға жіберілген ашық кілттің бірінші/соңғы байттарын журналдайды.

### v0.12.0–v0.12.4 — Bug Fixes & Polish (2026-04-24)

- **Touch input fixed**: `handleInputFrame` `Dispatchers.IO` бойынша өңделеді (Main болған, localhost сокет жазуында `NetworkOnMainThreadException` тудырды)
- **VD server NIO command reader**: Шексіз цикл түзетілді — switch ішіндегі `break` тек switch-тен шықты, талдау циклінен емес. Енді `break parseLoop;` белгіленген шығу пайдаланылады.
- **App launch dedup**: `am start` ішінен `--activity-clear-task` жойылды. Бар қолданбалар қайта іске қосылудың орнына жалғасады.
- **Bitrate**: 8Mbps CBR орнатылды (құрылғы үйлесімділігі үшін кейінгі релиздерде 12Mbps-тен өзгертілді).
- **FPS configurable**: HandshakeRequest-ке `targetFps` өрісі қосылды. Көлік 60fps сұратады. VD сервері FPS-ті командалық жол аргументі ретінде қабылдайды, кодтаушы `KEY_FRAME_RATE` және `FRAME_INTERVAL_MS` үшін пайдаланады.
- **Nav bar**: 72dp → 76dp, белгішелер 32dp → 40dp, жол биіктігі 52dp → 60dp, мәтін 12sp → 14sp.
- **Launcher app icons**: 40dp → 64dp, тор ұяшықтары 140dp → 160dp, мәтін bodyMedium → bodyLarge.
- **Search bar keyboard**: TextField-те `windowSoftInputMode="adjustNothing"` + `imePadding()`. Пернетақта activity-ді итермейді, тек іздеу жолағы қозғалады.
- **Notifications**: ID бойынша дедубликация (прогресс жаңартулары барларды ауыстырады), прогресс жолағын қолдау (determinate + indeterminate), VD-де ие қолданбаны түрту арқылы іске қосу + айна көрінісіне ауысу.
- **Recent apps**: `pruneUnavailable()` қолданбалар тізімі жаңартылғанда телефонда бұдан былай жоқ қолданбаларды жояды.
- **USB ADB key storage**: Басымдық тәртібі: `/sdcard/DiLinkAuto/` → `getExternalFilesDir` → `getFilesDir`. Миграция барлық орындарды іздейді.
- **Update flow**: Телефон `UPDATING_CAR` хабарламасын жібереді. Көлік "Updating car app..." күйін көрсетеді және қайта қосылмайды.
- **Update flow crash fix**: `updatingFromPhone` жалауы орнатылғанда көлік бейне/енгізу қосылымын өткізіп жібереді.
- **VideoDecoder/UsbAdbConnection logSink**: Көлік жағы журналдары протокол арқылы телефонның FileLog-ына бағытталады.

### v0.11.0–v0.11.3 — Non-Blocking Pipeline + Encoder Fix (2026-04-24)

- **VideoConfig**: `TARGET_FPS` және `FRAME_INTERVAL_MS` ортақ тұрақтылары. Барлық бейне жолы күту/сұраулары кадр интервалымен шектелген.
- **SurfaceScaler periodic re-draw**: Әрқашан әр кадр интервалында `glDrawArrays + eglSwapBuffers` шақырады, тіпті VD-ден жаңа кадр болмаса да. Тек жаңа кадр қолжетімді болғанда `updateTexImage` шақырады. Кодтаушыны статикалық мазмұнда қоректендіреді.
- **VD server NIO**: Блокталатын `DataOutputStream/DataInputStream` NIO жазу кезегімен (`ConcurrentLinkedQueue<ByteBuffer>`) + Selector негізіндегі команда оқу құралымен ауыстырылды. Конвейерде блокталатын I/O жоқ.
- **Encoder poll**: `dequeueOutputBuffer` тайм-ауты 100ms-тен `FRAME_INTERVAL_MS` дейін азайтылды (60fps кезінде 16ms).
- **VideoDecoder queue poll**: 100ms → `FRAME_INTERVAL_MS`.
- **NioReader select timeout**: 100ms → `FRAME_INTERVAL_MS` (конструктор параметрі арқылы бапталады).
- **Connection writer park**: 50ms → `FRAME_INTERVAL_MS`.
- **VirtualDisplayClient accept loop**: 100ms → `FRAME_INTERVAL_MS`.
- **VideoDecoder early start**: Бірінші CONFIG кадр келгенде экраннан тыс SurfaceTexture-де іске қосылады (MirrorScreen-ке дейін). MirrorScreen декодерді нақты TextureView бетімен қайта іске қосады.
- **VideoDecoder queue**: 3 → 30 кадр. Кадрлар `start()` шақырылғанға дейін де кезекке қойылады.
- **FileLog**: Файл негізіндегі журнал жүргізу (`/sdcard/DiLinkAuto/client.log`) HyperOS logcat сүзгісін айналып өтеді. Ротация: `client-YYYYMMDD-HHmmss.log` ретінде мұрағаттайды, ең көбі 10 сақтайды.

### v0.10.0 — 3-Connection Architecture (2026-04-24)

Бір мультиплекстелген TCP қосылымы бейне тоқтауын тудыратын арна аралық кедергіні жою үшін 3 арнайы қосылымға бөлінді:
- **Control connection** (порт 9637): handshake, heartbeat, қолданба командалары, DATA арнасы
- **Video connection** (порт 9638): тек H.264 CONFIG + FRAME (phone → car)
- **Input connection** (порт 9639): тек жанасу оқиғалары (car → phone)

Әр қосылымның тәуелсіз SocketChannel, NioReader және жазу кезегі бар өз `Connection` данасы. Heartbeat/watchdog тек control-да.

### v0.9.2 — Diagnostic Build (2026-04-23)

~420 кадрдан кейін бейне кадрының тоқтауын зерттеуге арналған кешенді журналдау:
- **Video relay loop**: readByte алдында/кейін, жүктеме өлшемі, белгісіз msgTypes журналдайды
- **NioReader**: channel.read() 0 қайтарғанда журналдайды (буфер күйімен әрбір 100-ші жағдай)
- **Connection writer**: әр 60 бейне кадрын журналдайды (саны, өлшемі, кезек тереңдігі, тоқтаулар), жазу тоқтауларын журналдайды
- **Writer stall fix**: writeBuffersToChannel-де `Thread.yield()` → `delay(1)` — бос күтудің орнына IO ағынын корутин пулына қайтарады (зерттеу қорытындысы: Thread.yield бейне реле корутинін аштыққа ұшыратты)
- **Frame listeners**: бейне емес кадр өңдеушілері асинхронды өңделеді (`scope.launch`), ауыр өңдеу (қолданбалар тізімін декодтау) оқу құралын TCP төгуден бөгемеу үшін

### v0.9.0-v0.9.1 — Write Stall Investigation (2026-04-23)

Бейне тоқтауының негізгі себебін зерттеу. TCP буфер өлшемін журналдау, жазу тоқтау диагностикасы қосылды.
- Қолданбалар тізімін жіберу кезінде TCP жіберу буфері 108,916 байт қалдықта қатып қалатыны расталды
- Бейне кадрларының өзінде нөл жазу тоқтаулары бар (бейне кезінде кезек=0)
- USB ADB кілті тұрақты екені расталды (LOADED fp=c4e88a05) — қайталанатын auth HyperOS мінез-құлқы

### v0.8.4-v0.8.8 — Bug Fixes + Log Routing (2026-04-23)

- **Car log routing**: Барлық көлік жағы `Log.*` шақырулары `carLogSend()` арқылы бағытталады, ол DATA арнасы `CAR_LOG` арқылы телефонға жібереді. Телефон logcat-та `CarLog` тегімен журналдайды. Қосылым орнатылғанға дейін 200 хабарламаға дейін буфер.
- **VD server launch reverted** `shellNoWait` + `exec app_process` (v0.6.2 тәсілі). `setsid`/`nohup` ажырату localhost байланысын бұзды. VD сервері USB ажыратылғанда өледі, бірақ қайта қосылғанда қалпына келеді.
- **VD ServerSocket**: `startListening()` синхронды ашылады, `waitForVDServer()` бұрыннан күтіп тұрса өткізіп жібереді
- **USB ADB key persistence**: `getExternalFilesDir` жазылу тексеруімен + `getFilesDir` миграциясы + саусақ ізін журналдау
- **ClosedSelectorException**: жазу және NioReader-де `selector.isOpen` тексерулері
- **Infinite recursion fix**: жаппай Log→carLogSend ауыстыру кездейсоқ carLogSend-тің өзін зақымдады

### v0.8.3 — Final Polish + VD Wait Fix + USB Key Diagnostic (2026-04-23)

Соңғы кезең + қате түзетулері:
- **VD wait guard**: `waitForVDServer` бұрыннан күтіп тұрса өткізіп жібереді — қайта қосылу кезінде көлік бірнеше handshake жібергенде ServerSocket жабу/қайта ашуды болдырмайды (VD сервері қосыла алмауының негізгі себебі, 45s ішінде 4x `startListening` көрсететін телефон logcat арқылы расталды)
- **USB key diagnostic**: Көлік кілт ақпаратын (`LOADED`/`GENERATED` + саусақ ізі + жол) USB ADB connect-тен кейін телефондағы `/data/local/tmp/car-adb-key.log` файлына жазады. Auth кілт өзгергендіктен немесе телефон "Always allow" сақтамағандықтан қайталанатынын диагностикалауға мүмкіндік береді.
- **M3**: Пакеттелген multi-touch — жаңа `TOUCH_MOVE_BATCH` (0x04) хабарлама түрі барлық көрсеткіштерді бір кадрда тасымалдайды. MOVE оқиғалары көлік жағында пакеттеледі, телефон жағында пакеттен шығарылады. N-саусақты ым-ишаралар үшін жүйелік шақыруларды N*60/сек-тан 60/сек-қа дейін азайтады.
- **M10**: Eject күйі SharedPreferences-те сақталады — көлік қолданбасын өлтіру/қайта іске қосудан аман қалады. USB қайта қосылғанда немесе ACTION_START кезінде тазартылады.
- **L4**: NioReader тікелей емес, heap ByteBuffer пайдаланады — детерминді GC тазалау.

### v0.8.2 — Polish + VD ServerSocket + USB Key Persistence (2026-04-23)

6 әрлеу түзетуі + 2 сыни түзету:
- **Hotfix**: VirtualDisplayClient `startListening()` (синхронды байлау) + `acceptConnection()` (асинхронды күту) болып бөлінді. ServerSocket handshake жауабынан БҰРЫН ашылады — VD серверінің localhost:19637-ге қосыла алмауын түзетеді.
- **USB key persistence**: Кілт сақтау `getExternalFilesDir` жазылу тексеруімен + `getFilesDir` миграциясын пайдаланады. Қосылымдар арасында кілт өзгеретінін диагностикалау үшін әр қосылымда саусақ ізін журналдау.
- **M12**: `checkStackEmpty` нәзік `sed` секция талдауының орнына қарапайым `grep -E` пайдаланады
- **L2**: VD сервері localhost сокет буферлері 256KB орнатылды
- **L3**: Декодер уақыт белгілері үшін `System.nanoTime()/1000` пайдаланады (тұрақты 33ms өсім болған)
- **L5**: `UsbAdbConnection.readFile()` оқу циклі + try-with-resources пайдаланады
- **L6**: SurfaceScaler HandlerThread тоқтату кезінде дұрыс аяқталады
- **L7**: Қолданба белгішелері 48x48-тен 96x96px дейін үлкейтілді

### v0.8.1 — Touch + Decoder Performance + Hotfixes (2026-04-23)

4 өнімділік оңтайландыруы + 2 құлау түзетуі:
- **M2**: `checkStackEmpty()` фондық ағында жұмыс істейді — команда оқу құралы Back басқаннан кейін 300ms+ блокталмайды
- **M4**: VD серверінде алдын ала бөлінген `PointerProperties[10]` + `PointerCoords[10]` пулдары — әр жанасудағы GC қысымын жояды
- **M5**: Декодер кадр кезегі 6-дан 3-ке дейін азайтылды (200ms → 100ms кідіріс шегі)
- **M6**: `cmd display power-off 0` "fire-and-forget" ағынында жұмыс істейді — жанасу енгізу жолынан діріл жойылды
- **Crash fix**: Connection жазу және NioReader-де `ClosedSelectorException` — `selector.isOpen` тексерулері және catch блогы қосылды. Жарыс: `disconnect()` оқу/жазу корутиндері әлі орындалып жатқанда селекторларды жабады.
- **Bug fix**: `startConnection()` ішіндегі `usbConnecting` қалпына келтіру енді `usbAdb == null` арқылы да қорғалған (USB auth жарысының екінші орны, біріншісі v0.7.3-те түзетілген)

### v0.8.0 — I/O Pipeline Performance (2026-04-23)

3 I/O өнімділік оңтайландыруы + hotfix:
- **H2**: Gathering writes — `channel.write(ByteBuffer[])` тақырып+жүктемені бір жүйелік шақыруға/TCP сегментіне біріктіреді
- **H3**: Бейне релесі дәл өлшемді `ByteArray(size)` тікелей бөледі — аралық `relayBuf` + `copyOf` жойылды
- **M1**: VD сервері DataOutputStream-ті `BufferedOutputStream(65536)` ішіне орайды — шағын localhost жазуларын біріктіреді
- **Hotfix**: `waitForVDServer()` handshake жауабын жіберуден БҰРЫН шақырылады — :19637-де ServerSocket көлік VD серверін орналастырғанға дейін ашық екеніне кепілдік береді (v0.7.4 регрессиясы: реттілік VD күтуді жауаптан кейін қойып, VD серверінің қосылу сәтсіздігіне әкелді)

### v0.7.4 — Write Queue + Flow Sequencing (2026-04-23)

Жазу архитектурасының өзгеруі + ағын жақсартулары:
- **Write queue**: `synchronized(outputLock)` құлыпсыз `ConcurrentLinkedQueue` + арнайы жазу корутинімен ауыстырылды. Жазушы TCP жіберу буфері толғанда `delay(1)` пайдаланады (IO ағынын пулға қайтарады). Жазу кезінде басқа корутиндерді блоктау жоқ.
- **H10**: Handshake → auto-update → VD deploy реттелген. Auto-update инициализацияны кідіртеді, ажыратады, көлік қайта қосылуын күтеді.
- **H11**: Прогрессивті көлік күй хабарламалары: "Preparing..." → "Starting..." → "Waiting for video stream..."
- **H12**: Көлік USB ADB connect кезінде "Check phone for authorization dialog" көрсетеді
- **Bug fix**: VD сервері жасалғаннан кейін VD-де home activity іске қосады — кодтаушы мазмұнды бірден алады
- **Bug fix**: `usbConnecting` тек `usbAdb == null` болғанда қалпына келтіріледі — қайталанатын USB-ADB auth диалогтарын болдырмайды

### v0.7.3 — Network Resilience + HyperOS Freeze Fix (2026-04-23)

Желі төзімділігі + logcat дәлелдері арқылы анықталған сыни HyperOS түзетуі:
- **Battery exemption**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — экран өшірулі кезде HyperOS "greeze" клиент қолданбасын қатыруын болдырмайды. "frames only during touch" қатесінің негізгі себебі: VD сервері (shell процессі) 960+ кадр шығарды, бірақ клиент қолданбасының NioReader-і ОЖ қуат басқаруымен қатырылды. Сұрау алғашқы іске қосуда көрсетіледі.
- **C4/M11**: Көлік WiFi трегі енді gateway IP-ді әр 3s сайын қайталайды (бір реттік болған). Көлікте `ConnectivityManager.NetworkCallback` қосылды, ол WiFi қолжетімді болғанда WiFi трегін қайта іске қосады. USB қосылғаннан кейін қосылған хотспотты өңдейді.
- **H9**: Телефон CONNECTED/STREAMING күйінде желі үзілгенде белсенді түрде ажыратады (болған: heartbeat тайм-ауты үшін 10s күту). `onLost` → `cleanupSession()` → тыңдау циклі қайта іске қосылады. `onAvailable` тек WAITING күйінде қалпына келтіреді (белсенді қосылымдарды бұзбай).
- **Bug fix**: VD сервері жасалғаннан кейін VD-де home activity іске қосады (`am start --display <id> HOME`) — кодтаушының мазмұнды бірден алуын қамтамасыз етеді.
- **Bug fix**: `usbConnecting` тек `usbAdb == null` болғанда қалпына келтіріледі — қайталанатын USB-ADB auth диалогтарын болдырмайды.

### v0.7.2 — Car-Side Stability + Selector Fix (2026-04-23)

5 тұрақтылық түзетуі + сыни NioReader түзетуі:
- **H1**: NioReader-де `delay(1)` сұрауы **Selector**-мен ауыстырылды — `selector.select(100)` деректер келгенде epoll арқылы бірден оянады. Бейне ағынсыздығын түзетеді (Android 10 көлігінде `delay(1)` 10-16ms алғандықтан кадрлар тек жанасу кезінде ақты). Ажыратудан таза тоқтату үшін `wakeup()`/`close()` қосылды.
- **H7**: `wifiReady`, `usbReady`, `vdServerStarted`, `usbConnecting` бойынша `@Volatile` — CONNECTING күйінде тұрып қалуды болдырмайды
- **H8**: `VideoDecoder.stop()` `codec.stop()` алдында беру ағынын күтеді (2s тайм-аут) — native crash болдырмайды
- **M7**: VD серверіндегі қос `cleanup()` жойылды — қос босатудағы IllegalStateException болдырмайды
- **M8**: `cleanupSession()` `_serviceState` WAITING күйіне қалпына келтіреді — қайта қосылу кідірісі кезінде ескірген UI болдырмайды
- **M9**: `onCreate()` статикалық `activeConnection` және `_serviceState` тазартады — қызмет қайта іске қосылғанда ескірген күйді болдырмайды

### v0.7.1 — Critical Bug Fixes (2026-04-23)

Кешенді шолудан 6 сыни/жоғары түзету:
- **C1**: `writeAll()` 5s жазу тайм-ауты — толық жіберу буферінде жүйенің қатып қалуын болдырмайды
- **C3**: Авто-жаңарту әрекеті жалауы — шексіз жаңарту/қайта іске қосу циклін бұзады
- **C5**: WakeLock 4h авто-босату — қалыптан тыс шығуда батарея зарядының ағуын болдырмайды
- **C6**: VirtualDisplayClient channel/reader бойынша `@Volatile` + тайм-аутпен қорғалған жазулар
- **H5**: `Connection.connect()` try/catch — бас тарту кезінде SocketChannel жабады
- **H6**: Ажырату тыңдаушысы try/catch ішіне оралған — ерекшелік таралуын болдырмайды

### v0.7.0 — Full NIO + Service Fix (2026-04-23)

Барлық сокет операциялары блокталмайтын NIO-ға ауыстырылды. mDNS тіркеу бұдан былай тыңдау циклін блоктамайды. Нұсқа коды PackageManager арқылы орындалу уақытында оқылады.

**Changes:**
- **NioReader**: SocketChannel үшін жаңа блокталмайтын буферленген оқу құралы (delay(1) сұрау, корутин-кооперативті)
- **Connection.kt**: SocketChannel бүкіл уақыт бойы блокталмайтын күйде қалады — connect/accept кейін configureBlocking(true) жоқ
- **FrameCodec.kt**: NIO `readFrame(NioReader)` және `writeFrameToChannel(SocketChannel, Frame)` әдістері қосылды
- **VirtualDisplayClient.kt**: NIO оқулар (NioReader) + ByteBuffer жазулар, арна блокталмайтын күйде қалады
- **VirtualDisplayServer.java**: Қосылу үшін NIO SocketChannel (қайталаумен блокталмайтын finishConnect)
- **ConnectionService.kt**: probePort() NIO-ға ауыстырылды; mDNS тіркеу 5s тайм-аутпен фонда іске қосылады (WiFi-сыз қызметтің іске қосылмауын түзетеді)
- **Version code**: `APP_VERSION_CODE` тұрақтысы жойылды — екі қолданба да `PackageManager.getPackageInfo()` арқылы орындалу уақытында versionCode оқиды

### v0.6.2 — Parallel Connection Model + Auto-Update (2026-04-23)

Негізгі архитектуралық қайта жазу: параллель WiFi + USB тректері, NIO блокталмайтын сокеттер, телефон арқылы авто-жаңарту, IInputManager арқылы multi-touch.

**Working:**
- **Parallel connection state machine**: WiFi табу + USB ADB бір уақытта жұмыс істейді, VD екеуі де дайын болғанда орналастырылады
- **Auto-update**: Телефон handshake кезінде ескірген көлік қолданбасын анықтайды, WiFi ADB (dadb) арқылы жаңартуды жібереді
- **Phone deploys VD JAR**: Іске қосу кезінде `/sdcard/DiLinkAuto/vd-server.jar` ішіне шығарылады (CRC32 тексеріледі)
- **Car APK embedded in phone**: Құрастыру жүйесі көлік APK-сын телефон assets ішіне жинайды
- **NIO non-blocking sockets**: Барлық accept/connect `ServerSocketChannel`/`SocketChannel` пайдаланады — лезде бас тарту, EADDRINUSE жоқ
- **Multi-touch input**: `ServiceManager → IInputManager` арқылы тікелей MotionEvent енгізу (түрту, сырғыту, қысуды қолдайды)
- **Screen power management**: Ағынды режим кезінде `cmd display power-off 0`, proximity/lift wake өшірілген, жанасу енгізуінен кейін шектелген қайта өшіру
- **State machine recovery**: `connectionScope` ажыратылғанда барлық корутиндерден бас тартады, экспоненциалды кідіріспен қайта қосылу
- **User disconnect**: Eject түймесі қайта қосылуды тоқтатады (IDLE күйінде қалады)
- **App search**: Қолданбалар торының төменгі жағында іздеу өрісі, қолданбалар әліпби бойынша сұрыпталған
- **Notification panel**: Навигация панеліндегі қоңырау белгішесі санауышпен
- **72dp nav bar**: Көлік дисплейлері үшін үлкенірек белгішелер (32dp) және мәтін (12sp)
- **Handshake version check**: HandshakeRequest-те `appVersionCode` өрісі, HandshakeResponse-те `vdServerJarPath`
- **Network change handling**: Телефон желі интерфейсі өзгергенде тыңдау циклін қалпына келтіреді (хотспот ауысуы)
- **H.264 encoding**: 8Mbps CBR, High профилі, төмен кідіріс режимі
- **Handshake timeout**: Дұрыс бас тартумен 10s тайм-аут (ескірген тайм-ауттарсыз)

**Architecture changes from v0.5.0:**
- Көліктен хотспот SSID сұрау/WiFi авто-қосылу жойылды (жеңілдетілді)
- UsbAdbConnection + AdbProtocol protocol модуліне жылжытылды (екі қолданбаға ортақ)
- VD сервері телефонҒА қосылады (кері қосылым) телефон VD серверіне қосылудың орнына
- VD сервері телефон ажыратылғанда шығады (бір реттік, көлік қажет болса қайта орналастырады)
- Телефон VD JAR жалпы сақтау орнына шығарады, көлік жолды handshake-тен оқиды

### v0.5.0 — USB ADB + Automated Setup (2026-04-22)

Негізгі архитектуралық өзгеріс: **көлік** VD серверін телефонға USB ADB арқылы орналастырады. Wireless Debugging жойылды.

### v0.4.0 — GPU-Scaled VirtualDisplay (2026-04-22)

Қолданбалар телефонның табиғи DPI (480dpi) бойынша рендерленеді, GPU көлік көрініс терезесіне масштабтайды. SurfaceScaler EGL/GLES конвейері.

### v0.3.0 — Persistent Navigation Bar (2026-04-21)

Әрдайым көрінетін сол жақ навигация панелі, TextureView, нақты қолданба белгішелері бар көлік UI.

### v0.2.0–v0.2.3 — Virtual Display Foundation (2026-04-21)

VD жасау, self-ADB, төзімді сервер, көп қолданбаны қолдау.

### v0.1.0–v0.1.1 — Initial Implementation (2026-04-21)

Жоба жасалды. Эмуляторларда экранды айналандыру.

---

## Fix Tracker

Өнімділікті, тұрақтылықты және ағын үздіксіздігін қамтитын кешенді шолу 2026-04-23 орындалды.

### Phase 1 — Critical (fix before next release)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| C1 | Stability/Perf | `writeAll()` толық жіберу буферінде шексіз айналады — тайм-аут жоқ, `outputLock` ұстайды, барлық жіберушілерді блоктайды. Жүйенің қатып қалу қаупі | **v0.7.1** |
| C2 | Flow | VD сервері USB ажыратылғанда өледі (`shellNoWait` процесті ADB ағынына байлайды). Толық қайта қосылу 5-15s | REVERTED — `setsid`/`nohup` localhost байланысын бұзды. `shellNoWait`+`exec` пайдаланылады (v0.6.2 тәсілі). Қайта қосылғанда қайта қосылады. |
| C3 | Flow | Авто-жаңартуда цикл үзілісі жоқ — егер `pm install` үнсіз сәтсіз болса, шексіз қайта іске қосу циклі | **v0.7.1** |
| C4 | Flow | Көлік WiFi трегі бір рет жұмыс істейді және бас тартады — USB қосылғаннан кейін хотспот қосылуы → мәңгі тұрып қалады | **v0.7.3** |
| C5 | Stability | WakeLock тайм-аутсыз алынған — `onDestroy()`-сыз қызмет өлтірілсе батарея заряды ағады | **v0.7.1** |
| C6 | Stability | `VirtualDisplayClient.touch()` блокталмайтын жазу циклі + `channel` өрісі volatile емес — деректер жарысы | **v0.7.1** |

### Phase 2 — High (latency & stability)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| H1 | Perf | NIO `delay(1)` сұрауы әр оқуға 1-4ms кідіріс + бос кезде 1000 ояту/сек қосады. Selector немесе `runInterruptible` пайдалану | **v0.7.2** |
| H2 | Perf | Бір кадр жазуға екі жүйелік шақыру (6-байт тақырып + жүктеме). `GatheringByteChannel.write(ByteBuffer[])` пайдалану | **v0.8.0** |
| H3 | Perf | Бейне релесінде әр кадрға `ByteArray.copyOf()` (~10-100KB 30 аллокация/сек). offset+length беру | **v0.8.0** |
| H4 | Perf | `synchronized(outputLock)` бейне+жанасу+heartbeat сериализациялайды. Кілттік кадр жазуы жанасуды ~200ms блоктайды | **v0.7.4** |
| H5 | Stability | `Connection.connect()` бас тарту кезінде SocketChannel ағып кетеді — try/finally жоқ | **v0.7.1** |
| H6 | Stability | `disconnectListener` CAS ішінде синхронды шақырылады — ықтимал deadlock | **v0.7.1** |
| H7 | Stability | Көлік күй жалаулары (`wifiReady`, `usbReady`) volatile емес — CONNECTING күйінде тұрып қалуы мүмкін | **v0.7.2** |
| H8 | Stability | `VideoDecoder.stop()` `codec.stop()` алдында беру ағынын күтпейді — native crash қаупі | **v0.7.2** |
| H9 | Flow | Телефон желі кері шақыруы CONNECTED/STREAMING елемейді — хотспот ауысуы 10s қатып қалған кадр тудырады | **v0.7.3** |
| H10 | Flow | Handshake + auto-update + VD deploy барлығы жарысады — deployAssets аяқталмауы мүмкін, бір уақытта ADB операциялары | **v0.7.4** |
| H11 | Flow | 5-12s VD серверін іске қосу кезінде қолданушыға кері байланыс жоқ — көлік статикалық спиннер көрсетеді | **v0.7.4** |
| H12 | Flow | Көлік экранында нұсқаулықсыз телефондағы алғашқы USB ADB auth диалогы — 30s тайм-аут | **v0.7.4** |

### Phase 3 — Medium (noticeable issues)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| M1 | Perf | VD сервері localhost-та әр кадрдан кейін тазартады — қажетсіз жүйелік шақыру | **v0.8.0** |
| M2 | Perf | `checkStackEmpty()` команда оқу құралын 500ms блоктайды — Back кейін жанасу үзілісі | **v0.8.1** |
| M3 | Perf | Multi-touch MOVE үшін N бөлек кадр жібереді — барлық көрсеткіштерді пакеттеу керек | **v0.8.3** |
| M4 | Perf | MotionEvent PointerProperties/Coords әр енгізуге бөлінеді — пул пайдалану | **v0.8.1** |
| M5 | Perf | Декодер кадр кезегі 6 тереңдікте (200ms) — төмен кідіріс үшін 2-3 дейін азайту | **v0.8.1** |
| M6 | Perf | `execFast("cmd display power-off 0")` жанасу ағынында — таймерге жылжыту | **v0.8.1** |
| M7 | Stability | VD серверінде қос `cleanup()` — handleClient finally + run екеуі де шақырады | **v0.7.2** |
| M8 | Stability | `cleanupSession()` `_serviceState` қалпына келтірмейді — кідіріс кезінде ескірген UI | **v0.7.2** |
| M9 | Stability | Companion-дағы статикалық MutableStateFlow қызмет қайта іске қосылуынан аман қалады — ескірген activeConnection | **v0.7.2** |
| M10 | Flow | Қолданушы ажыратуы (eject) сақталмайды — процесс өлтірілгеннен кейін көлік қайта қосылады | **v0.8.3** |
| M11 | Flow | Көлік mDNS + gateway IP зонды бір реттік — мерзімді қайталау қажет | **v0.7.3** |
| M12 | Flow | checkStackEmpty-те `dumpsys activity` талдауы Android нұсқаларында нәзік | **v0.8.2** |

### Phase 4 — Low (polish)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| L1 | Perf | `TouchEvent.encode()` әр оқиғаға 25-байт ByteArray бөледі — асинхронды жазу кезегімен пул пайдалану мүмкін емес | WONTFIX |
| L2 | Perf | VD сервері localhost сокетінде жіберу/алу буфері өлшемі бапталмаған | **v0.8.2** |
| L3 | Perf | Бейне декодері тұрақты 33,333us уақыт белгісін пайдаланады — қабырға сағатын пайдалану керек | **v0.8.2** |
| L4 | Stability | NioReader тікелей ByteBuffer детерминді түрде босатылмайды | **v0.8.3** |
| L5 | Stability | `UsbAdbConnection.readFile()` толық оқуға кепілдік бермейді | **v0.8.2** |
| L6 | Stability | SurfaceScaler HandlerThread ешқашан аяқталмайды | **v0.8.2** |
| L7 | Flow | Қолданба белгішелері 48x48px — көлік дисплейлерінде бұлыңғыр, 96-128px қажет | **v0.8.2** |

---

## Белгілі мәселелер

| Issue | Impact | Status |
|-------|--------|--------|
| USB ADB auth dialog on replug | Phone asked "Allow USB debugging?" each time | **FIXED v0.13.1** — was double-hashing AUTH_TOKEN with SHA1withRSA. Now uses NONEwithRSA + prehashed SHA-1 DigestInfo. "Always allow" persists. |
| VD server dies on USB disconnect | Stream stops if USB unplugged | Accepted — `setsid`/`nohup` detachment broke localhost connectivity. Car re-deploys on reconnect. |
| Touch injection wakes physical display | Screen turns on briefly during interaction | Mitigated with throttled re-power-off (1s, on background thread) |
| Portrait apps letterboxed on landscape VD | Petal Maps home screen narrow | Android limitation |
| Hotspot must be enabled manually | User enables before plugging in | Android 16 limitation |

---

## Architecture (Current)

```
Phone (Xiaomi 17 Pro Max, HyperOS 3, Android 16)
├── DiLink Auto Client App
│   ├── ConnectionService (3-port accept: 9637/9638/9639)
│   │   ├── Control (9637): handshake, heartbeat, commands, data, car logs
│   │   ├── Video (9638): H.264 relay from VD server to car
│   │   ├── Input (9639): touch events from car, dispatched on Dispatchers.IO
│   │   ├── VD JAR deploy to /sdcard/DiLinkAuto/ (CRC32 checked)
│   │   ├── Car auto-update: sends UPDATING_CAR, then dadb push+install
│   │   ├── Smart network callback (ignores unrelated network drops)
│   │   ├── Battery exemption (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
│   │   └── FileLog: /sdcard/DiLinkAuto/client.log (rotation, 10 max)
│   ├── VirtualDisplayClient (videoConnection + controlConnection)
│   │   ├── startListening() — synchronous ServerSocket on localhost:19637
│   │   ├── acceptConnection() — NIO non-blocking accept
│   │   ├── NioReader (Selector-based, FRAME_INTERVAL_MS timeout)
│   │   └── Video relay via videoConnection, stack empty via controlConnection
│   └── NotificationService (captures phone notifications with progress)
│
├── VD Server (app_process, shell UID 2000)
│   ├── NIO write queue (ConcurrentLinkedQueue) + Selector-based command reader
│   ├── IInputManager injection (ServiceManager → injectInputEvent)
│   ├── Multi-touch: pre-allocated PointerProperties/Coords pools (10 slots)
│   ├── VirtualDisplay (TRUSTED + OWN_DISPLAY_GROUP + OWN_FOCUS)
│   ├── SurfaceScaler (EGL/GLES GPU downscale, skips GL work on idle, encoder repeat-previous-frame)
│   ├── H.264 encoder (8Mbps CBR, Main profile, configurable FPS, backpressure at 6 frames)
│   ├── Screen power-off (background thread, proximity/lift disabled)
│   └── Reverse NIO connection to phone on localhost:19637
│
Car (BYD DiLink 3.0, Android 10)
├── DiLink Auto Server App
│   ├── CarConnectionService — 3 connections + parallel USB track
│   │   ├── controlConnection (9637): heartbeat, commands, data
│   │   ├── videoConnection (9638): video frames → VideoDecoder
│   │   ├── inputConnection (9639): touch events from MirrorScreen
│   │   ├── Track B (USB): UsbAdbConnection with logSink → carLogSend
│   │   ├── UPDATING_CAR handling: shows status, skips reconnect
│   │   ├── Early decoder start: offscreen surface on first CONFIG
│   │   ├── carLogSend() + logSink callbacks → phone FileLog
│   │   └── Eject state persisted to SharedPreferences
│   ├── VideoDecoder (queue=15, early start, logSink, 4-zone catchup)
│   ├── PersistentNavBar (76dp, 40dp icons, 14sp text, recent apps pruned)
│   ├── LauncherScreen (64dp icons, 160dp grid, imePadding search)
│   ├── NotificationScreen (progress bars, tap-to-launch, dedup by ID)
│   └── MirrorScreen (TextureView + touch forwarding, decoder restart)
```

## Connection Flow

```
1. Phone app starts → deploys VD JAR, rotates FileLog, requests battery exemption
2. Phone plugged into car USB → car detects USB_DEVICE_ATTACHED
3. Track A (WiFi): car discovers phone via gateway IP (3s retry) or mDNS
4. Track B (USB): car connects USB ADB (logSink for diagnostics), launches phone app
5. Control (9637): TCP connect → handshake (viewport + DPI + version + targetFps)
6. Phone: checks version → if mismatch, sends UPDATING_CAR → auto-updates via dadb
7. Video (9638) + Input (9639): car connects in parallel after handshake
8. Phone: accepts both, opens VD ServerSocket on localhost:19637
9. USB: car starts VD server (shellNoWait + exec app_process, FPS as arg)
10. VD server: VD + SurfaceScaler (periodic re-draw) + encoder → NIO connect localhost:19637
11. Car: starts VideoDecoder on offscreen surface on first CONFIG frame
12. MirrorScreen shows → decoder restarts with real TextureView surface
13. Video: VD → SurfaceScaler → encoder → NIO write queue → localhost → phone NioReader → videoConnection → WiFi TCP → car NioReader → VideoDecoder → TextureView
14. Touch: car TextureView → inputConnection → WiFi TCP → phone (Dispatchers.IO) → VD server NIO Selector → IInputManager injection
15. Car logs: carLogSend() + logSink callbacks → DATA CAR_LOG → phone FileLog
```
