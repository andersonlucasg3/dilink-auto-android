# Progress Traker

Joriy versiya: **v0.15.0** (barqaror)
So'nggi yangilanish: 2026-04-28

## Bosqichlar

### v0.15.0 (2026-04-28)

- **Phone service auto-start**: `ConnectionService` telefon ilovasi ochilganda avtomatik ravishda ishga tushadi (masalan, avtomobil USB ADB orqali), Start tugmasini qo'lda bosish zaruratini yo'qotadi. ✅ Done
- **Car no longer clears phone task**: Avtomobilning USB ADB telefon ishga tushirishidan `--activity-clear-task` olib tashlandi. Agar telefon ilovasi allaqachon ochiq bo'lsa, avtomobil uni buzmasdan oldinga siljiydi. ✅ Done
- **Share Logs button**: Asosiy ekrandagi "Share Logs" tugmasi `/sdcard/DiLinkAuto/` ichidagi barcha `*.log` fayllarini arxivlab, Android almashish varag'i orqali almashadi. `FileLog.zipLogs()` `dilinkauto-logs.zip` yaratadi. ✅ Done
- **Encoder configuration**: Kengroq qurilma mosligi uchun 8Mbps CBR Main profiliga sozlandi. Backpressure qo'shildi (yozish navbati 6 kadrdan oshganda kalit bo'lmagan kadrlarni tashlaydi). ✅ Done
- **VideoDecoder catchup**: Yumshoqroq kechikish tiklash uchun to'rt bosqichli tezlashtirish zonasi (normal, gentle 1.5x, medium 2x, aggressive 3x). ✅ Done
- **French translation**: Mavjud 7 tilga fransuz tili (fr) qo'shildi (endi jami 8). ✅ Done
- **Update check on app open**: O'z-o'zini yangilash tekshiruvi ilova ochilganda darhol ishga tushadi, yangilash xabarnomasi va qayta tekshirish tugmasi bilan. ✅ Done
- **Distribution channel selector**: O'z-o'zini yangilash uchun barqaror relizlar va dev oldindan relizlar o'rtasida tanlash kartochkasi. ✅ Done
- **CarLaunchScreen redesign**: Keng avtomobil displeylari uchun optimallashtirilgan ikki ustunli maket. ✅ Done
- **Phone app UI refactor**: Asosiy ekran qayta tashkil qilindi, o'rnatish oqimi xatolari tuzatildi. ✅ Done
- **Onboarding improvements**: Avtomobil sozlash shartlari, yaxshilangan o'rnatish progressi UI, yaxshilangan How to Connect kartochkasi. ✅ Done
- **Car UI two-mode separation**: Ishga tushirish ekrani (to'liq ekranli, ulanishga yo'naltirilgan) va oqim rejimi (navigatsiya paneli + tarkib). Ilovalar ro'yxati kelganda silliq o'tish. ✅ Done
- **Video artifact fixes**: Aqlli dekoder tashlashlari + bosqichli catchup + kodlovchi backpressure vizual artefaktlarni yo'q qiladi. ✅ Done
- **Touch input fixes**: Belgilangan 480dpi VD server DPI da to'g'ri koordinata moslashtirish, MOVE da inkremental teginish jo'natish, tegish imo-ishorasi va qo'lda IP tuzatishlari. ✅ Done
- **Screen restore and network stability**: USB uzilgandan keyin displeyni tiklash, tarmoq qayta chaqiruv yaxshilashlari. ✅ Done
- **Internationalization**: Barcha yangi UI satrlari 8 tilga tarjima qilindi (en, pt-BR, ru, be, fr, kk, uk, uz). ✅ Done
- **CI/CD automation**: 6 ajratilgan ish jarayoni — validatsiya (`build.yml`, `build-develop.yml`), `-dev` teglarida oldindan reliz (`build-pre-release.yml`), `vX.Y.Z` teglarida reliz (`build-release.yml`), main→develop teskari sinxronlash (`sync-main-to-develop.yml`), va avtonom issue-agent (`issue-agent.yml`). ✅ Done

### v0.14.0

- **Shared version source**: Versiya kodi/nomi endi gradle.properties da — ikkala ilova uchun bitta tahrir.
- **MAX_PAYLOAD_SIZE 2MB → 128MB**: 136+ PNG ikonkalari bilan ilovalar ro'yxati 2MB dan oshib, ProtocolException va ulanish uzilishlarini keltirib chiqardi.
- **Display restore fix**: `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` `ACQUIRE_CAUSES_WAKEUP` bilan USB uzilgandan keyin displeyni tiklaydi, hatto VD serveri tozalashsiz o'lsa ham.
- **POCO F5 compatibility**: `FLAG_KEEP_SCREEN_ON` oqim rejimida ekran qulfini oldini oladi. Xiaomi 17 Pro Max bilan POCO F5 da teginish kiritishi ishlashi tasdiqlangan.
- **Car-side touch logging**: MirrorScreen teginish hodisalari va sendTouchEvent muvaffaqiyati disk raskadrovka uchun jurnalga yoziladi.
- **Developer credit in About**: "Developed with ❤" GitHub havolasi bilan barcha 7 tilda.

### v0.13.1 — First Release (2026-04-26)

