# Протокол сипаттамасы

## Шолу

DiLink-Auto телефон мен көлік арасында **3 арнайы TCP қосылымы** арқылы теңшелген екілік протоколды пайдаланады:

| Connection | Port | Direction | Content |
|------------|------|-----------|---------|
| **Control** | 9637 | Екі бағытты | Handshake, heartbeat, қолданба командалары, DATA (қолданбалар тізімі, хабарландырулар, көлік журналдары, media) |
| **Video** | 9638 | Phone → Car | Тек H.264 CONFIG + FRAME |
| **Input** | 9639 | Car → Phone | Тек жанасу оқиғалары |

Әр қосылымның өз сокеті, NioReader-і және жазу кезегі бар — толық I/O оқшаулау бейне емес трафиктен (мысалы, үлкен қолданбалар тізімі жүктемелері) бейненің тоқтауын болдырмайды.

Control қосылымы бірінші орнатылады (handshake осында болады). Handshake-тен кейін көлік бейне және енгізу қосылымдарын параллель ашады. Телефон үшеуін де қабылдайды және оларды бір сессия ретінде байланыстырады. Heartbeat/watchdog тек control қосылымында жұмыс істейді; бейне және енгізуде heartbeat үстеме шығыны жоқ. Кез келген қосылымның үзілуі толық сессияны тоқтатуға әкеледі.

Телефон қолданбасы мен VD сервері арасында `localhost:19637` бойынша бөлек ішкі протокол жұмыс істейді ([client.md](./client.md) құжатталған). VD сервері телефонға кері қосылады (телефон тыңдайды, VD сервері қосылады). VD сервері localhost сокетінде оқу мен жазу үшін толық блокталмайтын NIO пайдаланады.

## Желі пішімі

```
+---------------+------------+--------------+-----------------+
| Frame Length   | Channel ID | Message Type | Payload          |
| (4 bytes)      | (1 byte)   | (1 byte)     | (N bytes)        |
| big-endian     |            |              |                  |
+---------------+------------+--------------+-----------------+
```

- **Frame Length**: `uint32` big-endian. Мәні = `2 + payload_size`.
- **Max payload**: 128 MB.
- **Header overhead**: бір кадрға 6 байт.

## Channels

| ID | Name | Connection | Direction | Purpose |
|----|------|------------|-----------|---------|
| 0 | CONTROL | Control (9637) | Екі бағытты | Handshake, heartbeat, қолданба командалары, VD сервер сигналдары |
| 1 | VIDEO | Video (9638) | Phone → Car | H.264 кодталған бейне кадрлары (VD серверінен жіберілген) |
| 2 | AUDIO | (резервтелген) | Phone → Car | Резервтелген (іске асырылмаған) |
| 3 | DATA | Control (9637) | Екі бағытты | Хабарландырулар, қолданбалар тізімі, медиа метадеректер, көлік журналдары |
| 4 | INPUT | Input (9639) | Car → Phone | Жанасу оқиғалары (шикі MotionEvent енгізу үшін CMD_INPUT_TOUCH) |

## Control Channel (0x00)

### HANDSHAKE_REQUEST (0x01) -- Car -> Phone

```
+----------------------+------+
| protocolVersion       | int32 |
| deviceName length     | int16 |
| deviceName            | UTF-8 |
| screenWidth           | int32 |  көлік дисплейінің ені пикселдермен (навигация панелінен кейін)
| screenHeight          | int32 |  көлік дисплейінің биіктігі пикселдермен
| supportedFeatures     | int32 |  биттік маска
| displayMode           | byte  |  0=MIRROR, 1=VIRTUAL (әдепкі)
| screenDpi             | int32 |  көлік дисплейінің тығыздығы (мысалы, 240)
| appVersionCode        | int32 |  көлік қолданбасының нұсқа коды (ескірген, кері үйлесімділік үшін)
| targetFps             | int32 |  көліктің сұратылған FPS (мысалы, 60)
| appVersionName ұзындығы | int16 |  versionName жолының ұзындығы
| appVersionName        | UTF-8 |  көлік қолданбасының нұсқа атауы (мысалы, "0.16.0")
+----------------------+------+
```

