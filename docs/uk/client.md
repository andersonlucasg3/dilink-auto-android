# Клієнтський додаток телефону (app-client)

## Огляд

Клієнт телефону керує розгортанням VD-сервера, автооновленням автомобіля та ретрансляцією 3 з'єднань. Додаток телефону:

1. Слухає TCP-з'єднання від автомобіля на порту 9637 (control, NIO ServerSocketChannel)
2. Відповідає на handshake інформацією про пристрій, vdServerJarPath та читає `targetFps`
3. Перевіряє appVersionCode з handshake — надсилає `UPDATING_CAR` та автоматично оновлює додаток автомобіля через dadb, якщо версії не збігаються
4. Приймає відео (9638) та вхідні (9639) з'єднання від автомобіля після handshake
5. Розгортає vd-server.jar до `/sdcard/DiLinkAuto/` та запускає VD-сервер (з аргументом FPS)
6. Приймає зворотне з'єднання від VD-сервера на localhost:19637 (NIO ServerSocketChannel)
7. Передає H.264 відео від VD-сервера до автомобіля через відео-з'єднання
8. Передає події дотику від автомобіля (вхідне з'єднання) до VD-сервера, обробляються на `Dispatchers.IO`

**Жодного захоплення екрану. Жодного MediaProjection.** Усе відео надходить від процесу VD-сервера.

## Компоненти

### ClientApp

Клас Application. Створює канали сповіщень (`dilinkauto_service`, `dilinkauto_update`), ініціалізує `UpdateManager` при створенні.

### UpdateManager

Механізм самооновлення, який перевіряє GitHub Releases на нові версії.
- `checkForUpdate(force)`: Запитує `https://api.github.com/repos/andersonlucasg3/dilink-auto-android/releases/latest`, порівнює semver з імені тегу зі встановленою versionName. Дотримується 6-годинної перерви, якщо не примусово.
- `downloadUpdate()`: Завантажує APK через `HttpsURLConnection` з відображенням прогресу. Перевіряє через `PackageManager.getPackageArchiveInfo()`.
- `installUpdate(context)`: Відкриває системний встановник пакетів через URI `FileProvider`.
- Стани: Idle, Checking, Available, Downloading, ReadyToInstall, UpToDate, Error. Представлені через `StateFlow`.

### MainActivity

Точка входу з двома екранами:

- **OnboardingScreen** (перший запуск): 7-кроковий майстер — Welcome, All Files Access, Battery Optimization, Accessibility Service, Notification Access, Car Setup, Done. Кожен крок пояснює, що не працює без відповідного дозволу. Автоматично переходить далі, коли дозвіл надано. Користувач може пропустити будь-який крок.
- **ClientScreen** (наступні запуски): картка статусу, кнопка start/stop, Install on Car, картка самооновлення, кнопка Share Logs та залишковий статус дозволів для всього, пропущеного під час onboarding.

### ConnectionService

Сервіс переднього плану, який керує життєвим циклом з'єднання телефон-автомобіль з 3 виділеними з'єднаннями. Автоматично запускається при відкритті додатку телефону (наприклад, через USB ADB автомобіля).

- **Control connection** (порт 9637): NIO TCP-сервер на `0.0.0.0`, обробляє handshake, heartbeat, команди додатків, канал DATA
- **Video connection** (порт 9638): приймається після handshake, передається VirtualDisplayClient для ретрансляції відео
- **Input connection** (порт 9639): приймається після handshake, слухач кадрів INPUT обробляється на `Dispatchers.IO` для уникнення NetworkOnMainThreadException при записі дотиків на localhost
- `deployAssets()`: видобуває vd-server.jar на sdcard, app-server.apk у filesDir
- Виявляє невідповідність версій → надсилає `UPDATING_CAR` → автоматично оновлює додаток автомобіля через dadb (WiFi ADB, dadb 1.2.10)
- Розумний мережевий зворотний виклик: `onLost` перевіряє, чи є втрачена мережа тією, яку використовує з'єднання, ігнорує незв'язані втрати (циклування мобільних даних)
- Передає відеокадри (H.264 CONFIG + FRAME) від VD до автомобіля через відео-з'єднання
- Маршрутизує події дотику від автомобіля (вхідне з'єднання) до VD-сервера
- Надсилає список додатків з іконками 96x96 PNG до автомобіля через control-з'єднання
- Обробляє команди LAUNCH_APP, GO_BACK, GO_HOME та пересилає їх VD-серверу
- Ставить у чергу запуски додатків, якщо VD-сервер ще не підключений
- Реєструє сервіс mDNS для автоматичного виявлення автомобілем
- `FileLog.rotate()` при старті сервісу — архівує лог попередньої сесії
- Кнопка "Install on Car": ручне встановлення + автоматичне при невідповідності версій handshake

### VirtualDisplayClient

Приймає зворотне з'єднання від процесу VD-сервера на `localhost:19637`. Приймає два параметри Connection: `videoConnection` та `controlConnection`.

- NIO ServerSocketChannel accept (неблокуючий) — VD-сервер підключається ДО телефону
- Читає: `MSG_VIDEO_CONFIG`, `MSG_VIDEO_FRAME`, `MSG_STACK_EMPTY`, `MSG_DISPLAY_READY`
- Пише: `CMD_LAUNCH_APP`, `CMD_GO_BACK`, `CMD_GO_HOME`, `CMD_INPUT_TOUCH` (0x32)
- Відеокадри передаються через `videoConnection.sendVideo()` (ізольовано від контрольного трафіку)
- Сигнал порожнього стеку (`MSG_STACK_EMPTY`) пересилається автомобілю через `controlConnection.sendControl()`
- Записи дотиків на localhost синхронні з `FrameCodec.writeAll()` під `writeLock`
- При відключенні: відновлює фізичний дисплей (`cmd display power-on 0` + `KEYCODE_WAKEUP`) як мережу безпеки, коли процес VD-сервера вбито до очищення

### AdbBridge

Резервний помічник shell-команд. Надає `execShell()` та `execFast()` з використанням `Runtime.exec()` для операцій VD-сервера та керування живленням дисплея, коли прямий API reflection не спрацьовує.

### VirtualDisplayManager

Керує запуском додатків на фізичному дисплеї, коли VD не використовується. З'єднує з `InputInjectionService` для ін'єкції вводу на основі жестів.

### VideoEncoder

MediaProjection + MediaCodec H.264 кодувальник з використанням віртуального дисплея `AUTO_MIRROR`. Альтернативний шлях кодування (не використовується в основному потоковому конвеєрі, який проходить через VD-сервер).

### FileLog

Файловий логер, який обходить фільтрацію Android logcat (HyperOS фільтрує `Log.i/d` для не-системних додатків).

- Пише до `/sdcard/DiLinkAuto/client.log`
- `rotate()`: архівує поточний лог як `client-YYYYMMDD-HHmmss.log`, починає новий
- `zipLogs()`: створює `dilinkauto-logs.zip` з усіх файлів `.log` для обміну
- Зберігає максимум 10 логів (9 архівних + поточний)
- Потоково-безпечний: ConcurrentLinkedQueue без блокувань, що спустошується потоком запису
- Також викликає `android.util.Log.*` для стандартного виводу logcat

### Multi-Touch Relay

Події дотику надходять від автомобіля через вхідне з'єднання як CMD_INPUT_TOUCH (0x32) з необробленими даними MotionEvent. `handleInputFrame` обробляється на `Dispatchers.IO` (не Main) для можливості запису на localhost сокет. Телефон передає події DOWN/MOVE/UP з pointerId безпосередньо VD-серверу, який обробляє повне створення MotionEvent з усіма активними вказівниками.

## Необхідні дозволи

| Permission | Purpose |
|-----------|---------|
| MANAGE_EXTERNAL_STORAGE | All Files Access для розгортання VD JAR на sdcard |
| Accessibility Service | Ін'єкція вводу на фізичний дисплей (резервний варіант) |
| Notification Access | Пересилання сповіщень на автомобіль (з прогресом) |
| USB Debugging | Необхідно для треку USB ADB автомобіля (Developer Options) |

## Залежності

- Jetpack Compose + Material 3
- kotlinx-coroutines
- dadb 1.2.10 (WiFi ADB для автооновлення автомобіля)
- Protocol module (спільний з додатком автомобіля)