- **Onboarding flow**: Birinchi ishga tushirish ruxsatlarini yo'naltirilgan sozlash (All Files, Battery, Accessibility, Notifications). Ruxsatlarni avtomatik aniqlaydi, dialog uslubidagi sozlamalar uchun zaxira so'rov.
- **Self-update (UpdateManager)**: GitHub Releases API tekshirish, progress bilan APK yuklash, tizim paket o'rnatuvchisi orqali o'rnatish. 6 soatlik tanaffus.
- **Main view reorganization**: Asosiy ekran kundalik foydalanishga yo'naltirilgan (ulanish qo'llanmasi, holat, start/stop, yangilashlar). Ruxsatlar, avtomobilga o'rnatish, about va xayriya havolalari bilan sozlamalar ekrani.
- **USB + WiFi install on car**: Parallel subnet skaneri avtomobil ADB uchun barcha 254 IP ni tekshiradi. ARP/neighbor/gateway aniqlash bilan birlashtirilgan. USB xost urinib ko'rildi, lekin avtomobil USB-A faqat xost.
- **VD server now Kotlin Gradle module**: :protocol va kotlinx-coroutines ga bog'liq. NioReader, FrameCodec.writeAll umumiy foydalanadi.
- **Performance**: Kodlovchida har bir kadrga oraliq ByteArray ajratish yo'q qilindi. NioReader boshlang'ich sig'imi 256KB. isKeyFrame FrameData'da keshlanadi.
- **Encoder**: CBR 8Mbps, Main profili, sozlanadigan FPS (default 30, avtomobil 60 so'raydi), PRIORITY 0 (real-time). I_FRAME_INTERVAL=1s. Statik tarkib uchun `repeat-previous-frame-after`=500ms.
- **Donations**: GitHub Sponsors va Pix (Brazil) README va ilova sozlamalarida nishonlar.
- **Adaptive vector icon**: Simsiz signallar bilan avtomobil silueti, ikkala ilovaga ham qo'llandi.
- **Internationalization**: Satr resurslari English, Portuguese (pt-BR), Russian (ru), Belarusian (be), French (fr), Kazakh (kk), Ukrainian (uk), va Uzbek (uz) tillarida.
- **Release signing**: Kuchli parol bilan belgilangan keystore. CI qurmalari GitHub Secrets orqali imzolangan reliz APK chiqaradi.

### v0.13.0 — USB ADB Auth Fix (2026-04-25)

Asosiy sabab topildi va tuzatildi: `Signature.getInstance("SHA1withRSA")` ADB AUTH_TOKEN ni ikki marta heshlaydi. ADB ning 20-baytli tokeni oldindan heshlangan qiymat — AOSP `RSA_sign(NID_sha1)` uni allaqachon heshlangan deb hisoblaydi. Endi qo'lda SHA-1 DigestInfo ASN.1 prefiksi bilan `NONEwithRSA` ishlatiladi (oldindan heshlangan imzolash). "Always allow" endi to'g'ri saqlanadi — AUTH_SIGNATURE dialogsiz qayta ulanishda qabul qilinadi.

### v0.13.0 — Display Power + Key Encoding (2026-04-25)

- **Display power via SurfaceControl (Android 14+)**: `DisplayControl` ni `/system/framework/services.jar` dan `ClassLoaderFactory.createClassLoader()` + `android_servers` native kutubxonasi orqali yuklaydi. Reflection muvaffaqiyatsiz bo'lsa, `cmd display power-off/on` zaxirasi.
- **Screen restore on disconnect**: Telefonning `VirtualDisplayClient.disconnect()` VD server jarayoni tozalashdan oldin o'ldirilganda xavfsizlik tarmog'i sifatida `cmd display power-on 0` + `KEYCODE_WAKEUP` ni bajaradi.
- **ADB key encoding rewrite**: AOSP namunasi bilan aniq mos keladigan `encodePublicKey()` qayta yozildi — konstantalar tuzatildi, aniq `bigIntToLEPadded()`, struct sarlavhasi jurnalga yozish.
- **Decoder catchup**: Navbat `100ms * TARGET_FPS / 1000` kadrdan oshganda (60fps da 6), har ikkinchi kalit bo'lmagan kadrni o'tkazib yuboradi. Tasvir sakramasdan, asta-sekin quvib yetib, 2x tezlikda harakatlanadi.
- **Car log buffer**: 200 → 10,000 xabar. USB ADB auth jurnallari endi control ulanishi ularni tozalamaguncha saqlanadi.

### v0.12.5 — Connection Stability (2026-04-24)

- **Smart network callback**: `onLost` endi uzilgan tarmoq ulanishni tashiydigan tarmoq ekanligini tekshiradi. Bog'liq bo'lmagan uzilishlarni e'tiborsiz qoldiradi (mobil ma'lumotlar sikli). Avval har qanday tarmoq uzilishi oqim sessiyasini o'ldirar edi.
- **USB ADB auth diagnostics**: To'liq auth oqimi jurnalga yozish carLogSend → telefon FileLog orqali yo'naltirildi. AUTH_SIGNATURE har safar rad etilishini aniqladi (telefon adbd saqlangan kalitni tanimaydi). Tekshiruv ostida.
- **AUTH_RSAPUBLICKEY key preview logging**: Standart ADB formati bilan solishtirish uchun telefonga yuborilgan ochiq kalitning birinchi/oxirgi baytlarini jurnalga yozadi.

### v0.12.0–v0.12.4 — Bug Fixes & Polish (2026-04-24)