### HANDSHAKE_RESPONSE (0x02) -- Phone -> Car

```
+----------------------+------+
| protocolVersion       | int32 |
| accepted              | byte  |  1=қабылданды, 0=қабылданбады
| deviceName length     | int16 |
| deviceName            | UTF-8 |
| displayWidth          | int32 |  VD ені (көлік сұрауына сәйкес)
| displayHeight         | int32 |  VD биіктігі (көлік сұрауына сәйкес)
| virtualDisplayId      | int32 |  -1 (VD серверімен орнатылады, телефонмен емес)
| adbPort               | int32 |  5555 (телефонның ADB TCP порты)
| vdServerJarPath       | UTF-8 |  телефондағы орналастырылған VD JAR жолы (мысалы, /sdcard/DiLinkAuto/vd-server.jar)
+----------------------+------+
```

### HEARTBEAT (0x03) / HEARTBEAT_ACK (0x04) -- Тек control қосылымы

Бос жүктеме. Control қосылымында әр 3 секунд сайын жіберіледі. Егер 10 секунд ішінде ешқандай кадр алынбаса, қосылым өлі деп саналады (watchdog тайм-ауты). Бейне және енгізу қосылымдарында heartbeat жоқ.

### DISCONNECT (0x05) -- Екі бағытты

Бос жүктеме. Сыпайы түрде ажырату.

### APP_STOPPED (0x14) -- Phone -> Car

Бос жүктеме. Виртуалды дисплейдегі қолданба тоқтатылғанда жіберіледі.

### VD_SERVER_READY (0x20) -- Car -> Phone

Бос жүктеме. Көлік VD сервер процесі телефонда жұмыс істеп тұрғанын растайды.

### LAUNCH_APP (0x10) -- Car -> Phone

```
+----------------------+------+
| packageName           | UTF-8 |  шикі байттар, ұзындық префиксінсіз
+----------------------+------+
```

Телефон VD серверіне жібереді, ол `am start --display <id> -n <component>` орындайды (`--activity-clear-task` жоқ — бар қолданбалар жалғасады).

### GO_HOME (0x11) / GO_BACK (0x12) -- Car -> Phone

Бос жүктеме. Телефон VD серверіне жібереді. GO_BACK `input -d <id> keyevent 4` жібереді, содан кейін бос стекті тексереді. GO_HOME — бос әрекет (көлік launcher навигациясын өңдейді).

### APP_STARTED (0x13) -- Phone -> Car

LAUNCH_APP сияқты пішім. Қолданбаның іске қосылғанын растайды.

### VD_STACK_EMPTY (0x15) -- Phone -> Car

Бос жүктеме. GO_BACK-тен кейін VD сервері виртуалды дисплейде қалған қолданба тапсырмалары жоқ екенін анықтағанда жіберіледі (`dumpsys activity activities` арқылы). Көлік мұны айна көрінісінен басты экранға ауысу үшін пайдаланады.

### UPDATING_CAR (0x30) -- Phone -> Car

Бос жүктеме. Телефон көлік қолданбасын авто-жаңартуды бастамас бұрын жіберіледі. Көлік "Updating car app..." күйін көрсетеді және қайта қосылуды тоқтатады. Жаңартудан кейін көлік қолданбасы жаңадан іске қосылады.

## Video Channel (0x01)

### CONFIG (0x01) -- Phone -> Car

Бастапқы кодтары бар H.264 SPS/PPS NAL бірліктері. Кодтаушы іске қосылғанда бір рет жіберіледі.

### FRAME (0x02) -- Phone -> Car

Бейне кадрын білдіретін H.264 NAL бірліктері.

