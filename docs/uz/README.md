# DiLink-Auto

*Bu oʻzbek tilidagi tarjima. Ingliz tilidagi asl nusxa: [README.md](../../README.md).*

Telefoningizdagi ilovalarni avtomobilning oʻrnatilgan ekraniga toʻliq sensorli boshqaruv bilan uzating. Ochiq kod, Google Services talab qilinmaydi.

Bu DiLink-Auto loyihasining **asl repozitoriysi** ([andersonlucasg3/dilink-auto-android](https://github.com/andersonlucasg3/dilink-auto-android)). 2026-yil noyabr oyida unga uzoq vaqt faoliyat yuritgan [ID-VerNe/dilink-auto-android](https://github.com/ID-VerNe/dilink-auto-android) forki 252 ta kommitni qaytarib qoʻshdi — fork upstream jim turganda loyihani hayotda saqlab turgan edi. Rivojlanish **Xitoy telefon ROMlari + BYD DiLink bosh qurilmalari**ga qaratilgan boʻlib, kuchsiz apparatda "slayd-shou"dan foydalanishga yaroqli holatga chiqishi uchun **boshlangʻich DiLink 4.0** uchun alohida sozlanadi.

**Documentation / Documentação / 文档 / Документация / Documentation / Құжаттама / Документація / Hujjatlar**

GitHub hujjatlarni sizning tilingizda avtomatik koʻrsatmaydi. Birini tanlang:

| Til | README | Sozlash | Arxitektura | Klient | Server | Protokol | Jarayon |
|----------|--------|-------|--------------|--------|--------|----------|----------|
| English | [README](../../README.md) | [Setup](../setup.md) | [Arch](../architecture.md) | [Client](../client.md) | [Server](../server.md) | [Proto](../protocol.md) | [Progress](../progress.md) |
| Português (BR) | [README](../pt-BR/README.md) | [Setup](../pt-BR/setup.md) | [Arch](../pt-BR/architecture.md) | [Client](../pt-BR/client.md) | [Server](../pt-BR/server.md) | [Proto](../pt-BR/protocol.md) | [Progress](../pt-BR/progress.md) |
| 简体中文 | [README](../zh-CN/README.md) | — | — | — | — | — | — |
| Français | [README](../fr/README.md) | — | — | — | — | — | — |
| Русский | [README](../ru/README.md) | — | — | — | — | — | — |
| Беларуская | [README](../be/README.md) | — | — | — | — | — | — |
| Қазақша | [README](../kk/README.md) | — | — | — | — | — | — |
| Українська | [README](../uk/README.md) | — | — | — | — | — | — |
| Oʻzbekcha | [README](../uz/README.md) | — | — | — | — | — | — |

Chiziqcha bu tilda hozircha faqat README borligini bildiradi; chuqur qoʻllanmalar hali faqat ingliz tilida va tarjima qilinmoqda.

Joriy versiya: **0.19.0** (`versionCode 26111724`). Yuklab olish: [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest).

Versiyalash ushbu repozitoriyning semver sxemasiga amal qiladi: `app.versionName` — `MAJOR.MINOR.PATCH` (oldindan chiqarilgan versiyalar `-dev-N` qoʻshadi), `app.versionCode` esa qoʻlda yuritiladigan monoton butun son. Teglar: `v0.19.0` (reliz) va `v0.19.1-dev-3` (oldindan chiqarilgan versiya).

U dastlab hal qilgan muammo: Xitoy ROMli telefonlar (Xiaomi HyperOS va boshqalar) Android Auto oʻrnata olmaydi, bosh qurilma esa faqat Android Auto bilan ishlaydi — ularni bogʻlaydigan hech narsa yoʻq. DiLink-Auto Google Services bilan yoki usiz har qanday Android 10+ telefonda ishlaydi.

---

## Sinovdan oʻtkazilgan apparat

**BYD Qin PLUS DM-i 2023, Champion 55KM Leading komplektatsiyasi.** Uning bosh qurilmasi — boshlangʻich DiLink 4.0: Snapdragon 439 (8x Cortex-A53, barchasi little yadrolar), Adreno 505, 4 GB RAM, 16 GB eMMC, 1280x800 ekran, faqat 2.4 GHz Wi-Fi, Android 9 / API 28, H.264 apparat dekodlash 1080p bilan cheklangan. Barcha unumdorlik sozlashlari shu apparatda bajarilgan.

---

## v0.18.0-dev dan beri nima oʻzgardi

Fork nuqtasi (`84df5cb`, upstream/main) va joriy `main` oʻrtasidagi rivojlanishning qisqa bayoni. Har bir band rejalarga emas, haqiqiy kommitlarga mos keladi; batafsil xronologiya [docs/progress.md](../progress.md) da.

### 1. VD uzatishning toʻgʻridan-toʻgʻri arxitekturasi

Telefon endi video, sensor yoki audioni oʻzi orqali uzatmaydi. VD serveri `9638` (video), `9639` (kirish) va `9640` (audio, ikki tomonlama kelishuvdan keyin ochiladi) portlarini bogʻlaydi va qabul qilgich bilan bevosita gaplashadi. Bu har bir kadr uchun 4 ta soket + 2 ta jarayon kontekst almashtirishni olib tashlab, 2 ta soket + 0 ta kontekst almashtirish qoldiradi. Telefon sof orkestratorga aylanadi: handshake va imkoniyatlarni kelishish, VD hayot sikli (`VD_PORTS_BOUND`) va bosh qurilma loglarini yoʻnaltirish. Protokolga `FrameCodec.readFrameBlocking()`, `MessageType.VD_PORTS_BOUND` va `AudioPayload` qoʻshildi, `VirtualDisplayClient` esa faqat hayot siklidan iborat qilib qisqartirildi.

### 2. Boshlangʻich DiLink 4.0 ni sozlash (asosiy ish)

Yuqoridagi apparatda:

- **Kodlash oʻlchami 1920x1080 bilan cheklandi.** Ilgari telefon IME qirqilishining oldini olish uchun VD ni jismoniy piksellargacha kattalashtirardi (1080x2340 → kodlash 2340x1658); bu 439 ning apparat dekodlash chegarasidan oshib, dasturiy dekodlashga qaytishga sabab boʻlardi va 8x A53 1080p dan yuqori H.264 ni bir xonali fps bilan bajarardi. VD oʻlchamini kodlash oʻlchamidan ajratish orqali tuzatildi — VD kattalashtirilgan holicha qoladi (IME tuzatishi saqlanadi), kodlash oʻlchami esa avtomobil xabar qilgan viewportga moslashadi va 1920x1080 bilan cheklanadi.
- **Bitrate 8 Mbps → 4 Mbps.** 2.4 GHz avtomobil muhitida barqaror oʻtkazish qobiliyati 5-15 Mbps deb oʻlchandi, shuning uchun 8 Mbps zaxira qoldirmasdi. Moslashuvchan zaxira ham kuchaytirildi: pastki chegara 1.5 Mbps, 2 s tiklanish oynasi, 0.5 Mbps qadamlar (ilgari 8 va 2 Mbps orasida tebranardi).
- **Kadr chastotasi 30 → 24 → 20.** Endi standart 20 fps (har kadrga 50 ms). #71 issue ga qarang: 2.5 soat navigatsiya telefonni qizdirib yubordi, kadr chastotasini pasaytirish esa VD kattalashtirish chegarasidan keyingi ikkinchi bosim klapani.
- **TextureView → SurfaceView.** Adreno 505 ning har kadrdagi toʻliq ekran GL kompozitsiyasini oʻtkazib yuboradi (taxminan 2-5 ms/kadr). `outputSurfaceValid` darvozasi navigatsiya davomida dekoder yashiringan yoki koʻrsatilganida yoʻq qilingan sirtga render qilishning oldini oladi.
- **Oqim ustuvorliklari.** A53 da big yadrolar yoʻq, shuning uchun rejalashtiruvchi uchun koʻrsatmalar muhim: enkoder/dekoder/soket-boʻshatish oqimlari `THREAD_PRIORITY_URGENT_DISPLAY`, `LifeWriter` esa `THREAD_PRIORITY_BACKGROUND` ishlatadi.
- **Loglash standartlari.** Release buildlarda `carLogEnabled` standart holatda oʻchirilgan (ilgari yoqilgan edi va log kadrlari 2.4 GHz kanal uchun video bilan raqobatlashardi); `ConcurrentLinkedQueue.size()` (O(n)) `AtomicInteger` ga aylandi.
- **Uzilishda AppIconCache tozalash.** Ilgari ~80-150 PNG 16 GB eMMC da abadiy toʻplanib qolardi (5-9 MB heap). Yangi `clear()` `handleDisconnect` dan ishga tushadi, `prepareAll` esa `Semaphore(4)` bilan parallel dekodlaydi va oraliq bitmap'larni qayta ishlatadi.
- **`Debug.getPss()` olib tashlandi.** Bu eskirgan binder chaqiruvi har ulanishda bajarilib, A53 da 100-500 ms sarflardi; endi u faqat crash paytida yigʻiladi.
- **`@Immutable AppTileData`.** `AppInfo` `ByteArray` saqlaydi, bu `AppTile` ni non-skippable qilib, har `appList` qayta tayinlanishida toʻliq rekompozitsiyaga majbur qilardi. `iconPng` ni istisno qiladigan oʻzgarmas wrapper setkaga rekompozitsiyani oʻtkazib yuborish imkonini beradi.

### 3. API 28 bosh qurilma bilan moslik

Haqiqiy BYD qurilmasida (`k65v1_64_bsp`, Android 9 / API 28) `MediaCodecInfo.isHardwareAccelerated()` (API 29+) `NoSuchMethodError` tashlab, jarayonni oʻldiradi. Taxminiy apparat dekoder tanlagich olib tashlandi va `createDecoderByType` tiklandi (`REGULAR_CODECS` baribir apparat dekoderlarini birinchi qoʻyadi; BYD da u `OMX.qcom.video.decoder.avc` ni tanlaydi). `app-server` va `protocol` uchun `minSdk` 24 → 26 ga oʻzgardi, NewApi lint darvozasi esa qayta yoqildi. `am display move-stack` (API 29+) endi API 26-28 uchun `SDK_INT` bilan cheklangan va shell xatosini muvaffaqiyat deb hisoblash oʻrniga log yozadi.

### 4. UI va oʻzaro aloqa

- **Navbar qayta dizayni:** Eject (uzish) / Home / Back ga qisqartirildi. Bildirishnoma tugmasi, soʻnggi ilovalar paneli, soat va tarmoq maʼlumoti toʻrtta modul boʻylab oʻlik kod bilan birga olib tashlandi (avto tomonida `NotificationScreen`, `RecentAppsState`; telefon tomonida `NotificationService`; `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` xabar turlari). **Bu kod bazasida bildirishnomalarni uzatish mavjud emas.**
- **Ilovalarni mahkamlash:** ilova ikonkasi ustida uzoq bosish Pin to Top / Unpin ni beradi, SharedPreferences da saqlanadi.
- **Ilovalar allowlist'i:** yangi `AllowlistScreen` foydalanuvchiga qaysi ilovalar avtomobilga yuborilishini tanlash imkonini beradi; tanlanmagan ilovalar `sendAppList` da filtrlanib, uzatiladigan maʼlumot hajmini va avtomobilning ikonka dekodlash ishini kamaytiradi. Keng tarqalgan navigatsiya ilovalari birinchi ishga tushirishda oldindan tanlanadi. Almashtirish `ACTION_ALLOWLIST_UPDATED` yuboradi, shunda ishlab turgan xizmat roʻyxatni qayta yuboradi va avtomobildagi setka jonli yangilanadi.
- **Portret ilovalarni qoʻllab-quvvatlash:** dinamik DPI hisoblash (`VideoConfig.calculateOptimalDpi`) portret ilovalarni (Amap, WeChat) gorizontal VD ichida yupqa tasmaga siqib qoʻyilishidan saqlaydi; sessiya tugagach IME tiklanadi (ilgari telefon linkpc IME sini saqlab qolardi); multi-touch inyeksiya indeksi tuzatildi.
- **Avto tomonidagi start DPI ni bekor qilish:** handshake `dpiOverride` maydonini oldi (0 = avtomatik, [120, 480] = foydalanuvchi qiymati), u avtomobilning `startup_dpi` sozlamasidan boshqariladi va ikkala orientatsiyada ham yuboriladi. 1280x800 da juda kichik standart DPI ni tuzatadi.
- **Rang tokenlari va tipografiya tekislanishi:** tarqoq `Color.Gray` / `0xFF888888` `onSurfaceVariant` ga koʻchirildi, takroriy xato qizillari birlashtirildi, yorliqlar 12sp → 14sp. "WiFi Direct Mode" "WiFi ADB Mode" deb qayta nomlandi; avto tomonidagi `adb tcpip` matni olib tashlandi.
- **Aylanishda qora ekran tuzatishi:** `onCarViewportChanged` holatni CONNECTING ga oʻrnatadi va aylanishda VD ni qayta joylashtiradi, biroq uzatish layout darvozasi `isConnected` dan foydalanardi (qayta joylashtirishning ~2 s davomida false), shu sabab `CarShell` `CarLaunchScreen` ga qaytardi, `TextureView` kompozitsiyadan chiqib ketardi va yangi sirt dekoderni qayta ishga tushira olmasdi. Endi darvoza `state == CONNECTING && appList.isNotEmpty()` ni ham qabul qiladi va aylanish davomida uzatish layoutini (hamda uning video kutish overlayini) saqlab turadi.

### 5. Sessiya barqarorligi

Faol sessiya hech qachon qayta ulanish urinishi bilan oʻldirilmaydi — ilgari Wi-Fi shlyuz orqali qayta urinish yoki qoʻlda ulanish ishlab turgan sessiyani oʻldirishi mumkin edi. Qayta ulanish tsikli 3 ta ketma-ket ADB xatosidan keyin toʻxtaydi. Telefon IP si oʻzgarganda TCP ADB qayta ulanadi va USB mavjud boʻlmaganda avtomatik ravishda TCP ADB ga oʻtadi.

### 6. PipelineServer barqarorlashtirish

`VirtualDisplayServer` (1287 qator, 9 oqim, 2 navbat) `PipelineServer` ga aylandi (bitta oqim, 0 navbat): kadr soati → GL render → enkoder boʻshatish → TCP yozish, tabiiy oqim nazorati bilan (tiqilib qolgan TCP yozuv keyingi `eglSwapBuffers` ni bloklaydi). Shuningdek tuzatildi: vaqtinchalik EGL kontekstda yaratilib, keyin yoʻq qilingan GL tekstura (pipeline oqimi 0-teksturani bogʻlardi); interaktiv boʻlmagan shell chiqqanda oʻldirilgan `app_process` (`shellBackground` bilan tuzatildi, u shell oqimini ulangan holda saqlaydi); va jismoniy panelni tiklash tartibi (ilgari shell allaqachon yopilgandan keyin `input keyevent 224` chaqirilardi).

### 7. Diagnostik log almashtirgichi

Settings → Debug diagnostik log almashtirgichini oldi. U oʻchirilganda diskka yozish nolga teng. Holat avtomobilga `LOG_TOGGLE` data xabari orqali uzatiladi va #72 issue dan boshlab har handshake dan keyin avtomatik sinxronlanadi, shunda avtomobil logi qayta ulanishda jimgina tiklanmasdan, telefon almashtirgichiga ergashadi. Standart: debug/pre-release buildlar uchun yoqilgan, release uchun oʻchirilgan; foydalanuvchining aniq tanlovi saqlanadi.

### 8. Soddalashtirilgan xitoycha interfeys

Klient ham, server ham `values-zh-rCN` / `values-zh` bilan keladi. Ilova matnlari ingliz, portugal (BR), fransuz, rus, belarus, qozoq, ukrain, oʻzbek va xitoy tillarida mavjud.

### 9. Kod sogʻlomligi

Bir necha davra SRP/DRY auditlari va refaktoringlari: modullar boʻylab umumiy konstantalar (`AppPrefs`, `AppTargets`, `VdDeploy`, `VdDeployArgs`, `WifiGatewayIp`, `AppIconLimits`) va ajratib olingan hamkorlar.

- **`protocol-core` / `protocol`:** `NioWriter` (taymaut bilan soket-kanal spin yozuvi), `BackoffPolicy` (parametrlangan eksponensial orqaga qaytish), `DecodeLivenessTracker`, `ConnectionFactory`, `ConnectionKeepAlive`, `UsbAdbKeyStore`, `Ipv4Validator`, `AppIconLimits`; oʻlik `UsbAdbConnection` kodi olib tashlandi.
- **`vd-server`:** `AsyncSocketSender`, `AudioEncoderSelector`, `ProcessWatchdog`, `VideoEncoderSelector`, `PortAcceptor`, `ActivityDumpParser`, `VdDisplayHealer`.
- **`app-client`:** `ConnectionService` yengillashtirildi; `ShizukuVdDeployer`, `ShizukuNav`, `ClientNetworkMonitor`, `CarAppInstallHost`, `AllowlistRepository`, `SessionState` ajratib olindi; `InputInjectionService` eskirgan deb belgilandi.
- **`app-server`:** `CarConnectionService` ~407 qatorga tushdi; holat mashinasi `CarSessionCoordinator` (9637/9638/9639/9640 koʻp kanalli holat mashinasi va qayta handshake), `CarAdbTracker` (USB/TCP ADB kuzatuvi) va `CarAppListReceiver` (ilovalar roʻyxati va ikonka xavfsizlik chegaralari) ga boʻlindi.
- **`app-desktop`:** `AppIconLimits`, `DecodeLivenessTracker`, `BackoffPolicy` dan foydalanadi va eng soʻnggi protokol primitivlari bilan moslashtirilgan.

### 10. VD oqishi tuzatishi, idempotent tozalash, qora ekrandan oʻz-oʻzini tiklash

**Asosiy sabab:** har qayta ulanish bitta VirtualDisplay oqishiga olib kelardi. Eski kod `pkill -9` ishlatardi, u JVM shutdown hook ni chetlab oʻtadi, shuning uchun `PipelineServer.cleanup()` hech qachon ishlamasdi — VD boʻshatilmasdi, jismoniy panel oʻchirilgan holicha qolardi va `screen_off_timeout` sozlamasi yoʻqolardi (snapshot kodning oʻzi yozgan `2147483647` sentinelini qamrab olgan edi). Bir necha qayta ulanishdan keyin avtomobil ekrani qora boʻlib qolardi.

**Tuzatish (12 fayl):** toʻxtatish soʻrovi sentinel'i (`/sdcard/DiLinkAuto/stop-request`), uni dvigatel watchdog'i isteʼmol qiladi, shunda u normal yoʻl bilan chiqib, toʻliq `cleanup()` ni bajaradi (ART oddiy SIGTERM da shutdown hook larini ishga tushirmaydi); `pgrep -f` ga asoslangan tekshiruv (toybox `pkill -0` ni rad etadi); pid ni `pkill -f` oʻrniga `pgrep -f` bilan topadigan `kill -9` zaxirasi (oxirgisi wrapper shellni ham oʻldirib, ADB natijasini oʻqib boʻlmaydigan qilib qoʻyadi); `AtomicBoolean` `cleanupGuard`, shunda 7 ta chaqiruv joyi `cleanupSession()` ni qayta-qayta ishga tushirmaydi (ilgari bitta uzilish 9 ta "Force-waking physical display" chaqiruvini keltirib chiqarardi); ikki tomonda `waitForVdServerExit()`, shunda ikki dvigatel bir VD va portlar uchun kurashmaydi; `PhoneDisplayRestorer` `NonCancellable` bilan oʻz jarayon umri doirasida; qayta tartiblangan `cleanup()`; `DisplayPowerController.restoreSetting()` qiymat validatsiyasi; va `protocol-core` ga koʻchirilgan `BlackScreenDetector` (`BLACK_SCREEN_SUSTAIN_MS = 3000`), shunda avto va desktop uni birgalikda ishlatadi, `rehandshakeForBlackScreen()` esa sessiya boshiga koʻpi bilan bitta urinish bilan qulflangan.

Regressiya avtomatlashtirishi `scripts/` da: `verify-blackscreen-fix.sh`, `e2e-reconnect-cleanup.sh`, `verify-reconnect-cleanup.sh` (haqiqiy desktop UI orqali boshqariladigan 32 tekshiruv; oxirgi toʻliq yugurish 32/32, 2m48s).

### 11. Windows qabul qilgich `:app-desktop`

Bosh qurilmadan tashqari, ayni protokol **Windows dasturini** qabul qilgich sifatida qoʻllab-quvvatlaydi: telefon ekrani PC oynasiga aks etadi, sichqoncha esa sensorga aylanadi. Modul `app-desktop/` da joylashgan (Kotlin/JVM + Compose Desktop 1.6.2, JDK 17) va `protocol-core` ni qayta ishlatadi — telefon tomonida hech qanday oʻzgarish yoʻq.

- **Transport:** telefonga toʻgʻridan-toʻgʻri uchta TCP ulanish — 9637 (boshqaruv + maʼlumot), 9638 (video), 9639 (kirish) — avtomobildagi kabi, vositachisiz.
- **Joylashtirish taqsimoti:** handshake javobidagi `connectionMethod` VD serverni kim joylashtirishini hal qiladi. Shizuku bilan buni telefon bajaradi; aks holda desktop lokal `adb.exe` bilan joylashtiradi (`connect` → eski dvigatelni oʻldirish **va uning haqiqatan chiqishini kutish** → `exec app_process`), bu `config.json` da `"dev_mode": true` ni talab qiladi.
- **Dekodlash:** JavaCV/FFmpeg dasturiy dekodlashi va D3D11VA apparat dekodlashi, apparat ishga tushishda ishlamasa yoki nol kadr bersa avtomatik zaxira bilan.
- **UI:** uchta navigatsiya sahifasi — Mirror / Apps (launcher) / Display (DPI bekor qilish, apparat dekodlash almashtirgichi, "Apply and reconnect", uygʻoq turish, telefonning jismoniy ekrani).
- **Oʻz-oʻzini tiklash:** 10 s barqaror qora ekran avtomatik qayta ulanadi, jarayon boshiga koʻpi bilan ikki marta.
- **Konfiguratsiya va loglar:** `%APPDATA%\DiLinkAuto` (`config.json` / `desktop.log`), `DILINK_DESKTOP_HOME` orqali oʻzgartirish mumkin; `DILINK_ADB` adb yoʻlini belgilaydi.

```bash
# Oʻz-oʻzini toʻldiruvchi app-image (ichiga JRE olingan): build/compose/binaries/main/app/DiLinkAuto nusxasi
./gradlew :app-desktop:createDistributable

# Headless probe: faqat handshake/VD/fps ni tekshiradi; Shizuku boʻlmasa ADB joylashtirish uchun DILINK_DEV_MODE=1 qoʻying
app-desktop --probe
```

> Holat: Shizuku yoʻli avtomobil tomoniga mos keladi; **ADB orqali joylashtirish yoʻli hali haqiqiy qurilmada tekshirilmagan**. Modul reliz assetlariga kirmaydi (uning JavaCV/FFmpeg bogʻliqligi GPL, loyiha esa MIT).

### 12. Unit testlar va xavfsizlikni kuchaytirish

**Test qamrovi:** ikkita modulda umuman test manba toʻplami yoʻq edi (`vd-server` da `src/test` yoʻq edi, `protocol` niki esa boʻsh edi). `8157c86` dan boshlab barcha 6 modulda JUnit4 unit testlari qoʻshildi va ilgari test qilinmagan eng xavfli mantiqni qamrab oldi (protokol framing, ADB kripto primitivlari, VD joylashtirish argumentlari, moslashuvchan bitrate, shell quote darvozasi, versiya darvozalari, oʻrnatish holatini tasniflash, crash hisoboti formati). Xulq-atvorni saqlovchi "testability seams" ning navbatdagi davrasi yana 44 ta holat qoʻshdi. Bugungi kunda repozitoriyda 6 modul boʻylab 1,200+ `@Test` holati bor (JUnit4 + kotlinx-coroutines-test, Robolectric/Mockito/MockK yoʻq; Android modullar `isReturnDefaultValues=true` va qoʻlda yozilgan fake'lardan foydalanadi). Inventar, qulflangan modullararo invariantlar va ochiq masalalar [docs/IMPLEMENTATION_REPORT_TESTING.md](../IMPLEMENTATION_REPORT_TESTING.md) da.

**Peer himoyasini kuchaytirish (`a7d2b72`):** ilgari har bir soket peer ishonchli deb qabul qilinardi — 6 baytli buzuq kadr telefon jarayonini yoki shell-UID VD serverni oʻldirishi mumkin edi. Endi barcha besh dekoder xom `getX()` dan oldin `require()` tekshiruvini bajaradi; oʻquvchi korutinalar `Throwable` ni tutib, sababini logga yozib, oʻlish oʻrniga uziladi; `MAX_PAYLOAD_SIZE` 128 MB dan 16 MB ga tushdi, `NioReader` buferi esa kadr chegaralarida qaytarib olinadigan qattiq chegaraga ega.

**VD kanali avtorizatsiyasi (`8601fb1`):** 9638/9639 portlari ilgari oʻsha Wi-Fi dagi har qanday hostni qabul qilardi va har kimga sensorlar inyeksiya qilish hamda shell sifatida `am start` / `pm uninstall` ishga tushirish imkonini berardi. Endi ular faqat hozir xizmat koʻrsatilayotgan qabul qilgichdan ulanishlarni qabul qiladi; rad etilgan peer'lar yopiladi va server kutishda davom etadi.

**Shell quote (`5eae3e7`):** `VdDeploy` buyruq satrlari `shellQuote` dan oʻtadi, shuning uchun peer boshqaradigan satrlar hech qachon `sh` ga yalangʻoch yetib bormaydi.

**Desktop (`1f6836a`):** handshake javobidagi `adbPort` / `jarPath` ishlatishdan oldin tekshiriladi (ilgari ular toʻgʻridan-toʻgʻri joylashtirishga uzatilardi, yaʼni har qanday peer'dan ixtiyoriy kod bajarilishi); adb bola jarayonlari endi oqib ketmaydi.

**Avto sessiyasi (`bc2634c` / `35d7821`):** sessiyalar generatsiya latch'idan foydalanadi, shunda `cleanupGuard` tarmoq oʻzgarishi yoʻli bilan erta isteʼmol qilinmaydi; qora ekrandan tiklash endi dekoderda `stop()` chaqirmaydi (hech kim uni qayta ishga tushirmasdi, shuning uchun tiklash yoʻli doimiy qora ekranni kafolatlar edi).

**Reliz taʼminot zanjiri:** reliz keystore'i ilgari kommit qilingan edi; endi unga faqat havola qilinadi, imzolash sirlari CI da (git tarixini hali tozalash kerak). CI endi PR kodini self-hosted runnerlarda ishga tushirmaydi (`9517205`) va imzolanmagan / haddan tashqari kichraytirilgan APK hamda yetim artefaktlar ishlab chiqarmaydi (`efde81f`). Barcha workflowlar GitHub-da joylashgan `ubuntu-latest` da ishlaydi.

---

## Joriy funksiyalar holati

**Ishlaydi:**

- H.264 video aks ettirish, standart 20 fps, 4 Mbps CBR, Main profili (boshlangʻich DiLink 4.0 uchun sozlangan; yuqori darajali qurilmalarda ham ishlaydi)
- Audio aks ettirish: AAC-LC 48 kHz stereo, 128 kbps, alohida TCP ulanishi (9640) orqali, 150 ms jitter bufer, 46.875 fps da doimiy sukunat toʻldirish, ikki tomonlama `FEATURE_AUDIO` imkoniyat kelishuvi, klientda saqlanadigan audio almashtirgichi
- Toʻliq sensorli kiritish (multi-touch, pinch-to-zoom)
- Ilova launcher: qidiruv, alifbo tartibi, 64dp ikonkalar, uzoq bosish menyusi (oʻchirish, ilova haqida, mahkamlash)
- Ilovalar allowlist'i: avtomobilga faqat tanlangan ilovalar yuboriladi
- Avto tomonidagi start DPI ni bekor qilish (1280x800 da kichik standart DPI ni tuzatadi)
- Orientatsiyaga ergashish: avtomobil aylanganda VD qayta quriladi, portret ilovalar endi tasmaga siqilmaydi
- Shizuku qoʻllab-quvvatlashi: VD serverni USB ADB siz joylashtirish va avtomobil ilovasini jim `pm install` bilan oʻz-oʻzini yangilash
- Avtomobil ilovasining oʻz-oʻzini yangilashi: telefon `versionCode` ni solishtiradi va avtomobil ilovasini Wi-Fi ADB orqali yangilaydi
- Uzatish davomida telefon ekrani oʻchirilgan (batareya tejaladi); uzilishda jismoniy panel tiklanadi
- Ruxsatlar boʻyicha bosqichma-bosqich onboarding
- Soddalashtirilgan xitoycha interfeys va yana 8 ta interfeys tili
- Toʻgʻridan-toʻgʻri VD arxitekturasi: telefonda vositachi yoʻq; video, sensor va audio toʻgʻridan-toʻgʻri oʻtadi
- VD oqishi tuzatishi: sentinelga asoslangan muloyim toʻxtatish (toʻliq `cleanup()`, `kill -9` faqat taymaut zaxirasi sifatida), idempotent cleanup guard, VD chiqishini kutish
- Qora ekrandan oʻz-oʻzini tiklash: avtomobil 3 s barqaror qora ekrandan keyin VD ni qayta quradi (qayta handshake); Windows qabul qilgich ayni detektordan 10 s oyna bilan foydalanadi
- Windows qabul qilgich (`app-desktop`): aks ettirish oynasi, sichqoncha sensori, ilova launcher, DPI/apparat dekodlash paneli, adb joylashtirish, `jpackage` app-image

**Olib tashlangan (birlashtirishdan oldingi upstreamga nisbatan):**

- Bildirishnomalarni uzatish (telefon bildirishnomalari → avtomobil ekrani): navbar bildirishnoma tugmasi, bildirishnomalar roʻyxati va `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` protokol xabarlari yoʻq
- Soʻnggi ilovalar paneli, soat, tarmoq maʼlumoti

**Amalga oshirilmagan:**

- Media boshqaruvi
- Navigatsiya vidjetlari (audio aks ettirish toʻliq)

**Maʼlum cheklovlar:**

- USB ADB ishlashi uchun Samsung Auto Blocker oʻchirilgan boʻlishi kerak (Settings → Security → Auto Blocker → off).
- Samsung batareya boshqaruvi aniq istisno talab qiladi.
- Samsung Knox virtual displeyga birinchi murojaatda xavfsizlik soʻrovini koʻrsatishi mumkin.
- Telefon hotspoti qoʻlda yoqilishi kerak (Android 16 cheklovi).
- Dekoder qayta ishga tushish poygalari sababli vaqti-vaqti bilan vizual glitch'lar boʻladi; keyingi keyframe (~1 s) tiklaydi.
- Uzatish kechikishi yuklama ostida taxminan 100-200 ms.
- Baʼzi ilovalar ekranni toʻldirmaydi (letterbox / faqat portret). DiLink-Auto gorizontal virtual displeyni aks ettiradi, shuning uchun gorizontal rejimni qoʻllab-quvvatlamaydigan ilovalar qora chiziqlar koʻrsatadi — buni ilovaning oʻzi hal qiladi.
- API 26-28 qurilmalarda, agar `DisplayControl` refleksiyasi muvaffaqiyatsiz boʻlsa, `cmd display power-reset` / `power-off` shell zaxirasi ham API 29+ boʻladi, shuning uchun jismoniy panel qaytmasligi mumkin (u jimgina muvaffaqiyatsizlik oʻrniga log yozadi).
- Oʻrnatish jarayoni ingliz boʻlmagan interfeys tillarida koʻrsatilmaydi: `InstallStatus.parse` lokalizatsiya qilingan holat satrlarini ingliz boʻlaklari boʻyicha tasniflaydi, shuning uchun xitoycha (va boshqalar) `IDLE` ga tushadi. Testlar bilan qulflangan, tuzatilmagan ([C-6](../IMPLEMENTATION_REPORT_TESTING.md)).
- Avtomobil ilovasini birinchi oʻrnatishni ekstremal versiya raqami bilan oʻtkazib yuborish mumkin edi: avtomobil ilovasi yoʻq boʻlsa `readInstalledVersion` `"0"` qaytaradi, shuning uchun ichki `versionName` `0` / `0.0` / `0.0.0` boʻlsa "yangi" deb solishtiriladi. Joriy `versionName` — `0.19.0`, shuning uchun bu holat yuzaga kelmaydi ([C-5](../IMPLEMENTATION_REPORT_TESTING.md)).

---

## Bu qanday ishlaydi

1. **Hotspotni yoqing** — telefonning Wi-Fi hotspot ini yoqing; avtomobil unga ulanadi.
2. **Ulang** — telefonni avtomobilning USB portiga ulang (yoki ayni Wi-Fi tarmogʻiga qoʻshiling).
3. **Avto-oʻrnatish** — telefon avtomobil ilovasini Wi-Fi ADB orqali oʻrnatadi (faqat birinchi marta, bitta bosish).
4. **Avto-ulanish** — toʻrtta Wi-Fi TCP oqimi: boshqaruv (9637), video (9638), sensor (9639), audio (9640, kelishuvdan keyin ochiladi).
5. **Ilovalaringizdan foydalaning** — avtomobil launcheridan istalgan ilovani ishga tushiring; u telefonda ishlaydi, avtomobil ekranida koʻrinadi va sensorlarni qabul qiladi.

Telefon ilovalarni virtual displeyda ishga tushiradi va ularni H.264 ga kodlab, avtomobilga uzatadi. Avtomobil ekranidan kelgan sensor hodisalari telefonga haqiqiy sensor hodisalari sifatida qayta inyeksiya qilinadi. Telefonning jismoniy ekrani batareyani tejash uchun oʻchirilgan holicha qoladi va mustaqil ravishda ishlatilishi mumkin.

Arxitektura, ulanish oqimi va dizayn qarorlari: [docs/architecture.md](../architecture.md).

---

## Talablar

**Telefon:**

- Android 10+ (`minSdk = 29`)
- USB debugging yoqilgan (Developer options)
- All Files Access ruxsati (birinchi ishga tushirishda yoʻnaltiriladi)

**Avtomobil:**

- BYD DiLink 3.0+, **BYD Qin PLUS DM-i 2023 Champion 55KM Leading** da tekshirilgan (boshlangʻich DiLink 4.0, Snapdragon 439 / Android 9 / 1280x800)
- Android 8.0+ (`minSdk = 26`)
- Bitta boʻsh USB-A port (yoki ayni Wi-Fi tarmogʻi)

**Telefon hotspoti yoqilgan boʻlishi kerak** — avtomobil unga qoʻshiladi. Juftlash kodi yoʻq, Google hisobi yoʻq.

**Internet ulanishi talab qilinmaydi.** DiLink-Auto telefon hotspoti orqali lokal uzatadi; telefon va avtomobil bevosita gaplashadi. Internet faqat telefoningizda ishlayotgan ilovalarga kerak.

---

## Oʻrnatish

APK ni [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest) dan yuklab oling yoki oʻzingiz quring:

1. **Qurish:** `./gradlew :app-client:assembleDebug`
2. **Telefonga oʻrnatish:** `app-client/build/outputs/apk/debug/app-client-debug.apk`
3. **USB debugging ni yoqing** (Settings → Developer options)
4. **DiLink-Auto ni oching** va All Files Access onboardingini bajaring
5. **Hotspotni yoqing va avtomobilga ulang** — avtomobil ilovasi birinchi ishga tushirishda Wi-Fi ADB orqali avtomatik oʻrnatiladi

Avtomobil APK si va VD server JAR i ikkalasi ham telefon APK si ichiga joylangan, shuning uchun avtomobilga alohida hech narsa oʻrnatish shart emas va avtomobil ilovasini oʻrnatish internet talab qilmaydi.

> **Imzo yangilanishi haqida eslatma:** debug bilan imzolangan builddan rasmiy imzolangan buildga oʻtish sertifikatni oʻzgartiradi — **avval eski paketni oʻchirib tashlang**, aks holda oʻrnatish `INSTALL_FAILED_UPDATE_INCOMPATIBLE` bilan muvaffaqiyatsiz boʻladi. Toʻliq reliz va imzolash qoidalari: [docs/release.md](../release.md).

---

## Loyiha tuzilishi

Telefon APK si (`app-client`) avtomobil APK sini (`app-server`) va VD server JAR ini (`vd-server`) ichiga oladi. Qabul qilgich almashtiriladigan: ayni protokol avtomobilda (`app-server`) yoki Windows da (`app-desktop`) ishlaydi.

```
DiLink-Auto/
├── protocol-core/  Sof JVM umumiy kutubxonasi (kadrlar, xabarlar, konstantalar, VD joylashtirish ketma-ketligi; Android bogʻliqligisiz)
├── protocol/       Platformaga moslash (ADB, Android hook'lar, USB/TCP ulanishlar)
├── app-client/     Telefon APK si — orkestratsiya, VD joylashtirish, avtomobilni oʻz-oʻzini yangilash, ilova allowlist
├── app-server/     Avtomobil APK si — UI, ulanish holat mashinasi, video dekoder
├── app-desktop/    Windows qabul qilgich — aks ettirish oynasi, FFmpeg dekodlash, adb joylashtirish, jpackage
├── vd-server/      VirtualDisplay serveri (JAR ga kompilyatsiya qilinadi, telefon tomonidan joylashtiriladi)
├── scripts/        Avtomatlashtirish (log tekshirish, uchdan-uchga qayta ulanish/tozalash)
├── docs/           Hujjatlar (koʻp tilli kirish nuqtasi)
└── gradle/         Build tizimi
```

| Modul | Vazifa | minSdk |
|--------|------|--------|
| `protocol-core` | Sof JVM umumiy protokol kutubxonasi (Connection/NioWriter/NioReader/FrameCodec/Messages/AudioPayload/VdDeploy/BackoffPolicy/DecodeLivenessTracker/BlackScreenDetector) | — |
| `protocol` | Umumiy protokol kutubxonasi (UsbAdbConnection, AdbProtocol, VideoConfig, ConnectionFactory, ConnectionKeepAlive, UsbAdbKeyStore) | 26 |
| `app-client` | Telefon ilovasi (ConnectionService, ShizukuVdDeployer, ClientNetworkMonitor, CarAppInstallHost, AllowlistRepository, FileLog) | 29 |
| `app-server` | Avtomobil ilovasi (CarSessionCoordinator, CarAdbTracker, CarAppListReceiver, AudioPlayer, VideoDecoder, CarShell, HomeScreen, PersistentNavBar) | 26 |
| `app-desktop` | Windows qabul qilgich (DesktopApp, DesktopConnectionService, VideoDecodePipeline, SwingVideoView) | — |
| `vd-server` | Shell-UID jarayoni (PipelineServer, AudioPipeline, AudioEncoderSelector, PortAcceptor, TouchInjector, GlPipeline, DisplayPowerController) | — |

---

## Yigʻish

```bash
# Telefon APK sini qurish (buildVdServer + embedServerApk ni ishga tushiradi, vd-server.jar va app-server.apk ni klient assetsiga joylaydi)
./gradlew :app-client:assembleDebug

# APK joylashuvi:
# app-client/build/outputs/apk/debug/app-client-debug.apk  (telefon, avtomobil APK sini ichiga oladi)

# Unit testlar (6 modul, JUnit4 + kotlinx-coroutines-test, Robolectric/Mockito/MockK yoʻq)
./gradlew :protocol-core:test :protocol:test :vd-server:test :app-desktop:test
./gradlew :app-client:testDebugUnitTest :app-server:testDebugUnitTest
```

JDK 17 va Android SDK (compileSdk 34) talab qilinadi. Release buildlar `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_PASSWORD` talab qiladi (`RELEASE_KEYSTORE_FILE` / `RELEASE_KEY_ALIAS` ixtiyoriy); majburiy oʻzgaruvchi yoʻq boʻlsa, konfiguratsiya bosqichida xato beriladi — rasmiy paket ustiga oʻrnatilmaydigan imzolanmagan APK jimgina yaratilmaydi. Keystore'ning oʻzi hech qachon kommit qilinmaydi.

Windows da ekvivalent buyruqlar `.\gradlew.bat` dan foydalanadi.

---

## Hujjatlar

Toʻliq hujjatlar (sozlash, arxitektura, protokol, klient, server, jarayon) [docs/README.md](../README.md) dan boshlanadi. Jarayon normalari [AGENTS.md](../../AGENTS.md), [docs/ISSUE_GUIDE.md](../ISSUE_GUIDE.md), [docs/PR_GUIDE.md](../PR_GUIDE.md) va [docs/release.md](../release.md) da.

---

## Hissa qoʻshish

Pull request'lar qabul qilinadi. Rivojlanish `main` va `develop` da olib boriladi (git-flow: feature'lar `develop` dan shoxlanadi, relizlar `main` ga birlashtiriladi va qayta birlashtiriladi). PR ochishdan oldin `./gradlew :app-client:assembleDebug` va yuqoridagi unit test buyruqlari oʻtishiga ishonch hosil qiling.

---

## Litsenziya

MIT, [LICENSE](../../LICENSE) ga qarang. Dastlab [andersonlucasg3](https://github.com/andersonlucasg3) tomonidan yaratilgan; asl muallifga va loyihani forkda hayotda saqlagan barchaga rahmat.
