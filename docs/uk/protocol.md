# Специфікація протоколу

## Огляд

DiLink-Auto використовує власний двійковий протокол через **3 виділені TCP-з'єднання** між телефоном і автомобілем:

| Connection | Port | Direction | Content |
|------------|------|-----------|---------|
| **Control** | 9637 | Двонаправлене | Handshake, heartbeat, команди додатків, DATA (список додатків, сповіщення, логи автомобіля, media) |
| **Video** | 9638 | Phone → Car | Лише H.264 CONFIG + FRAME |
| **Input** | 9639 | Car → Phone | Лише події дотику |

Кожне з'єднання має власний сокет, NioReader і чергу запису — повна ізоляція I/O запобігає зупинкам відео від не-відео трафіку (наприклад, великі списки додатків).

Control-з'єднання встановлюється першим (тут відбувається handshake). Після handshake автомобіль відкриває відео та вхідні з'єднання паралельно. Телефон приймає всі три та пов'язує їх як одну сесію. Heartbeat/watchdog працює лише на control-з'єднанні; відео та вхідні не мають накладних витрат heartbeat. Смерть будь-якого з'єднання каскадно веде до повного розриву сесії.

Окремий внутрішній протокол працює між додатком телефону та VD-сервером на `localhost:19637` (задокументовано в [client.md](./client.md)). VD-сервер підключається до телефону зворотно (телефон слухає, VD-сервер підключається). VD-сервер використовує повністю неблокуючий NIO для читання та запису на сокеті localhost.

## Дротовий формат

```
+---------------+------------+--------------+-----------------+
| Frame Length   | Channel ID | Message Type | Payload          |
| (4 bytes)      | (1 byte)   | (1 byte)     | (N bytes)        |
| big-endian     |            |              |                  |
+---------------+------------+--------------+-----------------+
```

- **Frame Length**: `uint32` big-endian. Значення = `2 + payload_size`.
- **Max payload**: 128 MB.
- **Header overhead**: 6 байт на кадр.

## Channels