**Кодтау параметрлері** (VD серверімен орнатылады, handshake арқылы бапталады):
- Codec: H.264/AVC
- Profile: High
- Resolution: көлік көрініс терезесі өлшемдері (мысалы, 1806x990)
- Bitrate: 8 Mbps CBR
- Frame rate: handshake-тегі `targetFps` арқылы бапталады (әдепкі 30, көлік 60 сұратады)
- IDR interval: 1 секунд
- SurfaceScaler: әр `1000/fps` мс сайын мерзімді қайта салу статикалық мазмұнда кодтаушы шығысын қамтамасыз етеді

## Data Channel (0x03)

### NOTIFICATION_POST (0x01) / NOTIFICATION_REMOVE (0x02) -- Phone -> Car

id, packageName, appName, title, text, timestamp, progressIndeterminate (byte), progress (int32), progressMax (int32) бар хабарландыру деректері. Көлік ID бойынша дедубликациялайды (жаңартулар барларын ауыстырады). Хабарландыруды түрту VD-де ие қолданбаны іске қосады.

### APP_LIST (0x03) -- Phone -> Car

packageName, appName, category (NAV/MUSIC/COMM/OTHER), iconPng (96x96 PNG) бар орнатылған қолданбалар тізімі.

### CAR_LOG (0x30) -- Car -> Phone

UTF-8 мәтін жолы. Көлік барлық журналдарды (VideoDecoder және UsbAdbConnection қоса) осы арна арқылы бағыттайды. Телефон `CarLog` тегімен FileLog-қа (`/sdcard/DiLinkAuto/client.log`) жазады.

### MEDIA_METADATA (0x10) / MEDIA_PLAYBACK_STATE (0x11) -- Phone -> Car

Трек ақпараты және ойнату күйі. Әлі белсенді түрде толтырылмайды.

### MEDIA_ACTION (0x12) -- Car -> Phone

Медиа басқару (play/pause/next/previous). MediaSession-ке әлі қосылмаған.

### NAVIGATION_STATE (0x20) -- Phone -> Car

Навигация күйі деректері. Навигация виджетімен интеграция үшін резервтелген.

## Input Channel (0x04)

### TOUCH_DOWN (0x01) -- Car -> Phone

Бір көрсеткішті басу оқиғасы. Жүктеме TouchEvent (25 байт):

```
+----------------------+------+
| action                | byte  |  InputMsg.TOUCH_DOWN (0x01)
| pointerId             | int32 |  мульти-жанасу көрсеткіш ID
| x                     | float |  нормаланған 0.0-1.0
| y                     | float |  нормаланған 0.0-1.0
| pressure              | float |
| timestamp             | int64 |
+----------------------+------+
```

### TOUCH_MOVE (0x02) -- Car -> Phone

Бір көрсеткішті жылжыту оқиғасы. TOUCH_DOWN сияқты TouchEvent жүктеме пішімі.

### TOUCH_UP (0x03) -- Car -> Phone

Бір көрсеткішті босату оқиғасы. TOUCH_DOWN сияқты TouchEvent жүктеме пішімі.

### TOUCH_MOVE_BATCH (0x04) -- Car -> Phone

```
+----------------------+------+
| count                 | byte  |  көрсеткіштер саны
| N × pointer:          |       |
|   pointerId           | int32 |
|   x                   | float |  нормаланған 0.0-1.0
|   y                   | float |  нормаланған 0.0-1.0
|   pressure            | float |
|   timestamp           | int64 |
+----------------------+------+
```

Пакеттелген MOVE оқиғалары — барлық белсенді көрсеткіштер бір хабарламада. Көп саусақты ым-ишаралар үшін жүйелік шақыруларды азайтады.

### KEY_EVENT (0x10) -- Car -> Phone

Перне оқиғасы (мысалы, медиа пернелер, навигация пернелері). Болашақта пайдалану үшін резервтелген.

## Constants

```
APP_VERSION_COMPARISON = versionName via semver (with versionCode fallback for older cars)
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

Барлық көп байтты бүтін сандар және float — **big-endian**.
