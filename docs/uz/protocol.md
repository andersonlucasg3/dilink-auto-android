# Protokol Spetsifikatsiyasi

## Umumiy ko'rinish

DiLink-Auto telefon va avtomobil o'rtasida **3 ajratilgan TCP ulanishi** orqali maxsus ikkilik protokoldan foydalanadi:

| Connection | Port | Direction | Content |
|------------|------|-----------|---------|
| **Control** | 9637 | Ikki yo'nalishli | Handshake, heartbeat, ilova buyruqlari, DATA (ilovalar ro'yxati, xabarnomalar, avtomobil jurnallari, media) |
| **Video** | 9638 | Phone → Car | Faqat H.264 CONFIG + FRAME |
| **Input** | 9639 | Car → Phone | Faqat teginish hodisalari |

Har bir ulanish o'z soketi, NioReader'i va yozish navbatiga ega — to'liq I/O izolyatsiyasi video bo'lmagan trafikdan (masalan, katta ilovalar ro'yxati yuklamalari) video to'xtashlarini oldini oladi.

Control ulanishi birinchi o'rnatiladi (handshake shu yerda bo'ladi). Handshake'dan keyin avtomobil video va kiritish ulanishlarini parallel ochadi. Telefon uchalasini ham qabul qiladi va ularni bir sessiya sifatida bog'laydi. Heartbeat/watchdog faqat control ulanishida ishlaydi; video va kiritishda heartbeat qo'shimcha xarajati yo'q. Har qanday ulanishning uzilishi to'liq sessiya to'xtatilishiga olib keladi.

Telefon ilovasi va VD serveri o'rtasida `localhost:19637` da alohida ichki protokol ishlaydi ([client.md](./client.md) da hujjatlashtirilgan). VD serveri telefonga teskari ulanadi (telefon tinglaydi, VD serveri ulanadi). VD serveri localhost soketida o'qish va yozish uchun to'liq bloklanmaydigan NIO dan foydalanadi.

## Simli Format

```
+---------------+------------+--------------+-----------------+
| Frame Length   | Channel ID | Message Type | Payload          |
| (4 bytes)      | (1 byte)   | (1 byte)     | (N bytes)        |
| big-endian     |            |              |                  |
+---------------+------------+--------------+-----------------+
```

- **Frame Length**: `uint32` big-endian. Qiymat = `2 + payload_size`.
- **Max payload**: 128 MB.
- **Header overhead**: har bir kadrga 6 bayt.

## Channels

| ID | Name | Connection | Direction | Purpose |
|----|------|------------|-----------|---------|
| 0 | CONTROL | Control (9637) | Ikki yo'nalishli | Handshake, heartbeat, ilova buyruqlari, VD server signallari |
| 1 | VIDEO | Video (9638) | Phone → Car | H.264 kodlangan video kadrlari (VD serveridan uzatilgan) |
| 2 | AUDIO | (zaxiralangan) | Phone → Car | Zaxiralangan (amalga oshirilmagan) |
| 3 | DATA | Control (9637) | Ikki yo'nalishli | Xabarnomalar, ilovalar ro'yxati, media metama'lumotlar, avtomobil jurnallari |
| 4 | INPUT | Input (9639) | Car → Phone | Teginish hodisalari (xom MotionEvent inyeksiyasi uchun CMD_INPUT_TOUCH) |

## Control Channel (0x00)

### HANDSHAKE_REQUEST (0x01) -- Car -> Phone

```
+----------------------+------+
| protocolVersion       | int32 |
| deviceName length     | int16 |
| deviceName            | UTF-8 |
| screenWidth           | int32 |  avtomobil displeyining piksellardagi eni (navigatsiya panelidan keyin)
| screenHeight          | int32 |  avtomobil displeyining piksellardagi balandligi
| supportedFeatures     | int32 |  bit maskasi
| displayMode           | byte  |  0=MIRROR, 1=VIRTUAL (default)
| screenDpi             | int32 |  avtomobil displey zichligi (mas. 240)
| appVersionCode        | int32 |  avtomobil ilovasi versiya kodi (eski, orqaga moslik uchun)
| targetFps             | int32 |  avtomobil so'ragan FPS (mas. 60)
| appVersionName uzunligi | int16 |  versionName satr uzunligi
| appVersionName        | UTF-8 |  avtomobil ilovasi versiya nomi (mas. "0.16.0")
+----------------------+------+
```

### HANDSHAKE_RESPONSE (0x02) -- Phone -> Car

```
+----------------------+------+
| protocolVersion       | int32 |
| accepted              | byte  |  1=qabul qilingan, 0=rad etilgan
| deviceName length     | int16 |
| deviceName            | UTF-8 |
| displayWidth          | int32 |  VD eni (avtomobil so'roviga mos)
| displayHeight         | int32 |  VD balandligi (avtomobil so'roviga mos)
| virtualDisplayId      | int32 |  -1 (VD serveri tomonidan o'rnatiladi, telefon tomonidan emas)
| adbPort               | int32 |  5555 (telefonning ADB TCP porti)
| vdServerJarPath       | UTF-8 |  telefonda joylashtirilgan VD JAR yo'li (mas. /sdcard/DiLinkAuto/vd-server.jar)
+----------------------+------+
```

