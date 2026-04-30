# Avtomobil Server Ilovasi (app-server)

## Umumiy ko'rinish

Avtomobil server ilovasi BYD DiLink axborot-ko'ngilochar tizimida ishlaydi. U bir vaqtda ishlaydigan WiFi (3 ajratilgan ulanish) va USB treklari bilan **parallel ulanish modeli** dan foydalanadi:

1. Track A (WiFi): gateway IP + mDNS aniqlash, control connect (9637), telefon bilan handshake
2. Handshake'dan keyin: video (9638) + kiritish (9639) ulanishlari parallel ochiladi
3. Track B (USB): qurilmalarni skanerlash, USB ADB connect (logSink diagnostikasi bilan), telefon ilovasini ishga tushirish
4. `checkAndAdvance()` har qanday shart o'zgarganda holatni baholaydi
5. H.264 videoni qabul qiladi va avtomobil displeyida renderlaydi (ekrandan tashqari yuzada dekoderni erta ishga tushirish)
6. Teginish hodisalarini oladi va VD kiritish inyeksiyasi uchun kiritish ulanishi orqali telefonga yuboradi

Holatlar: IDLE → CONNECTING → CONNECTED → STREAMING

Avtomobil APK telefon APK ichiga o'rnatilgan. Telefon handshake paytida versiya nomuvofiqligi aniqlanganda avtomobil ilovasini dadb orqali avtomatik yangilaydi. Avtomobil `UPDATING_CAR` xabarini oladi va ko'r-ko'rona qayta ulanish o'rniga holatni ko'rsatadi.

### Ikki Rejimli UI

Avtomobil ilovasi telefon ilovasining yondashuviga mos ravishda ulanish oqimini oqimli tajribadan ikki alohida rejimga ajratadi:

- **Launch mode** (`CarLaunchScreen`): To'liq ekranli, ulanishga yo'naltirilgan. Brendlash, bosqichma-bosqich ulanish yo'riqnomalari, ulanish holati va qo'lda IP kiritishni ko'rsatadi. Navigatsiya paneli yo'q — butun ekran ulanishga bag'ishlangan. Avtomobil ilovasi ishga tushganda ko'rsatiladi va telefondan control ulanishi orqali ilova ikonkalari kelguncha saqlanadi.

- **Streaming mode** (`CarShell` bilan `PersistentNavBar`): Chap navigatsiya paneli (xabarnomalar, bosh sahifa, orqaga, so'nggi ilovalar) va tarkib maydoni (ilovalar to'ri, ko'zgu ko'rinishi, xabarnomalar) bilan tanish maket. `appList` bo'sh emas va holat CONNECTED yoki STREAMING bo'lganda ko'rsatiladi.

O'tish triggeri: telefon control ulanishi orqali `APP_LIST` yuborganda va ulanish holati CONNECTED/STREAMING ga yetganda, UI launch rejimidan streaming rejimiga o'tadi.

## Komponentlar

### CarConnectionService

Parallel shart holat mashinasi va 3 ajratilgan ulanish bilan to'liq ulanish hayotiy siklini boshqaradigan oldingi plan xizmati.