- **Touch input fixed**: `handleInputFrame` `Dispatchers.IO` da ishlov beriladi (Main bo'lgan, localhost soket yozuvida `NetworkOnMainThreadException` keltirib chiqargan)
- **VD server NIO command reader**: Cheksiz sikl tuzatildi — switch ichidagi `break` faqat switch'dan chiqdi, tahlil siklidan emas. Endi `break parseLoop;` belgilangan chiqish ishlatiladi.
- **App launch dedup**: `am start` ichidan `--activity-clear-task` olib tashlandi. Mavjud ilovalar qayta ishga tushish o'rniga davom etadi.
- **Bitrate**: 8Mbps CBR o'rnatildi (qurilma mosligi uchun keyingi relizlarda 12Mbps dan o'zgartirildi).
- **FPS configurable**: HandshakeRequest'ga `targetFps` maydoni qo'shildi. Avtomobil 60fps so'raydi. VD serveri FPS ni buyruq satri argumenti sifatida qabul qiladi, kodlovchi `KEY_FRAME_RATE` va `FRAME_INTERVAL_MS` uchun ishlatadi.
- **Nav bar**: 72dp → 76dp, ikonkalar 32dp → 40dp, qator balandligi 52dp → 60dp, matn 12sp → 14sp.
- **Launcher app icons**: 40dp → 64dp, to'r katakchalari 140dp → 160dp, matn bodyMedium → bodyLarge.
- **Search bar keyboard**: TextField'da `windowSoftInputMode="adjustNothing"` + `imePadding()`. Klaviatura activity'ni turtmaydi, faqat qidiruv satri harakatlanadi.
- **Notifications**: ID bo'yicha deduplikatsiya (progress yangilanishlari mavjudlarini almashtiradi), progress bar qo'llab-quvvatlash (determinate + indeterminate), VD da egasi ilovani tegib ishga tushirish + ko'zgu ko'rinishiga o'tish.
- **Recent apps**: `pruneUnavailable()` ilovalar ro'yxati yangilanganda telefonda endi mavjud bo'lmagan ilovalarni o'chiradi.
- **USB ADB key storage**: Ustuvorlik tartibi: `/sdcard/DiLinkAuto/` → `getExternalFilesDir` → `getFilesDir`. Migratsiya barcha joylarni qidiradi.
- **Update flow**: Telefon `UPDATING_CAR` xabarini yuboradi. Avtomobil "Updating car app..." holatini ko'rsatadi va qayta ulanmaydi.
- **Update flow crash fix**: `updatingFromPhone` bayrog'i o'rnatilganda avtomobil video/kiritish ulanishini o'tkazib yuboradi.
- **VideoDecoder/UsbAdbConnection logSink**: Avtomobil tomoni jurnallari protokol orqali telefon FileLog'iga yo'naltiriladi.

### v0.11.0–v0.11.3 — Non-Blocking Pipeline + Encoder Fix (2026-04-24)

- **VideoConfig**: `TARGET_FPS` va `FRAME_INTERVAL_MS` umumiy konstantalari. Barcha video yo'li kutish/so'rovlari kadr intervali bilan cheklangan.
- **SurfaceScaler periodic re-draw**: Har doim har bir kadr intervalida `glDrawArrays + eglSwapBuffers` chaqiradi, hatto VD dan yangi kadr bo'lmasa ham. Faqat yangi kadr mavjud bo'lganda `updateTexImage` chaqiradi. Kodlovchini statik tarkibda oziqlantiradi.
- **VD server NIO**: Bloklanadigan `DataOutputStream/DataInputStream` NIO yozish navbati (`ConcurrentLinkedQueue<ByteBuffer>`) + Selector asosidagi buyruq o'qish vositasi bilan almashtirildi. Konveyerda bloklanadigan I/O yo'q.
- **Encoder poll**: `dequeueOutputBuffer` taym-auti 100ms dan `FRAME_INTERVAL_MS` gacha kamaytirildi (60fps da 16ms).
- **VideoDecoder queue poll**: 100ms → `FRAME_INTERVAL_MS`.
- **NioReader select timeout**: 100ms → `FRAME_INTERVAL_MS` (konstruktor parametri orqali sozlanadi).
- **Connection writer park**: 50ms → `FRAME_INTERVAL_MS`.
- **VirtualDisplayClient accept loop**: 100ms → `FRAME_INTERVAL_MS`.
- **VideoDecoder early start**: Birinchi CONFIG kadr kelganda ekrandan tashqari SurfaceTexture'da ishga tushadi (MirrorScreen'dan oldin). MirrorScreen dekoderni haqiqiy TextureView yuzasi bilan qayta ishga tushiradi.
- **VideoDecoder queue**: 3 → 30 kadr. Kadrlar `start()` chaqirilishidan oldin ham navbatga qo'yiladi.
- **FileLog**: Fayl asosidagi jurnal yuritish (`/sdcard/DiLinkAuto/client.log`) HyperOS logcat filtrlashini aylanib o'tadi. Rotatsiya: `client-YYYYMMDD-HHmmss.log` sifatida arxivlaydi, ko'pi bilan 10 ta saqlaydi.

### v0.10.0 — 3-Connection Architecture (2026-04-24)

Yagona multiplekslangan TCP ulanishi video to'xtashiga olib keladigan kanallararo interferensiyani yo'q qilish uchun 3 ajratilgan ulanishga bo'lindi:
- **Control connection** (port 9637): handshake, heartbeat, ilova buyruqlari, DATA kanali
- **Video connection** (port 9638): faqat H.264 CONFIG + FRAME (phone → car)
- **Input connection** (port 9639): faqat teginish hodisalari (car → phone)

Har bir ulanish mustaqil SocketChannel, NioReader va yozish navbati bilan o'z `Connection` nusxasiga ega. Heartbeat/watchdog faqat controlda.