### HEARTBEAT (0x03) / HEARTBEAT_ACK (0x04) -- Faqat control ulanishi

Bo'sh yuklama. Control ulanishida har 3 soniyada yuboriladi. Agar 10 soniya ichida hech qanday kadr olinmasa, ulanish o'lik hisoblanadi (watchdog taym-auti). Video va kiritish ulanishlarida heartbeat yo'q.

### DISCONNECT (0x05) -- Ikki yo'nalishli

Bo'sh yuklama. Odobli o'chirish.

### APP_STOPPED (0x14) -- Phone -> Car

Bo'sh yuklama. Virtual displeydagi ilova to'xtatilganda yuboriladi.

### VD_SERVER_READY (0x20) -- Car -> Phone

Bo'sh yuklama. Avtomobil VD server jarayoni telefonda ishlayotganini tasdiqlaydi.

### LAUNCH_APP (0x10) -- Car -> Phone

```
+----------------------+------+
| packageName           | UTF-8 |  xom baytlar, uzunlik prefiksisiz
+----------------------+------+
```

Telefon VD serveriga yo'naltiradi, u `am start --display <id> -n <component>` ni bajaradi (`--activity-clear-task` yo'q — mavjud ilovalar davom etadi).

### GO_HOME (0x11) / GO_BACK (0x12) -- Car -> Phone

Bo'sh yuklama. Telefon VD serveriga yo'naltiradi. GO_BACK `input -d <id> keyevent 4` yuboradi, so'ngra bo'sh stekni tekshiradi. GO_HOME — bo'sh amal (avtomobil ishga tushirgich navigatsiyasini boshqaradi).

### APP_STARTED (0x13) -- Phone -> Car

LAUNCH_APP bilan bir xil format. Ilova ishga tushirilganini tasdiqlaydi.

### VD_STACK_EMPTY (0x15) -- Phone -> Car

Bo'sh yuklama. GO_BACK dan keyin VD serveri virtual displeyda qolgan ilova vazifalari yo'qligini aniqlaganda yuboriladi (`dumpsys activity activities` orqali). Avtomobil buni ko'zgu ko'rinishidan bosh ekranga o'tish uchun ishlatadi.

### FOCUSED_APP (0x16) -- Phone -> Car

Yuklama: UTF-8 package name. Virtual displeyda ilova fokus olganda yuboriladi. Avtomobil buni ilova kuzatish holatini yangilash uchun ishlatadi.

### APP_INFO (0x17) -- Car -> Phone

Yuklama: UTF-8 package name. Avtomobil telefondan berilgan paket uchun tizim ilova ma'lumot/sozlamalar ekranini ochishni so'raydi.

### APP_SHORTCUTS (0x18) -- Car -> Phone

