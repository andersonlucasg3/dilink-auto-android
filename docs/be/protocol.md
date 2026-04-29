# Спецыфікацыя пратаколу

## Агляд

DiLink-Auto выкарыстоўвае ўласны двайковы пратакол праз **3 спецыялізаваныя TCP-злучэнні** паміж тэлефонам і аўтамабілем:

| Connection | Port | Direction | Content |
|------------|------|-----------|---------|
| **Control** | 9637 | Двухнакіраванае | Handshake, heartbeat, каманды прыкладанняў, DATA (спіс прыкладанняў, апавяшчэнні, лагі аўтамабіля, media) |
| **Video** | 9638 | Phone → Car | Толькі H.264 CONFIG + FRAME |
| **Input** | 9639 | Car → Phone | Толькі падзеі дотыку |

Кожнае злучэнне мае ўласны сокет, NioReader і чаргу запісу — поўная ізаляцыя I/O прадухіляе спыненні відэа ад не-відэа трафіку (напрыклад, вялікія спісы прыкладанняў).

Control-злучэнне ўсталёўваецца першым (тут адбываецца handshake). Пасля handshake аўтамабіль адкрывае відэа і ўваходныя злучэнні паралельна. Тэлефон прымае ўсе тры і звязвае іх як адну сесію. Heartbeat/watchdog працуе толькі на control-злучэнні; відэа і ўваходныя не маюць накладных выдаткаў heartbeat. Смерць любога злучэння каскадна вядзе да поўнага разрыву сесіі.

Асобны ўнутраны пратакол працуе паміж прыкладаннем тэлефона і VD-серверам на `localhost:19637` (задакументавана ў [client.md](./client.md)). VD-сервер падключаецца да тэлефона зваротна (тэлефон слухае, VD-сервер падключаецца). VD-сервер выкарыстоўвае цалкам неблакавальны NIO для чытання і запісу на сокеце localhost.

## Фармат перадачы

```
+---------------+------------+--------------+-----------------+
| Frame Length   | Channel ID | Message Type | Payload          |
| (4 bytes)      | (1 byte)   | (1 byte)     | (N bytes)        |
| big-endian     |            |              |                  |
+---------------+------------+--------------+-----------------+
```

- **Frame Length**: `uint32` big-endian. Значэнне = `2 + payload_size`.
- **Max payload**: 128 MB.
- **Header overhead**: 6 байт на кадр.

## Channels