**3-Ulanish Arxitekturasi:**
- `controlConnection`: handshake, heartbeat, ilova buyruqlari, DATA kanali (ilovalar ro'yxati, xabarnomalar, avtomobil jurnallari)
- `videoConnection`: faqat H.264 video kadrlari (phone → car)
- `inputConnection`: faqat teginish hodisalari (car → phone)
- Heartbeat/watchdog faqat control ulanishida; video va kiritishda heartbeat qo'shimcha xarajati yo'q
- Har qanday ulanishning uzilishi kaskadli → to'liq sessiyani to'xtatish

**Parallel Trek Arxitekturasi:**
- `connectionScope`: barcha aniqlash coroutinelar uchun ota Job, uzilganda bekor qilinadi
- Track A va Track B bir vaqtda ishlaydi
- `checkAndAdvance()` har qanday shart o'zgarganda umumiy holatni baholaydi
- Foydalanuvchi uzishi: IDLE holatida qoladi, avto-qayta ulanishsiz (SharedPreferences'da saqlanadi)

**Track A — WiFi:**
- Aniqlash: gateway IP (hotspot/LAN, har 3s qaytariladi) → mDNS → qo'lda IP
- Control connection: telefon TCP:9637 ga NIO bloklanmaydigan SocketChannel connect
- Handshake: ko'rish oynasi o'lchamlari + DPI + appVersionCode + appVersionName + targetFps (60) yuboradi → telefon ma'lumoti + vdServerJarPath + connectionMethod oladi
- Handshake javobida: video (9638) + kiritish (9639) ulanishlarini parallel ochadi, barchasi 3 o'rnatilgandan keyin `wifiReady = true` o'rnatadi
- Video: video ulanishi orqali H.264 kadrlarini oladi, VideoDecoder'ga yuboradi
- Touch: maxsus bir oqimli ijrochi, `sendTouchEvent()` / `sendTouchBatch()` kiritish ulanishi orqali
- Heartbeat: 3s interval, 10s watchdog taym-auti (faqat control ulanishi)
- Backoff: qayta ulanish muvaffaqiyatsizliklarida eksponentsial kechikish

**Track B — USB:**
- `USB_DEVICE_ATTACHED` / `USB_DEVICE_DETACHED` uchun `BroadcastReceiver` ro'yxatdan o'tkazadi
- `MainActivity` USB intent'larni xizmatga yo'naltiradi
- USB qurilmalarini ADB interfeysi uchun skanerlaydi
- UsbAdbConnection orqali USB ADB connect (protocol/ modulida), barcha ADB auth jurnallarini telefon FileLog'iga yo'naltiradigan `logSink` bilan
- Telefon ilovasini ishga tushiradi: `am start -n com.dilinkauto.client/.MainActivity`

**Yangilash Oqimi:**
- Telefon `UPDATING_CAR` yuborsa, avtomobil `updatingFromPhone = true` o'rnatadi, "Updating car app..." holatini ko'rsatadi
- Yangilash paytida video/kiritish ulanishi urinishlarini va qayta ulanish siklini o'tkazib yuboradi
- `pm install -r` dan keyin, avtomobil ilovasi yangidan ishga tushadi

**Holat Oqimlari:**
- `_state`, `_phoneName`, `_appList`, `_notifications`, `_mediaMetadata`, `_playbackState`: UI uchun asosiy holat
- `_videoReady`: birinchi config bo'lmagan video kadr kelganda true
- `_statusMessage`: UI ko'rsatish uchun odam o'qiy oladigan holat
- `_vdStackEmpty` (SharedFlow): telefon VD da activity yo'q deb xabar berganda chiqariladi (bosh sahifaga navigatsiyani ishga tushiradi)

**VD Serverini Ishga Tushirish:**
- `deployVdServer()`: handshake'dagi vdServerJarPath bilan CLASSPATH orqali `shellNoWait`
- Args: `W H DPI PORT EW EH FPS` — FPS handshake'dagi targetFps dan beriladi

**Dekoderni Erta Ishga Tushirish:**
- Birinchi CONFIG video kadrida, VideoDecoder'ni ekrandan tashqari SurfaceTexture'da ishga tushiradi (MirrorScreen'dan oldin)
- MirrorScreen'ning `onSurfaceTextureAvailable` dekoderni to'xtatib, haqiqiy TextureView yuzasi bilan qayta ishga tushiradi
- UI kompozitsiya kechikishi paytida kalit kadrlar yo'qolishini oldini oladi

**Avtomobil Jurnalini Yo'naltirish:**
- `carLogSend()` barcha avtomobil tomoni jurnallarini DATA kanali `CAR_LOG` orqali telefonga yo'naltiradi
- `videoDecoder.logSink` va `adb.setLogSink()` VideoDecoder va UsbAdbConnection jurnallarini bir xil yo'l orqali yo'naltiradi
- Barcha jurnallar telefonning `/sdcard/DiLinkAuto/client.log` faylida ko'rinadi

### VideoDecoder

Surface chiqishi bilan MediaCodec ishlatadigan H.264 dekoderi (GPU-to'g'ridan-to'g'ri renderlash).

- Kadr navbati: 15 kadr — ishga tushirish poygasi va tarmoq jitterini buferlaydi
- `onFrameReceived()`: `start()` chaqirilishidan oldin ham kadrlarni navbatga qo'yadi
- `start()`: avval keshlangan CONFIG'ni beradi, so'ngra navbatni to'kadi
- Navbat to'lganda eng eskisini tashlash: P-kadrlarni tashlashni afzal ko'radi, kalit kadrlar/CONFIG uchun navbatdagi P-kadrlarni chiqaradi
- `KEY_LOW_LATENCY = 1`, `KEY_PRIORITY = 0` minimal dekodlash kechikishi uchun
- CONFIG (SPS/PPS) keshlanadi va dekoder qayta ishga tushganda qayta o'ynatiladi
- Erta ishga tushirishni muvofiqlashtirish uchun `isRunning` xususiyati
- `logSink` qayta chaqiruvi barcha dekoder jurnallarini carLogSend orqali telefonga yo'naltiradi
- Catchup rejimi: navbat chuqurligiga asoslangan to'rt bosqichli tezlashtirish zonasi — normal (0-6 kadr), gentle 1.5x (7-12 kadr, kalit bo'lmagan 3 tadan 1 ini o'tkazadi), medium 2x (13-20 kadr, 2 tadan 1 ini o'tkazadi), aggressive 3x (21+ kadr, 3 tadan 2 ini o'tkazadi). Kalit kadrlar hech qachon o'tkazilmaydi.
- 10+ ketma-ket dequeueInputBuffer tashlashlarida kodekni tozalaydi va CONFIG'ni qayta beradi

### ServerApp

Application klassi. `IMPORTANCE_LOW` bilan `dilinkauto_car_service` xabarnoma kanalini yaratadi.

### RemoteAdbController

dadb kutubxonasidan foydalanadigan to'g'ridan-to'g'ri ADB klienti. Virtual displeyda shell buyruqlari orqali tegish, surish, orqaga, bosh sahifaga va ilovani ishga tushirishni taqdim etadi. Alternativ kiritish yo'li sifatida ishlatiladi.

### CarLaunchScreen

Telefon ulanishi o'rnatilishidan oldin ko'rsatiladigan to'liq ekranli ulanishga yo'naltirilgan composable — navigatsiya paneli yo'q, ilovalar to'ri yo'q.

- DiLink Auto brendlashi (ikonka, sarlavha, shior)
- Rangli indikator nuqtasi (yashil=streaming, to'q sariq=connected/connecting, kulrang=idle) va jonli holat matni bilan ulanish holati kartochkasi
- "How to connect" yo'riqnomalari: 4 raqamlangan qadam (hotspotni yoqish, USB ulash, telefon ilovasini ochish, avto-ulanishni kutish)
- To'g'ridan-to'g'ri ulanish uchun qo'lda IP kiritish
- `appList` bo'sh emas bo'lib, holat CONNECTED/STREAMING yetganda oqim rejimi maketi bilan almashtiriladi

### PersistentNavBar

76dp chap navigatsiya paneli — **faqat oqim rejimida ko'rsatiladi** — quyidagilar bilan:
- Soat displeyi (HH:mm, har 1s yangilanadi)
- Eject tugmasi (uzadi va foydalanuvchi afzalligini saqlaydi)
- Tarmoq holati indikatori
- O'qilmaganlar soni bilan xabarnomalar tugmasi
- Home tugmasi
- Back tugmasi
- So'nggi ilova ikonkalari (ko'pi bilan 5, ilovalar mavjud bo'lmaganda tozalanadi)
- 40dp ikonkalar, 12-14sp matn

Eni H.264 kodlovchi uchun juft ko'rish oynasini kafolatlash uchun hisoblanadi.

### NotificationScreen

- Vaqt tamg'asi bo'yicha saralangan xabarnomalar ro'yxati (eng yangisi birinchi)
- ID bo'yicha deduplikatsiya: yangilanishlar mavjudlarini almashtiradi (progress xabarnomalarini boshqaradi)
- Progress barlar: determinate (to'ldirilgan) va indeterminate (aylanuvchi)
- Tegib ishga tushirish: xabarnomani tegish VD da egasi ilovani ishga tushiradi va ko'zgu ko'rinishiga o'tadi

### Ilovalar To'ri (HomeContent)

Oqim rejimi faol va joriy ekran HOME bo'lganda asosiy tarkib maydoni sifatida ko'rsatiladi:
- `imePadding()` bilan qidiruv maydoni — klaviatura activity'ni turtmaydi, faqat qidiruv satri harakatlanadi
- Manifest'da `windowSoftInputMode="adjustNothing"`
- Displey kengligiga qarab dinamik hisoblanadigan qat'iy to'r ustunlarida 64dp ilova ikonkalari (3-12)
- Ilova nomi matni: bodyLarge
- Alifbo tartibida saralash
- Qo'lda IP kiritish
- Ulanish holati

### LauncherScreen (Eski)

`CarStatusBar`, `SideNavBar` (80dp) va `AppGrid` bilan to'liq integratsiyalashgan ishga tushirgich maketi. Joriy `CarShell` marshrutlashda ishlatilmaydi — faol UI `PersistentNavBar` + `HomeContent`/`MirrorContent`/`NotificationContent` composable'larini ishlatadi.

### RecentAppsState

So'nggi ishga tushirilgan ilovalarni kuzatadi (ko'pi bilan 5), SharedPreferences'da saqlanadi. `pruneUnavailable()` ilovalar ro'yxati yangilanganda endi mavjud bo'lmagan ilovalarni o'chiradi.

### NavBarComponents

Alohida navigatsiya paneli vidjet composable'lari: `ClockDisplay` (har 1s yangilanadi), `NetworkInfo` (connected/disconnected holati), `RecentAppIcon` (40dp, faol holat yoritish bilan), `NavActionButton` (40dp ikonkalar, 12sp belgilar).

### CarTheme

Kategoriya bo'yicha ilova qoplama ranglari bilan Material3 qorong'i rang sxemasi (`CarDark`): Navigation (yashil), Music (pushti), Communication (ko'k), Other (kulrang).

### Avtomobil Displeyi Haqida Ma'lumot

BYD DiLink 3.0 da sinovdan o'tkazilgan:
- Ekran: 1920x990 @ 240dpi
- Ko'rish oynasi: ~1806x990 (76dp navigatsiya panelidan keyin)
- VD: ~3282x1800 @ 480dpi (kodlash uchun ~1806x990 gacha GPU-masshtablangan)

## Bog'liqliklar

- Jetpack Compose + Material 3
- Protocol module (telefon ilovasi bilan umumiy, UsbAdbConnection + AdbProtocol + VideoConfig o'z ichiga oladi)
- dadb 1.2.10 (TCP ADB zaxirasi)