Yuklama: UTF-8 package name. Avtomobil berilgan paket uchun mavjud Android 7.1+ ilova yorliqlarini so'raydi. **UI da o'chirilgan** — infratuzilma (VD server so'rovi + APK XML zaxirasi) mavjud, ammo yorliqlar aniqlashtirilguncha yashirilgan (issue #57).

### APP_SHORTCUTS_LIST (0x19) -- Phone -> Car

Yuklama: `AppShortcutsListMessage` — package name + yorliq deskriptorlari ro'yxati (id, shortLabel, longLabel). APP_SHORTCUTS so'roviga javob sifatida yuboriladi.

### APP_SHORTCUT_ACTION (0x1A) -- Car -> Phone

Yuklama: `AppShortcutActionMessage` — package name + shortcut id. Virtual displeyda aniq yorliqni ishga tushiradi.

### APP_UNINSTALL (0x1B) -- Car -> Phone

Yuklama: UTF-8 package name. Avtomobil telefondan berilgan paketni o'chirishni so'raydi. Telefon tizim o'chirish dialogini boshqaradi va tugagandan keyin ma'lumot kanali orqali `APP_UNINSTALLED` qaytarib yuboradi.

### UPDATING_CAR (0x30) -- Phone -> Car

Bo'sh yuklama. Telefon avtomobil ilovasini avto-yangilashni boshlashdan oldin yuboriladi. Avtomobil "Updating car app..." holatini ko'rsatadi va qayta ulanishni to'xtatadi. Yangilashdan keyin avtomobil ilovasi yangidan ishga tushadi.

## Video Channel (0x01)

### CONFIG (0x01) -- Phone -> Car

Boshlang'ich kodlari bilan H.264 SPS/PPS NAL birliklari. Kodlovchi ishga tushganda bir marta yuboriladi.

### FRAME (0x02) -- Phone -> Car

Video kadrni ifodalovchi H.264 NAL birliklari.

**Kodlash parametrlari** (VD serveri tomonidan o'rnatiladi, handshake orqali sozlanadi):
- Codec: H.264/AVC
- Profile: High
- Resolution: avtomobil ko'rish oynasi o'lchamlari (mas., 1806x990)
- Bitrate: 8 Mbps CBR
- Frame rate: handshake'dagi `targetFps` orqali sozlanadi (default 30, avtomobil 60 so'raydi)
- IDR interval: 1 soniya
- SurfaceScaler: har `1000/fps` ms da davriy qayta chizish statik tarkibda kodlovchi chiqishini ta'minlaydi

## Data Channel (0x03)

### NOTIFICATION_POST (0x01) / NOTIFICATION_REMOVE (0x02) -- Phone -> Car

id, packageName, appName, title, text, timestamp, progressIndeterminate (byte), progress (int32), progressMax (int32) bilan xabarnoma ma'lumotlari. Avtomobil ID bo'yicha deduplikatsiya qiladi (yangilanishlar mavjudlarini almashtiradi). Xabarnomani tegish VD da egasi ilovani ishga tushiradi.

### NOTIFICATION_CLEAR (0x04) — Car → Phone

Yuklama: `ClearNotificationMessage` — notification id + package name. Avtomobil bitta xabarnomani yopadi; telefon tegishli Android xabarnomasini tozalaydi.

### NOTIFICATION_CLEAR_ALL (0x05) — Car → Phone

Bo'sh yuklama. Avtomobil barcha xabarnomalarni yopadi; telefon barcha faol xabarnomalarni tozalaydi.

### APP_UNINSTALLED (0x06) — Phone → Car

Yuklama: UTF-8 package name. Telefon ilovaning o'chirilganini tasdiqlaydi (`APP_UNINSTALL` javobi sifatida). Avtomobil ilovani ishga tushirish to'ridan olib tashlaydi.

### APP_INFO_DATA (0x07) — Phone → Car

Yuklama: `AppInfoDataMessage` — package name, version name, version code, o'rnatish vaqti, yangilash vaqti, ilova hajmi (bytes). Foydalanuvchi kontekst menyusidan "Ilova haqida ma'lumot" ni tanlaganda, telefon avtomobil tomonidagi dialogda ko'rsatish uchun ilova metama'lumotlarini yuboradi.

### APP_LIST (0x03) -- Phone -> Car

packageName, appName, category (NAV/MUSIC/COMM/OTHER), iconPng (96x96 PNG) bilan o'rnatilgan ilovalar ro'yxati.

### CAR_LOG (0x30) -- Car -> Phone

UTF-8 matn satri. Avtomobil barcha jurnallarni (VideoDecoder va UsbAdbConnection ni o'z ichiga olgan holda) shu kanal orqali yo'naltiradi. Telefon `CarLog` tegi bilan FileLog'ga (`/sdcard/DiLinkAuto/client.log`) yozadi.

### MEDIA_METADATA (0x10) / MEDIA_PLAYBACK_STATE (0x11) -- Phone -> Car

Trek ma'lumoti va ijro holati. Hali faol to'ldirilmaydi.

### MEDIA_ACTION (0x12) -- Car -> Phone

Media boshqaruvi (play/pause/next/previous). MediaSession'ga hali ulangan emas.

### NAVIGATION_STATE (0x20) -- Phone -> Car

Navigatsiya holati ma'lumotlari. Navigatsiya vidjeti integratsiyasi uchun zaxiralangan.

## Input Channel (0x04)

### TOUCH_DOWN (0x01) -- Car -> Phone

Bitta ko'rsatkichli bosish hodisasi. Yuklama TouchEvent (25 bayt):

```
+----------------------+------+
| action                | byte  |  InputMsg.TOUCH_DOWN (0x01)
| pointerId             | int32 |  multi-touch ko'rsatkich ID
| x                     | float |  normallashtirilgan 0.0-1.0
| y                     | float |  normallashtirilgan 0.0-1.0
| pressure              | float |
| timestamp             | int64 |
+----------------------+------+
```

### TOUCH_MOVE (0x02) -- Car -> Phone

Bitta ko'rsatkichli siljitish hodisasi. TOUCH_DOWN bilan bir xil TouchEvent yuklama formati.

### TOUCH_UP (0x03) -- Car -> Phone

Bitta ko'rsatkichli qo'yib yuborish hodisasi. TOUCH_DOWN bilan bir xil TouchEvent yuklama formati.

### TOUCH_MOVE_BATCH (0x04) -- Car -> Phone

```
+----------------------+------+
| count                 | byte  |  ko'rsatkichlar soni
| N × pointer:          |       |
|   pointerId           | int32 |
|   x                   | float |  normallashtirilgan 0.0-1.0
|   y                   | float |  normallashtirilgan 0.0-1.0
|   pressure            | float |
|   timestamp           | int64 |
+----------------------+------+
```

Paketlangan MOVE hodisalari — barcha faol ko'rsatkichlar bir xabarda. Ko'p barmoqli imo-ishoralar uchun tizim chaqiruvlarini kamaytiradi.

### KEY_EVENT (0x10) -- Car -> Phone

Tugma hodisasi (masalan, media tugmalar, navigatsiya tugmalari). Kelajakda foydalanish uchun zaxiralangan.

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

Barcha ko'p baytli butun sonlar va float — **big-endian**.