| ID | Name | Connection | Direction | Purpose |
|----|------|------------|-----------|---------|
| 0 | CONTROL | Control (9637) | Двухнакіраванае | Handshake, heartbeat, каманды прыкладанняў, сігналы VD-сервера |
| 1 | VIDEO | Video (9638) | Phone → Car | Кадаваныя H.264 відэакадры (перадаюцца ад VD-сервера) |
| 2 | AUDIO | (зарэзервавана) | Phone → Car | Зарэзервавана (не рэалізавана) |
| 3 | DATA | Control (9637) | Двухнакіраванае | Апавяшчэнні, спіс прыкладанняў, метаданыя media, лагі аўтамабіля |
| 4 | INPUT | Input (9639) | Car → Phone | Падзеі дотыку (CMD_INPUT_TOUCH для ін'екцыі неапрацаваных MotionEvent) |

## Control Channel (0x00)

### HANDSHAKE_REQUEST (0x01) -- Car -> Phone

```
+----------------------+------+
| protocolVersion       | int32 |
| deviceName length     | int16 |
| deviceName            | UTF-8 |
| screenWidth           | int32 |  шырыня дысплея аўтамабіля ў пікселях (пасля панэлі навігацыі)
| screenHeight          | int32 |  вышыня дысплея аўтамабіля ў пікселях
| supportedFeatures     | int32 |  бітавая маска
| displayMode           | byte  |  0=MIRROR, 1=VIRTUAL (па змаўчанні)
| screenDpi             | int32 |  шчыльнасць дысплея аўтамабіля (напр. 240)
| appVersionCode        | int32 |  код версіі прыкладання аўтамабіля
| targetFps             | int32 |  запытаны FPS аўтамабіля (напр. 60)
+----------------------+------+
```

### HANDSHAKE_RESPONSE (0x02) -- Phone -> Car

```
+----------------------+------+
| protocolVersion       | int32 |
| accepted              | byte  |  1=прынята, 0=адхілена
| deviceName length     | int16 |
| deviceName            | UTF-8 |
| displayWidth          | int32 |  шырыня VD (адпавядае запыту аўтамабіля)
| displayHeight         | int32 |  вышыня VD (адпавядае запыту аўтамабіля)
| virtualDisplayId      | int32 |  -1 (усталёўваецца VD-серверам, не тэлефонам)
| adbPort               | int32 |  5555 (порт ADB TCP тэлефона)
| vdServerJarPath       | UTF-8 |  шлях да разгорнутага VD JAR на тэлефоне (напр. /sdcard/DiLinkAuto/vd-server.jar)
+----------------------+------+
```

### HEARTBEAT (0x03) / HEARTBEAT_ACK (0x04) -- Толькі control-злучэнне

Пусты payload. Адпраўляецца кожныя 3 секунды на control-злучэнні. Калі кадр не атрыманы на працягу 10 секунд, злучэнне лічыцца мёртвым (watchdog timeout). Відэа і ўваходныя злучэнні не маюць heartbeat.

### DISCONNECT (0x05) -- Двухнакіраванае

Пусты payload. Плаўнае завяршэнне працы.

### APP_STOPPED (0x14) -- Phone -> Car

Пусты payload. Адпраўляецца, калі прыкладанне на віртуальным дысплеі спынена.

### VD_SERVER_READY (0x20) -- Car -> Phone

Пусты payload. Аўтамабіль пацвярджае, што працэс VD-сервера запушчаны на тэлефоне.

### LAUNCH_APP (0x10) -- Car -> Phone

```
+----------------------+------+
| packageName           | UTF-8 |  неапрацаваныя байты, без прэфікса даўжыні
+----------------------+------+
```

Тэлефон перасылае VD-серверу, які выконвае `am start --display <id> -n <component>` (без `--activity-clear-task` — існуючыя прыкладанні аднаўляюцца).

### GO_HOME (0x11) / GO_BACK (0x12) -- Car -> Phone

Пусты payload. Тэлефон перасылае VD-серверу. GO_BACK адпраўляе `input -d <id> keyevent 4`, затым правярае пусты стэк. GO_HOME — пустая аперацыя (аўтамабіль апрацоўвае навігацыю launcher).

### APP_STARTED (0x13) -- Phone -> Car

Такі ж фармат, як LAUNCH_APP. Пацвярджае, што прыкладанне запушчана.

### VD_STACK_EMPTY (0x15) -- Phone -> Car

Пусты payload. Адпраўляецца пасля GO_BACK, калі VD-сервер выяўляе адсутнасць пакінутых задач прыкладанняў на віртуальным дысплеі (праз `dumpsys activity activities`). Аўтамабіль выкарыстоўвае гэта для пераключэння з люстэркавага выгляду на хатні экран.

### UPDATING_CAR (0x30) -- Phone -> Car

Пусты payload. Адпраўляецца перад тым, як тэлефон пачынае аўтаматычнае абнаўленне прыкладання аўтамабіля. Аўтамабіль паказвае "Updating car app..." статус і спыняе перападключэнне. Пасля абнаўлення прыкладанне аўтамабіля перазапускаецца нанова.

## Video Channel (0x01)

### CONFIG (0x01) -- Phone -> Car

H.264 SPS/PPS NAL units са стартавымі кодамі. Адпраўляецца адзін раз пры старце кадавальніка.

### FRAME (0x02) -- Phone -> Car

H.264 NAL units, якія прадстаўляюць відэакадр.

**Параметры кадавання** (усталёўваюцца VD-серверам, наладжвальныя праз handshake):
- Codec: H.264/AVC
- Profile: High
- Resolution: памеры вакна аўтамабіля (напр., 1806x990)
- Bitrate: 12 Mbps CBR
- Frame rate: наладжвальны праз `targetFps` у handshake (па змаўчанні 30, аўтамабіль запытвае 60)
- IDR interval: 1 секунда
- SurfaceScaler: перыядычны перарысоўк кожныя `1000/fps` мс забяспечвае вывад кадавальніка на статычным змесце

## Data Channel (0x03)

### NOTIFICATION_POST (0x01) / NOTIFICATION_REMOVE (0x02) -- Phone -> Car

Даныя апавяшчэння з id, packageName, appName, title, text, timestamp, progressIndeterminate (byte), progress (int32), progressMax (int32). Аўтамабіль дэдублікуе па ID (абнаўленні замяняюць існуючыя). Націск на апавяшчэнне запускае прыкладанне-ўладальнік на VD.

### APP_LIST (0x03) -- Phone -> Car

Спіс усталяваных прыкладанняў з packageName, appName, category (NAV/MUSIC/COMM/OTHER), iconPng (96x96 PNG).

### CAR_LOG (0x30) -- Car -> Phone

Тэкставы радок UTF-8. Аўтамабіль маршрутызуе ўсе лагі (уключаючы VideoDecoder і UsbAdbConnection) праз гэты канал. Тэлефон піша ў FileLog (`/sdcard/DiLinkAuto/client.log`) з тэгам `CarLog`.

### MEDIA_METADATA (0x10) / MEDIA_PLAYBACK_STATE (0x11) -- Phone -> Car

Інфармацыя аб трэку і стане прайгравання. Пакуль актыўна не запаўняецца.

### MEDIA_ACTION (0x12) -- Car -> Phone

Кіраванне media (play/pause/next/previous). Пакуль не падключана да MediaSession.

### NAVIGATION_STATE (0x20) -- Phone -> Car

Даныя стану навігацыі. Зарэзервавана для інтэграцыі віджэта навігацыі.

## Input Channel (0x04)

### TOUCH_DOWN (0x01) -- Car -> Phone

Падзея аднаго націску ўніз. Payload — гэта TouchEvent (25 байт):

```
+----------------------+------+
| action                | byte  |  InputMsg.TOUCH_DOWN (0x01)
| pointerId             | int32 |  ID паказальніка multi-touch
| x                     | float |  нармалізавана 0.0-1.0
| y                     | float |  нармалізавана 0.0-1.0
| pressure              | float |
| timestamp             | int64 |
+----------------------+------+
```

### TOUCH_MOVE (0x02) -- Car -> Phone

Падзея перамяшчэння аднаго паказальніка. Такі ж фармат payload TouchEvent, як TOUCH_DOWN.

### TOUCH_UP (0x03) -- Car -> Phone

Падзея аднаго націску ўверх. Такі ж фармат payload TouchEvent, як TOUCH_DOWN.

### TOUCH_MOVE_BATCH (0x04) -- Car -> Phone

```
+----------------------+------+
| count                 | byte  |  колькасць паказальнікаў
| N × pointer:          |       |
|   pointerId           | int32 |
|   x                   | float |  нармалізавана 0.0-1.0
|   y                   | float |  нармалізавана 0.0-1.0
|   pressure            | float |
|   timestamp           | int64 |
+----------------------+------+
```

Пакетныя падзеі MOVE — усе актыўныя паказальнікі ў адным паведамленні. Скарачае колькасць сістэмных выклікаў для шматпальцавых жэстаў.

### KEY_EVENT (0x10) -- Car -> Phone

Клавішная падзея (напрыклад, медыяклавішы, навігацыйныя клавішы). Зарэзервавана для будучага выкарыстання.

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

Усе шматбайтавыя цэлыя і float — **big-endian**.