### v0.9.2 — Diagnostic Build (2026-04-23)

~420 kadrdan keyin video kadr to'xtashini tekshirish uchun keng qamrovli jurnalga yozish:
- **Video relay loop**: readByte oldidan/keyin, yuklama hajmi, noma'lum msgTypes jurnalga yozadi
- **NioReader**: channel.read() 0 qaytarganda jurnalga yozadi (bufer holati bilan har bir 100-chi hodisa)
- **Connection writer**: har 60 video kadrni jurnalga yozadi (soni, hajmi, navbat chuqurligi, to'xtashlar), yozish to'xtashlarini jurnalga yozadi
- **Writer stall fix**: writeBuffersToChannel da `Thread.yield()` → `delay(1)` — IO oqimini band kutish o'rniga korutin puliga qaytaradi (tekshiruv xulosasi: Thread.yield video reley korutinini ochlikka uchratdi)
- **Frame listeners**: video bo'lmagan kadr ishlovchilari asinxron ishlov beriladi (`scope.launch`), og'ir ishlov (ilovalar ro'yxatini dekodlash) o'qish vositasini TCP to'kishdan to'sib qo'ymaslik uchun

### v0.9.0-v0.9.1 — Write Stall Investigation (2026-04-23)

Video to'xtashining asosiy sababini tekshirish. TCP bufer hajmini jurnalga yozish, yozish to'xtash diagnostikasi qo'shildi.
- Ilovalar ro'yxatini yuborish paytida TCP jo'natish buferi 108,916 bayt qoldiqda qotib qolishi tasdiqlandi
- Video kadrlarning o'zida nol yozish to'xtashlari bor (video paytida navbat=0)
- USB ADB kaliti barqaror ekani tasdiqlandi (LOADED fp=c4e88a05) — takrorlanuvchi auth HyperOS xatti-harakati

### v0.8.4-v0.8.8 — Bug Fixes + Log Routing (2026-04-23)

- **Car log routing**: Barcha avtomobil tomoni `Log.*` chaqiruvlari `carLogSend()` orqali yo'naltiriladi, u DATA kanali `CAR_LOG` orqali telefonga yuboradi. Telefon logcat'da `CarLog` tegi bilan jurnalga yozadi. Ulanish o'rnatilguncha 200 xabargacha bufer.
- **VD server launch reverted** `shellNoWait` + `exec app_process` (v0.6.2 yondashuvi). `setsid`/`nohup` ajratish localhost ulanishini buzdi. VD serveri USB uzilganda o'ladi, lekin qayta ulanganda tiklanadi.
- **VD ServerSocket**: `startListening()` sinxron ochiladi, `waitForVDServer()` allaqachon kutayotgan bo'lsa o'tkazib yuboradi
- **USB ADB key persistence**: `getExternalFilesDir` yozilish tekshiruvi bilan + `getFilesDir` dan migratsiya + barmoq izi jurnalga yozish
- **ClosedSelectorException**: yozuvchi va NioReader da `selector.isOpen` tekshiruvlari
- **Infinite recursion fix**: ommaviy Log→carLogSend almashtirish tasodifan carLogSend'ni o'ziga tegdi

### v0.8.3 — Final Polish + VD Wait Fix + USB Key Diagnostic (2026-04-23)

Yakuniy bosqich + xatolik tuzatishlari:
- **VD wait guard**: `waitForVDServer` allaqachon kutayotgan bo'lsa o'tkazib yuboradi — qayta ulanish paytida avtomobil bir nechta handshake yuborganda ServerSocket yopish/qayta ochishni oldini oladi (VD serveri ulana olmasligining asosiy sababi, 45s ichida 4x `startListening` ko'rsatgan telefon logcat orqali tasdiqlandi)
- **USB key diagnostic**: Avtomobil kalit ma'lumotini (`LOADED`/`GENERATED` + barmoq izi + yo'l) USB ADB connect dan keyin telefonda `/data/local/tmp/car-adb-key.log` fayliga yozadi. Auth kalit o'zgargani yoki telefon "Always allow" ni saqlamagani sababli takrorlanishini diagnostika qilishga imkon beradi.
- **M3**: Paketlangan multi-touch — yangi `TOUCH_MOVE_BATCH` (0x04) xabar turi barcha ko'rsatkichlarni bir kadrda tashiydi. MOVE hodisalari avtomobil tomonida paketlanadi, telefon tomonida paketdan chiqariladi. N-barmoqli imo-ishoralar uchun tizim chaqiruvlarini N*60/sek dan 60/sek gacha kamaytiradi.
- **M10**: Eject holati SharedPreferences da saqlanadi — avtomobil ilovasini o'ldirish/qayta ishga tushirishdan omon qoladi. USB qayta ulanganda yoki ACTION_START da tozalanadi.
- **L4**: NioReader to'g'ridan-to'g'ri emas, heap ByteBuffer ishlatadi — deterministik GC tozalash.

### v0.8.2 — Polish + VD ServerSocket + USB Key Persistence (2026-04-23)

6 sayqallash tuzatishi + 2 muhim tuzatish:
- **Hotfix**: VirtualDisplayClient `startListening()` (sinxron bog'lash) + `acceptConnection()` (asinxron kutish) ga bo'lindi. ServerSocket handshake javobidan OLDIN ochiladi — VD serverining localhost:19637 ga ulana olmasligini tuzatadi.
- **USB key persistence**: Kalit saqlash `getExternalFilesDir` yozilish tekshiruvi bilan + `getFilesDir` dan migratsiyani ishlatadi. Ulanishlar o'rtasida kalit o'zgarishini diagnostika qilish uchun har bir ulanishda barmoq izi jurnalga yoziladi.
- **M12**: `checkStackEmpty` mo'rt `sed` bo'lim tahlili o'rniga oddiy `grep -E` ishlatadi
- **L2**: VD serveri localhost soket buferlari 256KB o'rnatildi
- **L3**: Dekoder vaqt tamg'alari uchun `System.nanoTime()/1000` ishlatadi (belgilangan 33ms o'sish bo'lgan)
- **L5**: `UsbAdbConnection.readFile()` o'qish sikli + try-with-resources ishlatadi
- **L6**: SurfaceScaler HandlerThread to'xtatishda to'g'ri yakunlanadi
- **L7**: Ilova ikonkalari 48x48 dan 96x96px gacha kattalashtirildi

### v0.8.1 — Touch + Decoder Performance + Hotfixes (2026-04-23)

4 unumdorlik optimallashtirish + 2 qulash tuzatishi:
- **M2**: `checkStackEmpty()` fon oqimida ishlaydi — buyruq o'qish vositasi Back bosilgandan keyin 300ms+ bloklanmaydi
- **M4**: VD serverida oldindan ajratilgan `PointerProperties[10]` + `PointerCoords[10]]` pul'lari — har bir teginishdagi GC bosimini yo'q qiladi
- **M5**: Dekoder kadr navbati 6 dan 3 gacha kamaytirildi (200ms → 100ms kechikish chegarasi)
- **M6**: `cmd display power-off 0` "fire-and-forget" oqimida ishlaydi — teginish inyeksiyasi yo'lidan titroq olib tashlandi
- **Crash fix**: Connection yozuvchi va NioReader da `ClosedSelectorException` — `selector.isOpen` tekshiruvlari va catch bloki qo'shildi. Poyga: `disconnect()` o'qish/yozish korutinlari hali bajarilayotganda selektorlarni yopadi.
- **Bug fix**: `startConnection()` ichidagi `usbConnecting` qayta o'rnatish endi `usbAdb == null` bilan ham himoyalangan (USB auth poygasining ikkinchi joyi, birinchisi v0.7.3 da tuzatilgan)

### v0.8.0 — I/O Pipeline Performance (2026-04-23)

3 I/O unumdorlik optimallashtirish + hotfix:
- **H2**: Gathering writes — `channel.write(ByteBuffer[])` sarlavha+yuklamani bitta tizim chaqiruviga/TCP segmentiga birlashtiradi
- **H3**: Video releyi aniq o'lchamdagi `ByteArray(size)` ni to'g'ridan-to'g'ri ajratadi — oraliq `relayBuf` + `copyOf` olib tashlandi
- **M1**: VD serveri DataOutputStream'ni `BufferedOutputStream(65536)` ichiga o'raydi — kichik localhost yozuvlarini birlashtiradi
- **Hotfix**: `waitForVDServer()` handshake javobini yuborishdan OLDIN chaqiriladi — :19637 da ServerSocket avtomobil VD serverini joylashtirishdan oldin ochiq ekanligini kafolatlaydi (v0.7.4 regressiyasi: ketma-ketlik VD kutishni javobdan keyin qo'yib, VD serveri ulanish muvaffaqiyatsizligiga olib keldi)

### v0.7.4 — Write Queue + Flow Sequencing (2026-04-23)

Yozish arxitekturasining o'zgarishi + oqim yaxshilashlari:
- **Write queue**: `synchronized(outputLock)` qulfsiz `ConcurrentLinkedQueue` + maxsus yozish korutini bilan almashtirildi. Yozuvchi TCP jo'natish buferi to'lganda `delay(1)` ishlatadi (IO oqimini pulga qaytaradi). Yozish paytida boshqa korutinlarni bloklash yo'q.
- **H10**: Handshake → auto-update → VD deploy ketma-ketlashtirilgan. Auto-update initsializatsiyani to'xtatadi, uzadi, avtomobil qayta ulanishini kutadi.
- **H11**: Progressiv avtomobil holat xabarlari: "Preparing..." → "Starting..." → "Waiting for video stream..."
- **H12**: Avtomobil USB ADB connect paytida "Check phone for authorization dialog" ko'rsatadi
- **Bug fix**: VD serveri yaratilgandan keyin VD da home activity ishga tushiradi — kodlovchi tarkibni darhol oladi
- **Bug fix**: `usbConnecting` faqat `usbAdb == null` bo'lganda qayta o'rnatiladi — takrorlanuvchi USB-ADB auth dialoglarini oldini oladi

### v0.7.3 — Network Resilience + HyperOS Freeze Fix (2026-04-23)

Tarmoq chidamliligi + logcat dalillari orqali aniqlangan muhim HyperOS tuzatishi:
- **Battery exemption**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — ekran o'chirilganda HyperOS "greeze" mijoz ilovasini muzlatishini oldini oladi. "frames only during touch" xatosining asosiy sababi: VD serveri (shell jarayoni) 960+ kadr ishlab chiqardi, lekin mijoz ilovasining NioReader'i OT quvvat boshqaruvi tomonidan muzlatildi. So'rov birinchi ishga tushirishda ko'rsatiladi.
- **C4/M11**: Avtomobil WiFi tregi endi gateway IP ni har 3s takrorlaydi (bir martalik bo'lgan). Avtomobilda `ConnectivityManager.NetworkCallback` qo'shildi, u WiFi mavjud bo'lganda WiFi tregini qayta ishga tushiradi. USB ulangandan keyin yoqilgan hotspotni boshqaradi.
- **H9**: Telefon CONNECTED/STREAMING holatida tarmoq uzilganda faol uzadi (bo'lgan: heartbeat taym-auti uchun 10s kutish). `onLost` → `cleanupSession()` → tinglash sikli qayta ishga tushadi. `onAvailable` faqat WAITING da qayta o'rnatadi (faol ulanishlarni buzmasdan).
- **Bug fix**: VD serveri yaratilgandan keyin VD da home activity ishga tushiradi (`am start --display <id> HOME`) — kodlovchi darhol tarkibga ega bo'lishini kafolatlaydi.
- **Bug fix**: `usbConnecting` faqat `usbAdb == null` bo'lganda qayta o'rnatiladi — takrorlanuvchi USB-ADB auth dialoglarini oldini oladi.

### v0.7.2 — Car-Side Stability + Selector Fix (2026-04-23)

5 barqarorlik tuzatishi + muhim NioReader tuzatishi:
- **H1**: NioReader da `delay(1)` so'rovi **Selector** bilan almashtirildi — `selector.select(100)` ma'lumotlar kelganda epoll orqali bir zumda uyg'onadi. Video oqimsizligini tuzatadi (Android 10 avtomobilida `delay(1)` 10-16ms olgani uchun kadrlar faqat teginish paytida oqardi). Uzishdan toza to'xtatish uchun `wakeup()`/`close()` qo'shildi.
- **H7**: `wifiReady`, `usbReady`, `vdServerStarted`, `usbConnecting` da `@Volatile` — CONNECTING holatida qotib qolishni oldini oladi
- **H8**: `VideoDecoder.stop()` `codec.stop()` oldidan uzatish oqimini kutadi (2s taym-aut) — native crash oldini oladi
- **M7**: VD serveridagi qo'sh `cleanup()` olib tashlandi — qo'sh bo'shatishdagi IllegalStateException oldini oladi
- **M8**: `cleanupSession()` `_serviceState` ni WAITING ga qayta o'rnatadi — qayta ulanish kechikishi paytida eskirgan UI oldini oladi
- **M9**: `onCreate()` statik `activeConnection` va `_serviceState` ni tozalaydi — xizmat qayta ishga tushganda eskirgan holatni oldini oladi

### v0.7.1 — Critical Bug Fixes (2026-04-23)

Keng qamrovli ko'rib chiqishdan 6 muhim/yuqori tuzatish:
- **C1**: `writeAll()` 5s yozish taym-auti — to'la jo'natish buferida tizim qotib qolishini oldini oladi
- **C3**: Avto-yangilash urinish bayrog'i — cheksiz yangilash/qayta ishga tushirish siklini buzadi
- **C5**: WakeLock 4h avto-bo'shatish — g'ayritabiiy chiqishda batareya zaryadining oqishini oldini oladi
- **C6**: VirtualDisplayClient channel/reader da `@Volatile` + taym-aut bilan himoyalangan yozuvlar
- **H5**: `Connection.connect()` try/catch — bekor qilishda SocketChannel ni yopadi
- **H6**: Uzish tinglovchisi try/catch ichiga o'ralgan — istisno tarqalishini oldini oladi

### v0.7.0 — Full NIO + Service Fix (2026-04-23)

Barcha soket operatsiyalari bloklanmaydigan NIO ga o'tkazildi. mDNS ro'yxatdan o'tkazish endi tinglash siklini bloklamaydi. Versiya kodi PackageManager orqali bajarilish vaqtida o'qiladi.

**Changes:**
- **NioReader**: SocketChannel uchun yangi bloklanmaydigan buferlangan o'qish vositasi (delay(1) so'rov, korutin-kooperativ)
- **Connection.kt**: SocketChannel butun vaqt davomida bloklanmaydigan holatda qoladi — connect/accept dan keyin configureBlocking(true) yo'q
- **FrameCodec.kt**: NIO `readFrame(NioReader)` va `writeFrameToChannel(SocketChannel, Frame)` usullari qo'shildi
- **VirtualDisplayClient.kt**: NIO o'qishlar (NioReader) + ByteBuffer yozuvlar, kanal bloklanmaydigan holatda qoladi
- **VirtualDisplayServer.java**: Ulanish uchun NIO SocketChannel (qayta urinish bilan bloklanmaydigan finishConnect)
- **ConnectionService.kt**: probePort() NIO ga o'tkazildi; mDNS ro'yxatdan o'tkazish 5s taym-aut bilan fonda ishga tushiriladi (WiFi siz xizmat ishga tushmasligini tuzatadi)
- **Version code**: `APP_VERSION_CODE` konstantasi olib tashlandi — ikkala ilova ham `PackageManager.getPackageInfo()` orqali bajarilish vaqtida versionCode o'qiydi

### v0.6.2 — Parallel Connection Model + Auto-Update (2026-04-23)

Katta arxitektura qayta yozish: parallel WiFi + USB treklari, NIO bloklanmaydigan soketlar, telefon orqali avto-yangilash, IInputManager orqali multi-touch.

**Working:**
- **Parallel connection state machine**: WiFi aniqlash + USB ADB bir vaqtda ishlaydi, VD ikkalasi ham tayyor bo'lganda joylashtiriladi
- **Auto-update**: Telefon handshake paytida eskirgan avtomobil ilovasini aniqlaydi, WiFi ADB (dadb) orqali yangilashni yuboradi
- **Phone deploys VD JAR**: Ishga tushirishda `/sdcard/DiLinkAuto/vd-server.jar` ga chiqariladi (CRC32 tekshiriladi)
- **Car APK embedded in phone**: Qurish tizimi avtomobil APK sini telefon assets ichiga yig'adi
- **NIO non-blocking sockets**: Barcha accept/connect `ServerSocketChannel`/`SocketChannel` ishlatadi — zumda bekor qilish, EADDRINUSE yo'q
- **Multi-touch input**: `ServiceManager → IInputManager` orqali to'g'ridan-to'g'ri MotionEvent inyeksiyasi (tegish, surish, chimchilashni qo'llab-quvvatlaydi)
- **Screen power management**: Oqim rejimida `cmd display power-off 0`, proximity/lift wake o'chirilgan, teginish inyeksiyasidan keyin cheklangan qayta o'chirish
- **State machine recovery**: `connectionScope` uzilganda barcha korutinlarni bekor qiladi, eksponentsial backoff bilan qayta ulanish
- **User disconnect**: Eject tugmasi qayta ulanishni to'xtatadi (IDLE holatida qoladi)
- **App search**: Ilovalar to'rining pastki qismida qidiruv maydoni, ilovalar alifbo tartibida saralangan
- **Notification panel**: Navigatsiya panelidagi qo'ng'iroq ikonkasi sanagich bilan
- **72dp nav bar**: Avtomobil displeylari uchun kattaroq ikonkalar (32dp) va matn (12sp)
- **Handshake version check**: HandshakeRequest da `appVersionCode` maydoni, HandshakeResponse da `vdServerJarPath`
- **Network change handling**: Telefon tarmoq interfeysi o'zgarganda tinglash siklini qayta o'rnatadi (hotspot o'tish)
- **H.264 encoding**: 8Mbps CBR, High profili, past kechikish rejimi
- **Handshake timeout**: To'g'ri bekor qilish bilan 10s taym-aut (eskirgan taym-autlarsiz)

**Architecture changes from v0.5.0:**
- Avtomobildan hotspot SSID so'rov/WiFi avto-ulanish olib tashlandi (soddalashtirildi)
- UsbAdbConnection + AdbProtocol protocol moduliga ko'chirildi (ikkala ilova uchun umumiy)
- VD serveri telefonga ulanadi (teskari ulanish) telefon VD serveriga ulanish o'rniga
- VD serveri telefon uzilganda chiqadi (bir martalik, avtomobil kerak bo'lsa qayta joylashtiradi)
- Telefon VD JAR ni umumiy xotiraga chiqaradi, avtomobil yo'lni handshake dan o'qiydi

### v0.5.0 — USB ADB + Automated Setup (2026-04-22)

Katta arxitektura o'zgarishi: **avtomobil** VD serverini telefonga USB ADB orqali joylashtiradi. Wireless Debugging yo'q qilindi.

### v0.4.0 — GPU-Scaled VirtualDisplay (2026-04-22)

Ilovalar telefonning tabiiy DPI (480dpi) da renderlanadi, GPU avtomobil ko'rish oynasiga masshtablaydi. SurfaceScaler EGL/GLES konveyeri.

### v0.3.0 — Persistent Navigation Bar (2026-04-21)

Doim ko'rinadigan chap navigatsiya paneli, TextureView, haqiqiy ilova ikonkalari bilan avtomobil UI.

### v0.2.0–v0.2.3 — Virtual Display Foundation (2026-04-21)

VD yaratish, self-ADB, chidamli server, ko'p ilovalarni qo'llab-quvvatlash.

### v0.1.0–v0.1.1 — Initial Implementation (2026-04-21)

Loyiha yaratildi. Emulyatorlarda ekranni ko'zgulash.

---

## Fix Tracker

Unumdorlik, barqarorlik va oqim uzluksizligini qamrab olgan keng qamrovli ko'rib chiqish 2026-04-23 bajarildi.

### Phase 1 — Critical (fix before next release)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| C1 | Stability/Perf | `writeAll()` to'la jo'natish buferida cheksiz aylanadi — taym-aut yo'q, `outputLock` ni ushlaydi, barcha jo'natuvchilarni bloklaydi. Tizim qotib qolish xavfi | **v0.7.1** |
| C2 | Flow | VD serveri USB uzilganda o'ladi (`shellNoWait` jarayonni ADB oqimiga bog'laydi). To'liq qayta ulanish 5-15s | REVERTED — `setsid`/`nohup` localhost aloqasini buzdi. `shellNoWait`+`exec` ishlatiladi (v0.6.2 yondashuvi). Qayta ulanganda qayta ulanadi. |
| C3 | Flow | Avto-yangilashda sikl uzilishi yo'q — agar `pm install` jimgina muvaffaqiyatsiz bo'lsa, cheksiz qayta ishga tushirish sikli | **v0.7.1** |
| C4 | Flow | Avtomobil WiFi tregi bir marta ishlaydi va taslim bo'ladi — USB ulangandan keyin hotspot yoqilishi → abadiy qotib qoladi | **v0.7.3** |
| C5 | Stability | WakeLock taym-autsiz olingan — `onDestroy()` siz xizmat o'ldirilsa batareya zaryadi oqadi | **v0.7.1** |
| C6 | Stability | `VirtualDisplayClient.touch()` bloklanmaydigan yozish sikli + `channel` maydoni volatile emas — ma'lumotlar poygasi | **v0.7.1** |

### Phase 2 — High (latency & stability)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| H1 | Perf | NIO `delay(1)` so'rovi har bir o'qishga 1-4ms kechikish + bo'sh paytda 1000 uyg'onish/sek qo'shadi. Selector yoki `runInterruptible` ishlatish | **v0.7.2** |
| H2 | Perf | Bir kadr yozishga ikkita tizim chaqiruvi (6-bayt sarlavha + yuklama). `GatheringByteChannel.write(ByteBuffer[])` ishlatish | **v0.8.0** |
| H3 | Perf | Video releyida har bir kadrga `ByteArray.copyOf()` (~10-100KB 30 ajratish/sek). offset+length berish | **v0.8.0** |
| H4 | Perf | `synchronized(outputLock)` video+teginish+heartbeat ni ketma-ketlashtiradi. Kalit kadr yozuvi teginishni ~200ms bloklaydi | **v0.7.4** |
| H5 | Stability | `Connection.connect()` bekor qilishda SocketChannel oqib ketadi — try/finally yo'q | **v0.7.1** |
| H6 | Stability | `disconnectListener` CAS ichida sinxron chaqiriladi — potensial deadlock | **v0.7.1** |
| H7 | Stability | Avtomobil holat bayroqlari (`wifiReady`, `usbReady`) volatile emas — CONNECTING da qotib qolishi mumkin | **v0.7.2** |
| H8 | Stability | `VideoDecoder.stop()` `codec.stop()` oldidan uzatish oqimini kutmaydi — native crash xavfi | **v0.7.2** |
| H9 | Flow | Telefon tarmoq qayta chaqiruvi CONNECTED/STREAMING ni e'tiborsiz qoldiradi — hotspot o'tishi 10s qotgan kadr keltirib chiqaradi | **v0.7.3** |
| H10 | Flow | Handshake + auto-update + VD deploy barchasi poygalashadi — deployAssets tugallanmagan bo'lishi mumkin, bir vaqtda ADB operatsiyalari | **v0.7.4** |
| H11 | Flow | 5-12s VD server ishga tushirish paytida foydalanuvchiga qayta aloqa yo'q — avtomobil statik spinner ko'rsatadi | **v0.7.4** |
| H12 | Flow | Avtomobil ekranida ko'rsatmasiz telefonda birinchi USB ADB auth dialogi — 30s taym-aut | **v0.7.4** |

### Phase 3 — Medium (noticeable issues)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| M1 | Perf | VD serveri localhost da har bir kadrdan keyin tozalaydi — keraksiz tizim chaqiruvi | **v0.8.0** |
| M2 | Perf | `checkStackEmpty()` buyruq o'qish vositasini 500ms bloklaydi — Back dan keyin teginish uzilishi | **v0.8.1** |
| M3 | Perf | Multi-touch MOVE uchun N ta alohida kadr yuboradi — barcha ko'rsatkichlarni paketlash kerak | **v0.8.3** |
| M4 | Perf | MotionEvent PointerProperties/Coords har bir inyeksiyaga ajratiladi — pul ishlatish | **v0.8.1** |
| M5 | Perf | Dekoder kadr navbati 6 chuqurlikda (200ms) — past kechikish uchun 2-3 gacha kamaytirish | **v0.8.1** |
| M6 | Perf | `execFast("cmd display power-off 0")` teginish oqimida — taymerga ko'chirish | **v0.8.1** |
| M7 | Stability | VD serverida qo'sh `cleanup()` — handleClient finally + run ikkalasi ham chaqiradi | **v0.7.2** |
| M8 | Stability | `cleanupSession()` `_serviceState` ni qayta o'rnatmaydi — kechikish paytida eskirgan UI | **v0.7.2** |
| M9 | Stability | Companion dagi statik MutableStateFlow xizmat qayta ishga tushishidan omon qoladi — eskirgan activeConnection | **v0.7.2** |
| M10 | Flow | Foydalanuvchi uzishi (eject) saqlanmaydi — jarayon o'ldirilgandan keyin avtomobil qayta ulanadi | **v0.8.3** |
| M11 | Flow | Avtomobil mDNS + gateway IP zondi bir martalik — davriy qaytarish kerak | **v0.7.3** |
| M12 | Flow | checkStackEmpty da `dumpsys activity` tahlili Android versiyalarida mo'rt | **v0.8.2** |

### Phase 4 — Low (polish)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| L1 | Perf | `TouchEvent.encode()` har bir hodisaga 25-bayt ByteArray ajratadi — asinxron yozish navbati bilan pul ishlatib bo'lmaydi | WONTFIX |
| L2 | Perf | VD serveri localhost soketida jo'natish/qabul qilish buferi hajmi sozlanmagan | **v0.8.2** |
| L3 | Perf | Video dekoderi belgilangan 33,333us vaqt tamg'asini ishlatadi — devor soatini ishlatish kerak | **v0.8.2** |
| L4 | Stability | NioReader to'g'ridan-to'g'ri ByteBuffer deterministik tarzda bo'shatilmaydi | **v0.8.3** |
| L5 | Stability | `UsbAdbConnection.readFile()` to'liq o'qishni kafolatlamaydi | **v0.8.2** |
| L6 | Stability | SurfaceScaler HandlerThread hech qachon yakunlanmaydi | **v0.8.2** |
| L7 | Flow | Ilova ikonkalari 48x48px — avtomobil displeylarida xira, 96-128px kerak | **v0.8.2** |

---

## Ma'lum Muammolar

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