| ID | Name | Connection | Direction | Purpose |
|----|------|------------|-----------|---------|
| 0 | CONTROL | Control (9637) | Двонаправлене | Handshake, heartbeat, команди додатків, сигнали VD-сервера |
| 1 | VIDEO | Video (9638) | Phone → Car | Кодовані H.264 відеокадри (передаються від VD-сервера) |
| 2 | AUDIO | (зарезервовано) | Phone → Car | Зарезервовано (не реалізовано) |
| 3 | DATA | Control (9637) | Двонаправлене | Сповіщення, список додатків, метадані media, логи автомобіля |
| 4 | INPUT | Input (9639) | Car → Phone | Події дотику (CMD_INPUT_TOUCH для ін'єкції необроблених MotionEvent) |

## Control Channel (0x00)

### HANDSHAKE_REQUEST (0x01) -- Car -> Phone

```
+----------------------+------+
| protocolVersion       | int32 |
| deviceName length     | int16 |
| deviceName            | UTF-8 |
| screenWidth           | int32 |  ширина дисплея автомобіля в пікселях (після панелі навігації)
| screenHeight          | int32 |  висота дисплея автомобіля в пікселях
| supportedFeatures     | int32 |  бітова маска
| displayMode           | byte  |  0=MIRROR, 1=VIRTUAL (за замовчуванням)
| screenDpi             | int32 |  щільність дисплея автомобіля (напр. 240)
| appVersionCode        | int32 |  код версії додатку автомобіля
| targetFps             | int32 |  запитаний FPS автомобіля (напр. 60)
+----------------------+------+
```

### HANDSHAKE_RESPONSE (0x02) -- Phone -> Car

```
+----------------------+------+
| protocolVersion       | int32 |
| accepted              | byte  |  1=прийнято, 0=відхилено
| deviceName length     | int16 |
| deviceName            | UTF-8 |
| displayWidth          | int32 |  ширина VD (відповідає запиту автомобіля)
| displayHeight         | int32 |  висота VD (відповідає запиту автомобіля)
| virtualDisplayId      | int32 |  -1 (встановлюється VD-сервером, не телефоном)
| adbPort               | int32 |  5555 (порт ADB TCP телефону)
| vdServerJarPath       | UTF-8 |  шлях до розгорнутого VD JAR на телефоні (напр. /sdcard/DiLinkAuto/vd-server.jar)
+----------------------+------+
```

### HEARTBEAT (0x03) / HEARTBEAT_ACK (0x04) -- Лише control-з'єднання

Порожній payload. Надсилається кожні 3 секунди на control-з'єднанні. Якщо кадр не отримано протягом 10 секунд, з'єднання вважається мертвим (watchdog timeout). Відео та вхідні з'єднання не мають heartbeat.

### DISCONNECT (0x05) -- Двонаправлене

Порожній payload. Плавне завершення роботи.

### APP_STOPPED (0x14) -- Phone -> Car

Порожній payload. Надсилається, коли додаток на віртуальному дисплеї зупинено.

### VD_SERVER_READY (0x20) -- Car -> Phone

Порожній payload. Автомобіль підтверджує, що процес VD-сервера запущено на телефоні.

### LAUNCH_APP (0x10) -- Car -> Phone

```
+----------------------+------+
| packageName           | UTF-8 |  необроблені байти, без префікса довжини
+----------------------+------+
```

Телефон пересилає VD-серверу, який виконує `am start --display <id> -n <component>` (без `--activity-clear-task` — існуючі додатки відновлюються).

### GO_HOME (0x11) / GO_BACK (0x12) -- Car -> Phone

Порожній payload. Телефон пересилає VD-серверу. GO_BACK надсилає `input -d <id> keyevent 4`, потім перевіряє порожній стек. GO_HOME — пуста операція (автомобіль обробляє навігацію launcher).

### APP_STARTED (0x13) -- Phone -> Car

Такий самий формат, як LAUNCH_APP. Підтверджує, що додаток запущено.

### VD_STACK_EMPTY (0x15) -- Phone -> Car

Порожній payload. Надсилається після GO_BACK, коли VD-сервер виявляє відсутність залишкових задач додатків на віртуальному дисплеї (через `dumpsys activity activities`). Автомобіль використовує це для перемикання з дзеркального вигляду на домашній екран.

### UPDATING_CAR (0x30) -- Phone -> Car

Порожній payload. Надсилається перед тим, як телефон починає автоматичне оновлення додатку автомобіля. Автомобіль показує "Updating car app..." статус і зупиняє перепідключення. Після оновлення додаток автомобіля перезапускається наново.

## Video Channel (0x01)

### CONFIG (0x01) -- Phone -> Car

H.264 SPS/PPS NAL units зі стартовими кодами. Надсилається один раз при старті кодувальника.

### FRAME (0x02) -- Phone -> Car

H.264 NAL units, що представляють відеокадр.

**Параметри кодування** (встановлюються VD-сервером, настроювані через handshake):
- Codec: H.264/AVC
- Profile: High
- Resolution: розміри вікна автомобіля (напр., 1806x990)
- Bitrate: 12 Mbps CBR
- Frame rate: настроюваний через `targetFps` у handshake (за замовчуванням 30, автомобіль запитує 60)
- IDR interval: 1 секунда
- SurfaceScaler: періодичне перемальовування кожні `1000/fps` мс забезпечує вивід кодувальника на статичному вмісті

## Data Channel (0x03)

### NOTIFICATION_POST (0x01) / NOTIFICATION_REMOVE (0x02) -- Phone -> Car

Дані сповіщення з id, packageName, appName, title, text, timestamp, progressIndeterminate (byte), progress (int32), progressMax (int32). Автомобіль дедублікує за ID (оновлення замінюють існуючі). Дотик до сповіщення запускає додаток-власник на VD.

### APP_LIST (0x03) -- Phone -> Car

Список встановлених додатків з packageName, appName, category (NAV/MUSIC/COMM/OTHER), iconPng (96x96 PNG).

### CAR_LOG (0x30) -- Car -> Phone

Текстовий рядок UTF-8. Автомобіль маршрутизує всі логи (включаючи VideoDecoder та UsbAdbConnection) через цей канал. Телефон пише до FileLog (`/sdcard/DiLinkAuto/client.log`) з тегом `CarLog`.

### MEDIA_METADATA (0x10) / MEDIA_PLAYBACK_STATE (0x11) -- Phone -> Car

Інформація про трек і стан програвання. Поки активно не заповнюється.

### MEDIA_ACTION (0x12) -- Car -> Phone

Керування media (play/pause/next/previous). Поки не підключена до MediaSession.

### NAVIGATION_STATE (0x20) -- Phone -> Car

Дані стану навігації. Зарезервовано для інтеграції віджета навігації.

## Input Channel (0x04)

### TOUCH_DOWN (0x01) -- Car -> Phone

Подія одного натискання вниз. Payload — це TouchEvent (25 байт):

```
+----------------------+------+
| action                | byte  |  InputMsg.TOUCH_DOWN (0x01)
| pointerId             | int32 |  ID вказівника multi-touch
| x                     | float |  нормалізовано 0.0-1.0
| y                     | float |  нормалізовано 0.0-1.0
| pressure              | float |
| timestamp             | int64 |
+----------------------+------+
```

### TOUCH_MOVE (0x02) -- Car -> Phone

Подія переміщення одного вказівника. Такий самий формат payload TouchEvent, як TOUCH_DOWN.

### TOUCH_UP (0x03) -- Car -> Phone

Подія одного натискання вгору. Такий самий формат payload TouchEvent, як TOUCH_DOWN.

### TOUCH_MOVE_BATCH (0x04) -- Car -> Phone

```
+----------------------+------+
| count                 | byte  |  кількість вказівників
| N × pointer:          |       |
|   pointerId           | int32 |
|   x                   | float |  нормалізовано 0.0-1.0
|   y                   | float |  нормалізовано 0.0-1.0
|   pressure            | float |
|   timestamp           | int64 |
+----------------------+------+
```

Пакетні події MOVE — усі активні вказівники в одному повідомленні. Скорочує кількість системних викликів для багатопальцевих жестів.

### KEY_EVENT (0x10) -- Car -> Phone

Клавішна подія (наприклад, медіаклавіші, навігаційні клавіші). Зарезервовано для майбутнього використання.

## Constants

```
APP_VERSION_CODE      = read at runtime via PackageManager
PROTOCOL_VERSION      = 1
CONTROL_PORT          = 9637 (phone <-> car, handshake + heartbeat + commands + data)
VIDEO_PORT            = 9638 (phone -> car, H.264 frames only)
INPUT_PORT            = 9639 (car -> phone, touch events only)
VD_SERVER_PORT        = 19637 (phone <-> VD server, localhost only, NIO non-blocking)
TARGET_FPS            = 60 (configurable via handshake, default 30)
FRAME_INTERVAL_MS     = 1000 / TARGET_FPS (16ms at 60fps, max wait for video-path loops)
HEARTBEAT_INTERVAL    = 3000 ms (control connection only)
HEARTBEAT_TIMEOUT     = 10000 ms (control connection only)
MAX_PAYLOAD_SIZE      = 134,217,728 bytes (128 MB)
SERVICE_TYPE (mDNS)   = "_dilinkauto._tcp."
DISPLAY_MODE_MIRROR   = 0
DISPLAY_MODE_VIRTUAL  = 1
```

## Byte Order

Усі багатобайтові цілі та float — **big-endian**.
