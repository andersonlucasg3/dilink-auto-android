# Трекер прогресу

Поточна версія: **v0.17.0-dev-02** (pre-release)
Останнє оновлення: 2026-05-01

## Етапи

### v0.17.0-dev-02 (2026-05-01)

- **Виправлення перегріву телефону**: Усунено шаблони активного очікування CPU у конвеєрі потокової передачі, що спричиняли перегрів телефону. Замінено busy-waits `delay(1)` на механізми на основі blocking/selector.
- **AppIconCache перенесено на бік автомобіля**: Кеш іконок на стороні автомобіля зберігає вихідні PNG (192x192) на диск. `prepareAll()` декодує+змінює розмір усіх іконок у фоновому потоці перед появою сітки; `getPrepared()` це O(1) пошук ConcurrentHashMap без I/O при прокрутці. Усунено декодування на плитку та падіння при швидкій прокрутці.
- **AppTile спрощено**: Видалено StateFlow collect на плитку, лінивий DropdownMenu та ефекти click ripple. Легкі плитки з clickable замість combinedClickable для основного натискання.
- **App grid дедублікація**: Виправлено падіння LazyGrid шляхом дедублікації елементів за packageName.

### v0.17.0-dev-01 (2026-04-30)

- **Закриття сповіщень по одному та Очистити всі**: Екран сповіщень автомобіля тепер має кнопки закриття по елементу з анімацією slide-out та кнопку "Очистити всі" у заголовку. Нові повідомлення протоколу: `NOTIFICATION_CLEAR` (0x04) та `NOTIFICATION_CLEAR_ALL` (0x05) на каналі даних. Іконки елементів з payload `iconPng` телефону.
- **Контекстні дії додатків**: Довге натискання на плитки додатків (лаунчер) та нещодавні додатки панелі навігації показує випадаюче меню з Видалити та Інформація про додаток. Поширення видалення через `APP_UNINSTALL` (0x1B) / `APP_UNINSTALLED` (0x06). Інформація про додаток показує діалог на стороні автомобіля з метаданими `APP_INFO_DATA` (0x07) від телефону. Дії контекстного меню проходять через VD-сервер для доступу на рівні shell.
- **Інфраструктура ярликів додатків** (відключено в UI): Повідомлення протоколу `APP_SHORTCUTS` (0x18) / `APP_SHORTCUTS_LIST` (0x19) / `APP_SHORTCUT_ACTION` (0x1A) із запитом через VD-сервер + APK XML fallback. Відключено до доопрацювання розрізнення міток (issue #57).
- **Виправлення кнопки назад**: GO_BACK тепер закриває активності по одній перед поверненням до головного меню, використовуючи відстеження стеку та повідомлення `FOCUSED_APP` (0x16).
- **DPI для Samsung DeX / Режим робочого столу** (скасовано): Початкова реалізація з виявленням `UiModeManager.currentModeType` та 213dpi була скасована в dev-02. Замінена підходом видалення прапора на рівні VD.

### v0.16.0 (2026-04-29)

- **Shizuku**: Додаток тепер з'являється в списку авторизованих додатків Shizuku (додано ShizukuProvider ContentProvider). Картка налаштувань відкриває додаток Shizuku безпосередньо для керування дозволами.
- **Виправлення Shizuku exec**: Виправлено EBADF від ParcelFileDescriptors у транзакціях binder шляхом дублювання FD перед читанням. `pm install` через Shizuku для безшумного самооновлення.
- **Режим Shizuku на автомобілі**: Підключення автомобіля більше не зависає на "Очікування WiFi" при активному Shizuku — цикл повторних спроб IP шлюзу більше не переривається після першої спроби.
- **Перевірку версій змінено на versionName**: Оновлення додатку авто тепер порівнюють рядки versionName (семантичне версіонування) замість цілих чисел versionCode, що дозволяє передрелізні оновлення авто.
- **Посилення безпеки**: Видалено невикористовувані дозволи `RECORD_AUDIO` та `SYSTEM_ALERT_WINDOW`. Служба доступності більше не слухає події `typeAllMask` (використовує лише `dispatchGesture`).
- **Продуктивність сітки додатків**: Виправлено збій при швидкій прокрутці на дисплеї авто. `GridCells.Adaptive` → `GridCells.Fixed` з обчислюваними колонками. Ліниве декодування bitmap на плитку з `inSampleSize=2` + `RGB_565`.
- **Стабільність мережі**: `NetworkCallback` на стороні телефону тепер фільтрується лише за `TRANSPORT_WIFI`, ігноруючи коливання мобільних даних 3G/4G.

### v0.15.0 (2026-04-28)

- **Phone service auto-start**: `ConnectionService` автоматично запускається при відкритті додатку телефону (наприклад, через USB ADB автомобіля), усуваючи необхідність ручного натискання Start. ✅ Done
- **Car no longer clears phone task**: Видалено `--activity-clear-task` із запуску телефону через USB ADB автомобіля. Якщо додаток телефону вже відкрито, автомобіль продовжує без його переривання. ✅ Done
- **Share Logs button**: Кнопка "Share Logs" на головному екрані архівує всі файли `*.log` з `/sdcard/DiLinkAuto/` та передає через Android share sheet. `FileLog.zipLogs()` створює `dilinkauto-logs.zip`. ✅ Done
- **Encoder configuration**: Налаштовано на 8Mbps CBR Main profile для ширшої сумісності пристроїв. Додано backpressure (відкидає неключові кадри, коли черга запису перевищує 6 кадрів). ✅ Done
- **VideoDecoder catchup**: Чотири зони прискорення (normal, gentle 1.5x, medium 2x, aggressive 3x) для більш плавного відновлення затримки. ✅ Done
- **French translation**: Додано французьку (fr) до існуючих 7 мов (тепер 8 всього). ✅ Done
- **Update check on app open**: Перевірка самооновлення запускається одразу при відкритті додатку, зі сповіщенням про оновлення та кнопкою повторної перевірки. ✅ Done
- **Distribution channel selector**: Картка налаштувань для вибору між stable releases та dev prereleases для самооновлення. ✅ Done
- **CarLaunchScreen redesign**: Двоколонковий макет, оптимізований для широких автомобільних дисплеїв. ✅ Done
- **Phone app UI refactor**: Реорганізовано головний екран, виправлено помилки потоку встановлення. ✅ Done
- **Onboarding improvements**: Передумови налаштування автомобіля, покращений UI прогресу встановлення, покращена картка How to Connect. ✅ Done
- **Car UI two-mode separation**: Екран запуску (повноекранний, орієнтований на з'єднання) та режим streaming (панель навігації + вміст). Плавний перехід при надходженні списку додатків. ✅ Done
- **Video artifact fixes**: Розумне скидання декодера + graduated catchup + backpressure кодувальника усувають візуальні артефакти. ✅ Done
- **Touch input fixes**: Коректне відображення координат при фіксованому 480dpi DPI VD-сервера, інкрементна відправка дотиків на MOVE, виправлення жесту натискання та ручного IP. ✅ Done
- **Screen restore and network stability**: Відновлення дисплея після відключення USB, покращення мережевого зворотного виклику. ✅ Done
- **Internationalization**: Усі нові рядки UI перекладено на 8 мов (en, pt-BR, ru, be, fr, kk, uk, uz). ✅ Done
- **CI/CD automation**: 6 виділених workflows — validation (`build.yml`, `build-develop.yml`), pre-release на `-dev` тегах (`build-pre-release.yml`), release на `vX.Y.Z` тегах (`build-release.yml`), зворотна синхронізація main→develop (`sync-main-to-develop.yml`), та автономний issue-agent (`issue-agent.yml`). ✅ Done

### v0.14.0

- **Shared version source**: Код/ім'я версії тепер у gradle.properties — одне редагування для обох додатків.
- **MAX_PAYLOAD_SIZE 2MB → 128MB**: Список додатків зі 136+ іконками PNG перевищував 2MB, викликаючи ProtocolException та скиди з'єднання.
- **Display restore fix**: `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` з `ACQUIRE_CAUSES_WAKEUP` відновлює дисплей після відключення USB, навіть коли VD-сервер помирає без очищення.
- **POCO F5 compatibility**: `FLAG_KEEP_SCREEN_ON` запобігає блокуванню екрану під час streaming. Ввід дотику підтверджено працює на POCO F5 з Xiaomi 17 Pro Max.
- **Car-side touch logging**: Події дотику MirrorScreen та успіх sendTouchEvent логуються для налагодження.
- **Developer credit in About**: "Developed with ❤" з посиланням на GitHub усіма 7 мовами.

### v0.13.1 — First Release (2026-04-26)

- **Onboarding flow**: Кероване налаштування дозволів при першому запуску (All Files, Battery, Accessibility, Notifications). Автоматично виявляє дозволи, резервне опитування для діалогових налаштувань.
- **Self-update (UpdateManager)**: Перевіряє GitHub Releases API, завантажує APK з прогресом, встановлює через системний встановник пакетів. 6-годинна перерва.
- **Main view reorganization**: Головний екран орієнтований на щоденне використання (посібник зі з'єднання, статус, start/stop, оновлення). Екран налаштувань з дозволами, встановленням на автомобіль, about та посиланнями для пожертв.
- **USB + WiFi install on car**: Паралельний сканер підмереж перевіряє всі 254 IP на ADB автомобіля. Комбіновано з ARP/neighbor/gateway discovery. USB host спробувано, але USB-A автомобіля лише host.
- **VD server now Kotlin Gradle module**: Залежить від :protocol та kotlinx-coroutines. Поділяє NioReader, FrameCodec.writeAll.
- **Performance**: Ліквідовано проміжне виділення ByteArray на кадр у кодувальнику. Початкова ємність NioReader 256KB. isKeyFrame кешується в FrameData.
- **Encoder**: CBR 8Mbps, Main profile, настроюваний FPS (за замовчуванням 30, автомобіль запитує 60), PRIORITY 0 (real-time). I_FRAME_INTERVAL=1s. `repeat-previous-frame-after`=500ms для статичного вмісту.
- **Donations**: GitHub Sponsors та Pix (Brazil) бейджі в README та налаштуваннях додатку.
- **Adaptive vector icon**: Силует автомобіля з бездротовими сигналами, застосовано до обох додатків.
- **Internationalization**: Рядкові ресурси English, Portuguese (pt-BR), Russian (ru), Belarusian (be), French (fr), Kazakh (kk), Ukrainian (uk), та Uzbek (uz).
- **Release signing**: Фіксований keystore з надійним паролем. CI збірки підписують release APK через GitHub Secrets.

### v0.13.0 — USB ADB Auth Fix (2026-04-25)

Знайдено та виправлено основну причину: `Signature.getInstance("SHA1withRSA")` двічі хешує AUTH_TOKEN ADB. 20-байтовий токен ADB — це попередньо хешоване значення — `RSA_sign(NID_sha1)` в AOSP розглядає його як уже хешоване. Тепер використовується `NONEwithRSA` з ручним додаванням префікса SHA-1 DigestInfo ASN.1 (попередньо хешоване підписання). "Always allow" тепер зберігається коректно — AUTH_SIGNATURE приймається при перепідключенні без діалогу.

### v0.13.0 — Display Power + Key Encoding (2026-04-25)

- **Display power via SurfaceControl (Android 14+)**: Завантажує `DisplayControl` з `/system/framework/services.jar` через `ClassLoaderFactory.createClassLoader()` + нативну бібліотеку `android_servers`. Резервний варіант: `cmd display power-off/on`, якщо reflection не спрацьовує.
- **Screen restore on disconnect**: `VirtualDisplayClient.disconnect()` телефону виконує `cmd display power-on 0` + `KEYCODE_WAKEUP` як мережу безпеки, коли процес VD-сервера вбито до очищення.
- **ADB key encoding rewrite**: Переписано `encodePublicKey()` у точній відповідності з еталоном AOSP — виправлено константи, явний `bigIntToLEPadded()`, логування заголовка struct.
- **Decoder catchup**: Коли черга перевищує `100ms * TARGET_FPS / 1000` кадрів (6 при 60fps), пропускає кожен другий неключовий кадр. Зображення рухається з 2x швидкістю, поступово наздоганяючи без ривків.
- **Car log buffer**: 200 → 10,000 повідомлень. Логи автентифікації USB ADB тепер зберігаються, поки control-з'єднання не очистить їх.

### v0.12.5 — Connection Stability (2026-04-24)

- **Smart network callback**: `onLost` тепер перевіряє, чи є втрачена мережа тією, що несе з'єднання. Ігнорує незв'язані втрати (циклування мобільних даних). Раніше будь-яка втрата мережі вбивала потокову сесію.
- **USB ADB auth diagnostics**: Повне логування потоку автентифікації, маршрутизоване через carLogSend → FileLog телефону. Виявило, що AUTH_SIGNATURE відхиляється кожного разу (adbd телефону не розпізнає збережений ключ). На дослідженні.
- **AUTH_RSAPUBLICKEY key preview logging**: Логує перші/останні байти публічного ключа, надісланого на телефон, для порівняння зі стандартним форматом ADB.

### v0.12.0–v0.12.4 — Bug Fixes & Polish (2026-04-24)

- **Touch input fixed**: `handleInputFrame` обробляється на `Dispatchers.IO` (було Main, викликало `NetworkOnMainThreadException` при записі на localhost socket)
- **VD server NIO command reader**: Виправлено нескінченний цикл — `break` всередині switch лише виходив зі switch, не з циклу розбору. Тепер використовується `break parseLoop;` з позначеним виходом.
- **App launch dedup**: Видалено `--activity-clear-task` з `am start`. Існуючі додатки відновлюються замість перезапуску.
- **Bitrate**: Встановлено 8Mbps CBR (змінено з 12Mbps у пізніших випусках для сумісності пристроїв).
- **FPS configurable**: Додано поле `targetFps` до HandshakeRequest. Автомобіль запитує 60fps. VD-сервер приймає FPS як аргумент командного рядка, використовує його для `KEY_FRAME_RATE` кодувальника та `FRAME_INTERVAL_MS`.
- **Nav bar**: 72dp → 76dp, іконки 32dp → 40dp, висота рядка 52dp → 60dp, текст 12sp → 14sp.
- **Launcher app icons**: 40dp → 64dp, комірки сітки 140dp → 160dp, текст bodyMedium → bodyLarge.
- **Search bar keyboard**: `windowSoftInputMode="adjustNothing"` + `imePadding()` на TextField. Клавіатура не штовхає activity, рухається лише рядок пошуку.
- **Notifications**: Дедублікація за ID (оновлення прогресу замінюють існуючі), підтримка смуги прогресу (determinate + indeterminate), запуск по дотику додатку-власника на VD + перемикання на дзеркальний вигляд.
- **Recent apps**: `pruneUnavailable()` видаляє додатки, яких більше немає на телефоні при оновленні списку.
- **USB ADB key storage**: Пріоритетний порядок: `/sdcard/DiLinkAuto/` → `getExternalFilesDir` → `getFilesDir`. Міграція шукає в усіх місцях.
- **Update flow**: Телефон надсилає повідомлення `UPDATING_CAR`. Автомобіль показує "Updating car app..." статус і не перепідключається.
- **Update flow crash fix**: Автомобіль пропускає відео/вхідні з'єднання, коли встановлено прапор `updatingFromPhone`.
- **VideoDecoder/UsbAdbConnection logSink**: Логи з боку автомобіля маршрутизуються через протокол до FileLog телефону.

### v0.11.0–v0.11.3 — Non-Blocking Pipeline + Encoder Fix (2026-04-24)

- **VideoConfig**: Спільні константи `TARGET_FPS` та `FRAME_INTERVAL_MS`. Усі очікування/опитування відео-шляху обмежені інтервалом кадру.
- **SurfaceScaler periodic re-draw**: Завжди викликає `glDrawArrays + eglSwapBuffers` кожен інтервал кадру, навіть коли немає нового кадру від VD. Викликає `updateTexImage` лише при наявності нового кадру. Живить кодувальник на статичному вмісті.
- **VD server NIO**: Замінено блокуючий `DataOutputStream/DataInputStream` на NIO чергу запису (`ConcurrentLinkedQueue<ByteBuffer>`) + читач команд на основі Selector. Жодного блокуючого I/O ніде в конвеєрі.
- **Encoder poll**: `dequeueOutputBuffer` timeout зменшено зі 100ms до `FRAME_INTERVAL_MS` (16ms при 60fps).
- **VideoDecoder queue poll**: 100ms → `FRAME_INTERVAL_MS`.
- **NioReader select timeout**: 100ms → `FRAME_INTERVAL_MS` (настроюваний через параметр конструктора).
- **Connection writer park**: 50ms → `FRAME_INTERVAL_MS`.
- **VirtualDisplayClient accept loop**: 100ms → `FRAME_INTERVAL_MS`.
- **VideoDecoder early start**: Запускається на позаекранному SurfaceTexture при надходженні першого кадру CONFIG (перед MirrorScreen). MirrorScreen перезапускає декодер з реальною поверхнею TextureView.
- **VideoDecoder queue**: 3 → 30 кадрів. Кадри ставляться в чергу навіть до виклику `start()`.
- **FileLog**: Файловий логер (`/sdcard/DiLinkAuto/client.log`) обходить фільтрацію logcat у HyperOS. Ротація: архівує як `client-YYYYMMDD-HHmmss.log`, зберігає макс. 10.

### v0.10.0 — 3-Connection Architecture (2026-04-24)

Єдине мультиплексоване TCP-з'єднання розділено на 3 виділені з'єднання для усунення міжканальної інтерференції, що викликала зупинки відео:
- **Control connection** (порт 9637): handshake, heartbeat, команди додатків, DATA channel
- **Video connection** (порт 9638): лише H.264 CONFIG + FRAME (phone → car)
- **Input connection** (порт 9639): лише події дотику (car → phone)

Кожне з'єднання має власний екземпляр `Connection` з незалежними SocketChannel, NioReader та чергою запису. Heartbeat/watchdog лише на control.

### v0.9.2 — Diagnostic Build (2026-04-23)

Всеосяжне логування для дослідження зупинки відеокадрів після ~420 кадрів:
- **Video relay loop**: логує до/після readByte, розмір payload, невідомі msgTypes
- **NioReader**: логує, коли channel.read() повертає 0 (кожне 100-те входження зі станом буфера)
- **Connection writer**: логує кожні 60 відеокадрів (кількість, розмір, глибина черги, зупинки), логує зупинки запису
- **Writer stall fix**: `Thread.yield()` → `delay(1)` у writeBuffersToChannel — звільняє потік IO назад до пулу корутин замість busy-waiting (висновок дослідження: Thread.yield морив голодом корутину ретрансляції відео)
- **Frame listeners**: обробники не-відео кадрів обробляються асинхронно (`scope.launch`), щоб важка обробка (декодування списку додатків) не блокувала читач від спустошення TCP

### v0.9.0-v0.9.1 — Write Stall Investigation (2026-04-23)

Дослідження основної причини зупинки відео. Додано логування розміру буфера TCP, діагностику зупинок запису.
- Підтверджено, що буфер відправки TCP зависає на 108,916 байт залишку під час відправки списку додатків
- Підтверджено, що самі відеокадри мають нуль зупинок запису (queue=0 під час відео)
- Підтверджено, що ключ USB ADB стабільний (LOADED fp=c4e88a05) — повторна автентифікація є поведінкою HyperOS

### v0.8.4-v0.8.8 — Bug Fixes + Log Routing (2026-04-23)

- **Car log routing**: Усі виклики `Log.*` з боку автомобіля маршрутизуються через `carLogSend()`, яка надсилає через DATA-канал `CAR_LOG` на телефон. Телефон логує з тегом `CarLog` у logcat. Буфер до 200 повідомлень до встановлення з'єднання.
- **VD server launch reverted** до `shellNoWait` + `exec app_process` (підхід v0.6.2). Відділення `setsid`/`nohup` порушило зв'язність localhost. VD-сервер помирає при відключенні USB, але відновлюється при повторному підключенні.
- **VD ServerSocket**: `startListening()` відкривається синхронно, `waitForVDServer()` пропускає, якщо вже очікує
- **USB ADB key persistence**: `getExternalFilesDir` з перевіркою записуваності + міграція з `getFilesDir` + логування відбитків
- **ClosedSelectorException**: перевірки `selector.isOpen` у writer та NioReader
- **Infinite recursion fix**: масова заміна Log→carLogSend випадково зачепила саму carLogSend

### v0.8.3 — Final Polish + VD Wait Fix + USB Key Diagnostic (2026-04-23)

Останній етап + виправлення помилок:
- **VD wait guard**: `waitForVDServer` пропускає, якщо вже очікує — запобігає закриттю/перевідкриттю ServerSocket, коли автомобіль надсилає кілька handshake під час перепідключення (основна причина неможливості підключення VD-сервера, підтверджено через logcat телефону, що показує 4x `startListening` за 45s)
- **USB key diagnostic**: Автомобіль пише інформацію про ключ (`LOADED`/`GENERATED` + відбиток + шлях) до `/data/local/tmp/car-adb-key.log` на телефоні після USB ADB connect. Дозволяє діагностувати, чи повторюється автентифікація через зміну ключа, чи телефон не зберігає "Always allow."
- **M3**: Пакетний multi-touch — новий тип повідомлення `TOUCH_MOVE_BATCH` (0x04) містить усі вказівники в одному кадрі. Події MOVE пакетуються на боці автомобіля, розпактовуються на боці телефону. Скорочує кількість системних викликів з N*60/sec до 60/sec для N-пальцевих жестів.
- **M10**: Стан Eject зберігається в SharedPreferences — переживає вбивство/перезапуск додатку автомобіля. Очищається при повторному підключенні USB або ACTION_START.
- **L4**: NioReader використовує heap ByteBuffer замість direct — детерміноване очищення GC.

### v0.8.2 — Polish + VD ServerSocket + USB Key Persistence (2026-04-23)

6 поліпшень + 2 критичні виправлення:
- **Hotfix**: VirtualDisplayClient розділено на `startListening()` (синхронне зв'язування) + `acceptConnection()` (асинхронне очікування). ServerSocket відкривається ПЕРЕД відповіддю handshake — виправляє неможливість підключення VD-сервера на localhost:19637.
- **USB key persistence**: Зберігання ключів використовує `getExternalFilesDir` з перевіркою записуваності + міграція з `getFilesDir`. Логування відбитків при кожному з'єднанні для діагностики зміни ключа між з'єднаннями.
- **M12**: `checkStackEmpty` використовує простіший `grep -E` замість крихкого розбору секцій `sed`
- **L2**: Буфери сокета localhost VD-сервера встановлено на 256KB
- **L3**: Декодер використовує `System.nanoTime()/1000` для timestamp (було фіксоване збільшення 33ms)
- **L5**: `UsbAdbConnection.readFile()` використовує цикл читання + try-with-resources
- **L6**: HandlerThread SurfaceScaler коректно завершується при зупинці
- **L7**: Іконки додатків збільшено з 48x48 до 96x96px

### v0.8.1 — Touch + Decoder Performance + Hotfixes (2026-04-23)

4 оптимізації продуктивності + 2 виправлення крахів:
- **M2**: `checkStackEmpty()` працює у фоновому потоці — читач команд більше не блокується на 300ms+ після натискання Back
- **M4**: Попередньо виділені пули `PointerProperties[10]` + `PointerCoords[10]` у VD-сервері — усуває тиск GC на кожен дотик
- **M5**: Черга кадрів декодера зменшена з 6 до 3 (200ms → 100ms межа затримки)
- **M6**: `cmd display power-off 0` працює в потоці "fire-and-forget" — прибрано тремтіння зі шляху ін'єкції дотику
- **Crash fix**: `ClosedSelectorException` у Connection writer + NioReader — додано перевірки `selector.isOpen` та блок catch. Гонка: `disconnect()` закриває селектори, поки корутини читача/письменника ще виконуються.
- **Bug fix**: Скидання `usbConnecting` у `startConnection()` тепер також захищене `usbAdb == null` (друге місце гонки USB auth, перше було виправлено в v0.7.3)

### v0.8.0 — I/O Pipeline Performance (2026-04-23)

3 оптимізації продуктивності I/O + hotfix:
- **H2**: Gathering writes — `channel.write(ByteBuffer[])` об'єднує заголовок+payload в один системний виклик/сегмент TCP
- **H3**: Video relay виділяє `ByteArray(size)` точного розміру безпосередньо — видаляє проміжний `relayBuf` + `copyOf`
- **M1**: VD-сервер обгортає DataOutputStream у `BufferedOutputStream(65536)` — об'єднує малі записи localhost
- **Hotfix**: `waitForVDServer()` викликається ПЕРЕД відправкою відповіді handshake — гарантує, що ServerSocket на :19637 відкритий до того, як автомобіль розгорне VD-сервер (регресія v0.7.4: послідовність ставила VD wait після відповіді, викликаючи збій підключення VD-сервера)

### v0.7.4 — Write Queue + Flow Sequencing (2026-04-23)

Зміна архітектури запису + поліпшення потоку:
- **Write queue**: Замінено `synchronized(outputLock)` на безблокуючу `ConcurrentLinkedQueue` + виділену корутину запису. Письменник використовує `delay(1)`, коли буфер відправки TCP заповнений (звільняє потік IO у пул). Більше жодного блокування інших корутин під час запису.
- **H10**: Handshake → auto-update → VD deploy послідовно. Auto-update призупиняє ініціалізацію, відключає, чекає перепідключення автомобіля.
- **H11**: Прогресивні статусні повідомлення автомобіля: "Preparing..." → "Starting..." → "Waiting for video stream..."
- **H12**: Автомобіль показує "Check phone for authorization dialog" під час USB ADB connect
- **Bug fix**: VD-сервер запускає home activity на VD після створення — кодувальник одразу отримує вміст
- **Bug fix**: `usbConnecting` скидається лише коли `usbAdb == null` — запобігає дублюванню діалогів USB-ADB auth

### v0.7.3 — Network Resilience + HyperOS Freeze Fix (2026-04-23)

Стійкість мережі + критичне виправлення HyperOS, виявлене через докази logcat:
- **Battery exemption**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — запобігає "greeze" HyperOS від заморожування клієнтського додатку, поки екран вимкнений. Основна причина помилки "frames only during touch": VD-сервер (shell process) виробляв 960+ кадрів, але NioReader клієнтського додатку був заморожений керуванням живленням OS. Запит показується при першому запуску.
- **C4/M11**: WiFi трек автомобіля тепер повторює gateway IP кожні 3s (було одноразово). Додано `ConnectivityManager.NetworkCallback` на автомобілі, який повторно запускає WiFi трек, коли WiFi стає доступним. Обробляє hotspot, увімкнений після підключення USB.
- **H9**: Телефон активно відключає при втраті мережі в стані CONNECTED/STREAMING (було: очікування 10s для тайм-ауту heartbeat). `onLost` → `cleanupSession()` → цикл прослуховування перезапускається. `onAvailable` скидає лише в WAITING (без порушення активних з'єднань).
- **Bug fix**: VD-сервер запускає home activity на VD після створення (`am start --display <id> HOME`) — гарантує, що кодувальник одразу має вміст.
- **Bug fix**: `usbConnecting` скидається лише коли `usbAdb == null` — запобігає дублюванню діалогів USB-ADB auth.

### v0.7.2 — Car-Side Stability + Selector Fix (2026-04-23)

5 виправлень стабільності + критичне виправлення NioReader:
- **H1**: Замінено опитування `delay(1)` на **Selector** у NioReader — `selector.select(100)` прокидається миттєво через epoll при надходженні даних. Виправляє неструменеве відео (кадри йшли лише під час дотику на Android 10 автомобіля через те, що `delay(1)` займала 10-16ms). Додано `wakeup()`/`close()` для чистого завершення з disconnect.
- **H7**: `@Volatile` на `wifiReady`, `usbReady`, `vdServerStarted`, `usbConnecting` — запобігає застряганню в стані CONNECTING
- **H8**: `VideoDecoder.stop()` чекає потік подачі (тайм-аут 2s) перед `codec.stop()` — запобігає native crash
- **M7**: Видалено подвійну `cleanup()` у VD-сервері — запобігає IllegalStateException при подвійному звільненні
- **M8**: `cleanupSession()` скидає `_serviceState` у WAITING — запобігає застарілому UI під час затримки перепідключення
- **M9**: `onCreate()` очищає статичні `activeConnection` та `_serviceState` — запобігає застарілому стану при перезапуску сервісу

### v0.7.1 — Critical Bug Fixes (2026-04-23)

6 критичних/високих виправлень зі всеосяжного огляду:
- **C1**: `writeAll()` 5s тайм-аут запису — запобігає зависанню системи на заповненому буфері відправки
- **C3**: Прапор спроби автооновлення — розриває нескінченний цикл оновлення/перезапуску
- **C5**: WakeLock 4h авто-звільнення — запобігає розряду батареї при аварійному виході
- **C6**: `@Volatile` на VirtualDisplayClient channel/reader + записи з захистом тайм-аутом
- **H5**: `Connection.connect()` try/catch — закриває SocketChannel при скасуванні
- **H6**: Слухач відключення обгорнуто в try/catch — запобігає поширенню виключення

### v0.7.0 — Full NIO + Service Fix (2026-04-23)

Усі операції з сокетами переведено на неблокуючий NIO. Реєстрація mDNS більше не блокує цикл прослуховування. Код версії читається під час виконання через PackageManager.

**Changes:**
- **NioReader**: Новий неблокуючий буферизований читач для SocketChannel (опитування delay(1), корутинно-кооперативний)
- **Connection.kt**: SocketChannel залишається неблокуючим протягом усього часу — більше немає configureBlocking(true) після connect/accept
- **FrameCodec.kt**: Додано методи NIO `readFrame(NioReader)` та `writeFrameToChannel(SocketChannel, Frame)`
- **VirtualDisplayClient.kt**: NIO читання (NioReader) + записи ByteBuffer, канал залишається неблокуючим
- **VirtualDisplayServer.java**: NIO SocketChannel для connect (неблокуючий finishConnect з повторенням)
- **ConnectionService.kt**: probePort() переведено на NIO; реєстрація mDNS запускається у фоні з тайм-аутом 5s (виправляє незапуск сервісу без WiFi)
- **Version code**: Видалено константу `APP_VERSION_CODE` — обидва додатки читають versionCode під час виконання через `PackageManager.getPackageInfo()`

### v0.6.2 — Parallel Connection Model + Auto-Update (2026-04-23)

Велика переробка архітектури: паралельні треки WiFi + USB, неблокуючі сокети NIO, автооновлення через телефон, multi-touch через IInputManager.

**Working:**
- **Parallel connection state machine**: WiFi discovery + USB ADB працюють одночасно, VD розгортається, коли обидва готові
- **Auto-update**: Телефон виявляє застарілий додаток автомобіля при handshake, надсилає оновлення через WiFi ADB (dadb)
- **Phone deploys VD JAR**: Видобувається до `/sdcard/DiLinkAuto/vd-server.jar` при запуску (перевіряється CRC32)
- **Car APK embedded in phone**: Система збірки компілює APK автомобіля в assets телефону
- **NIO non-blocking sockets**: Усі accept/connect використовують `ServerSocketChannel`/`SocketChannel` — миттєве скасування, без EADDRINUSE
- **Multi-touch input**: Пряма ін'єкція MotionEvent через `ServiceManager → IInputManager` (підтримує tap, swipe, pinch)
- **Screen power management**: `cmd display power-off 0` під час streaming, proximity/lift wake вимкнено, обмежене повторне вимкнення після ін'єкції дотику
- **State machine recovery**: `connectionScope` скасовує всі корутини при відключенні, експоненційне перепідключення з backoff
- **User disconnect**: Кнопка Eject зупиняє перепідключення (залишається IDLE)
- **App search**: Поле пошуку внизу сітки додатків, додатки відсортовані за алфавітом
- **Notification panel**: Іконка дзвоника в панелі навігації з лічильником
- **72dp nav bar**: Більші іконки (32dp) та текст (12sp) для автомобільних дисплеїв
- **Handshake version check**: Поле `appVersionCode` у HandshakeRequest, `vdServerJarPath` у HandshakeResponse
- **Network change handling**: Телефон перезапускає цикл прослуховування при змінах мережевого інтерфейсу (перемикання hotspot)
- **H.264 encoding**: 8Mbps CBR, High profile, режим низької затримки
- **Handshake timeout**: 10s тайм-аут з коректним скасуванням (без застарілих тайм-аутів)

**Architecture changes from v0.5.0:**
- Видалено опитування SSID hotspot/автопідключення WiFi з автомобіля (спрощено)
- Перенесено UsbAdbConnection + AdbProtocol до модуля protocol (спільний для обох додатків)
- VD-сервер підключається ДО телефону (зворотне з'єднання) замість підключення телефону до VD-сервера
- VD-сервер завершується при відключенні телефону (одноразовий, автомобіль повторно розгортає при необхідності)
- Телефон видобуває VD JAR до спільного сховища, автомобіль читає шлях з handshake

### v0.5.0 — USB ADB + Automated Setup (2026-04-22)

Велика зміна архітектури: **автомобіль** розгортає VD-сервер на телефон через USB ADB. Wireless Debugging усунено.

### v0.4.0 — GPU-Scaled VirtualDisplay (2026-04-22)

Додатки рендеряться при рідній щільності телефону (480dpi), GPU масштабує до вікна автомобіля. Конвеєр SurfaceScaler EGL/GLES.

### v0.3.0 — Persistent Navigation Bar (2026-04-21)

UI автомобіля з завжди видимою лівою панеллю навігації, TextureView, реальні іконки додатків.

### v0.2.0–v0.2.3 — Virtual Display Foundation (2026-04-21)

Створення VD, self-ADB, стійкий сервер, підтримка кількох додатків.

### v0.1.0–v0.1.1 — Initial Implementation (2026-04-21)

Проект створено. Дзеркальне відображення екрану на емуляторах.

---

## Fix Tracker

Всеосяжний огляд виконано 2026-04-23, охоплюючи продуктивність, стабільність та безперервність потоку.

### Phase 1 — Critical (fix before next release)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| C1 | Stability/Perf | `writeAll()` крутиться нескінченно на заповненому буфері відправки — немає тайм-ауту, тримає `outputLock`, блокує всіх відправників. Ризик зависання системи | **v0.7.1** |
| C2 | Flow | VD-сервер помирає при відключенні USB (`shellNoWait` зв'язує процес з потоком ADB). Повне перепідключення 5-15s | REVERTED — `setsid`/`nohup` порушили localhost. Використовується `shellNoWait`+`exec` (підхід v0.6.2). Перепідключається при повторному підключенні. |
| C3 | Flow | Auto-update не має break-циклу — якщо `pm install` тихо збоїть, нескінченний цикл перезапуску | **v0.7.1** |
| C4 | Flow | WiFi трек автомобіля працює один раз і здається — hotspot увімкнений після підключення USB → назавжди застрягає | **v0.7.3** |
| C5 | Stability | WakeLock захоплений без тайм-ауту — розряд батареї, якщо сервіс вбито без `onDestroy()` | **v0.7.1** |
| C6 | Stability | `VirtualDisplayClient.touch()` неблокуючий цикл запису + поле `channel` не volatile — гонка даних | **v0.7.1** |

### Phase 2 — High (latency & stability)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| H1 | Perf | Опитування NIO `delay(1)` додає 1-4ms мінімальної затримки на читання + 1000 пробуджень/сек у бездіяльності. Використовувати Selector або `runInterruptible` | **v0.7.2** |
| H2 | Perf | Два системні виклики на запис кадру (6-байтовий заголовок + payload). Використовувати `GatheringByteChannel.write(ByteBuffer[])` | **v0.8.0** |
| H3 | Perf | `ByteArray.copyOf()` на кожен кадр у ретрансляції відео (~30 алокацій/сек по 10-100KB). Передавати offset+length | **v0.8.0** |
| H4 | Perf | `synchronized(outputLock)` серіалізує відео+дотик+heartbeat. Запис ключового кадру блокує дотик ~200ms | **v0.7.4** |
| H5 | Stability | `Connection.connect()` витікає SocketChannel при скасуванні — немає try/finally | **v0.7.1** |
| H6 | Stability | `disconnectListener` викликається синхронно в CAS — потенційний deadlock | **v0.7.1** |
| H7 | Stability | Прапори стану автомобіля (`wifiReady`, `usbReady`) не volatile — можуть застрягнути в CONNECTING | **v0.7.2** |
| H8 | Stability | `VideoDecoder.stop()` не чекає потік подачі перед `codec.stop()` — ризик native crash | **v0.7.2** |
| H9 | Flow | Мережевий зворотний виклик телефону ігнорує CONNECTED/STREAMING — перемикання hotspot викликає 10s заморожений кадр | **v0.7.3** |
| H10 | Flow | Handshake + auto-update + VD deploy усі змагаються — deployAssets може бути не завершена, одночасні операції ADB | **v0.7.4** |
| H11 | Flow | Немає зворотного зв'язку для користувача під час 5-12s запуску VD-сервера — автомобіль показує статичний спіннер | **v0.7.4** |
| H12 | Flow | Діалог першої автентифікації USB ADB на телефоні без вказівок на екрані автомобіля — 30s тайм-аут | **v0.7.4** |

### Phase 3 — Medium (noticeable issues)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| M1 | Perf | VD-сервер скидає після кожного кадру на localhost — непотрібний системний виклик | **v0.8.0** |
| M2 | Perf | `checkStackEmpty()` блокує читач команд на 500ms — відсутність дотику після Back | **v0.8.1** |
| M3 | Perf | Multi-touch надсилає N окремих кадрів за MOVE — потрібно пакетувати всі вказівники | **v0.8.3** |
| M4 | Perf | PointerProperties/Coords MotionEvent виділяються при кожній ін'єкції — використовувати пул | **v0.8.1** |
| M5 | Perf | Черга кадрів декодера глибиною 6 (200ms) — зменшити до 2-3 для меншої затримки | **v0.8.1** |
| M6 | Perf | `execFast("cmd display power-off 0")` у потоці дотику — перемістити на таймер | **v0.8.1** |
| M7 | Stability | Подвійна `cleanup()` у VD-сервері — handleClient finally + run викликають обидва | **v0.7.2** |
| M8 | Stability | `cleanupSession()` не скидає `_serviceState` — застарілий UI під час затримки | **v0.7.2** |
| M9 | Stability | Статичний MutableStateFlow у companion переживає перезапуски сервісу — застарілий activeConnection | **v0.7.2** |
| M10 | Flow | Відключення користувача (eject) не зберігається — автомобіль перепідключається після вбивства процесу | **v0.8.3** |
| M11 | Flow | mDNS автомобіля + gateway IP probe одноразові — потрібен періодичний повтор | **v0.7.3** |
| M12 | Flow | Розбір `dumpsys activity` у checkStackEmpty крихкий на різних версіях Android | **v0.8.2** |

### Phase 4 — Low (polish)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| L1 | Perf | `TouchEvent.encode()` виділяє 25-байтовий ByteArray на подію — не можна використовувати пул з асинхронною чергою запису | WONTFIX |
| L2 | Perf | Сокет localhost VD-сервера без налаштування розміру буфера відправки/отримання | **v0.8.2** |
| L3 | Perf | Відеодекодер використовує фіксований timestamp 33,333us — повинен використовувати системний годинник | **v0.8.2** |
| L4 | Stability | NioReader direct ByteBuffer не звільняється детерміновано | **v0.8.3** |
| L5 | Stability | `UsbAdbConnection.readFile()` не гарантує повне читання | **v0.8.2** |
| L6 | Stability | SurfaceScaler HandlerThread ніколи не завершується | **v0.8.2** |
| L7 | Flow | Іконки додатків 48x48px — розмиті на автомобільних дисплеях, потрібно 96-128px | **v0.8.2** |

---

## Known Issues

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
