# DiLink-Auto

Telefoningizdagi ilovalarni avtomobilingizning o'rnatilgan ekranida ishlating. Ochiq manbali, Google xizmatlari talab qilinmaydi.

**Android 10+ bo'lgan har qanday telefon** va **BYD DiLink 3.0+** axborot-ko'ngilochar tizimlari uchun Android Auto'ga ochiq manbali muqobil. Dastlab Xiaomi HyperOS / Xitoy ROM uzilishi sababli yaratilgan, ammo universal ishlaydi.

[![Sponsor](https://img.shields.io/badge/Sponsor-%E2%9D%A4-pink?logo=github)](https://github.com/sponsors/andersonlucasg3)
[![Pix](https://img.shields.io/badge/Pix-Brazil-00C2A0)](https://nubank.com.br/cobrar/5gf35/69ed4939-b2c0-4071-b75d-3b430ab70a5d)

## Documentation / Documentação / Документация / Documentatio / Hujjatlar

GitHub foydalanuvchi tilidagi hujjatlarni **avtomatik ravishda** ko'rsatmaydi. Quyidagi tilingizni tanlang:

| Language | README | Setup | Architecture | Client | Server | Protocol | Progress |
|----------|--------|-------|-------------|--------|--------|----------|----------|
| English | [README](./README.md) | [Setup](./setup.md) | [Arch](./architecture.md) | [Client](./client.md) | [Server](./server.md) | [Proto](./protocol.md) | [Progress](./progress.md) |
| Português (BR) | [README](./pt-BR/README.md) | [Setup](./pt-BR/setup.md) | [Arch](./pt-BR/architecture.md) | [Client](./pt-BR/client.md) | [Server](./pt-BR/server.md) | [Proto](./pt-BR/protocol.md) | [Progress](./pt-BR/progress.md) |
| Français | [README](./fr/README.md) | [Setup](./fr/setup.md) | [Arch](./fr/architecture.md) | [Client](./fr/client.md) | [Server](./fr/server.md) | [Proto](./fr/protocol.md) | [Progress](./fr/progress.md) |
| Русский | [README](./ru/README.md) | [Setup](./ru/setup.md) | [Arch](./ru/architecture.md) | [Client](./ru/client.md) | [Server](./ru/server.md) | [Proto](./ru/protocol.md) | [Progress](./ru/progress.md) |
| Беларуская | [README](./be/README.md) | [Setup](./be/setup.md) | [Arch](./be/architecture.md) | [Client](./be/client.md) | [Server](./be/server.md) | [Proto](./be/protocol.md) | [Progress](./be/progress.md) |
| Қазақша | [README](./kk/README.md) | [Setup](./kk/setup.md) | [Arch](./kk/architecture.md) | [Client](./kk/client.md) | [Server](./kk/server.md) | [Proto](./kk/protocol.md) | [Progress](./kk/progress.md) |
| Українська | [README](./uk/README.md) | [Setup](./uk/setup.md) | [Arch](./uk/architecture.md) | [Client](./uk/client.md) | [Server](./uk/server.md) | [Proto](./uk/protocol.md) | [Progress](./uk/progress.md) |
| Oʻzbekcha | [README](./uz/README.md) | [Setup](./uz/setup.md) | [Arch](./uz/architecture.md) | [Client](./uz/client.md) | [Server](./uz/server.md) | [Proto](./uz/protocol.md) | [Progress](./uz/progress.md) |

## Nima Qiladi

DiLink-Auto telefoningizdagi ilovalarni to'liq sensorli o'zaro ta'sir bilan avtomobilingiz displeyiga uzatadi. Navigatsiya, musiqa, xabarlar — telefondagi har qanday ilovani — to'g'ridan-to'g'ri avtomobil ekranidan ishga tushiring. Bildirishnomalar avtomobilning navigatsiya panelida progress ko'rsatkichlari bilan paydo bo'ladi. H.264 video 60 kadr/s gacha, 8 Mbit/s CBR, batareyani tejash uchun telefon ekrani o'chirilgan.

**Asl motivatsiya:** telefoningiz Android Auto'ni ishga tushira olmaganda (Xitoy ROM, Google Play Services yo'q), lekin avtomobilingiz faqat Android Auto'ni qo'llab-quvvatlaganda (CarWith, CarPlay yoki Carlife yo'q) bo'shliqni bartaraf etish. Ammo DiLink-Auto har qanday Android telefon bilan ishlaydi — Google xizmatlari bor yoki yo'q.

| Qurilma | Muammo |
|--------|-------|
| Xiaomi 17 Pro Max (HyperOS 3, Xitoy ROM) | Android Auto yo'q — Google Play Services yo'q |
| BYD Destroyer 05 / King (Braziliya bozori) | Bosh qurilmada faqat Android Auto |
| Android 10+ har qanday telefon | ROM yoki Play Services'dan qat'i nazar ishlaydi |

## Talablar

**Telefon:**
- Android 10+ har qanday telefon
- USB disk raskadrovka yoqilgan (Dasturchi parametrlari)
- Barcha fayllarga kirish ruxsati (birinchi ishga tushirishda so'raladi)

**Avtomobil:**
- BYD DiLink 3.0 yoki yangiroq
- Bitta bo'sh USB-A port

**Telefon hotspoti yoqilgan bo'lishi kerak** — avtomobil telefoningizning WiFi hotspotiga ulanadi. Juftlashtirish kodlari kerak emas, Google hisobi kerak emas.

**Internetga ulanish talab qilinmaydi.** DiLink-Auto hamma narsani telefoningizning WiFi kirish nuqtasi orqali mahalliy ravishda uzatadi — avtomobil va telefon bevosita muloqot qiladi. Internetga ulanish faqat telefoningizda ishlaydigan ilovalar uchun kerak (masalan, navigatsiya, musiqa), DiLink-Auto uchun emas.

## Qanday Ishlaydi

1. **Hotspotni yoqing** — Telefoningizning WiFi hotspotini yoqing. Avtomobil unga ulanadi.
2. **Ulang** — Telefonni avtomobilning USB portiga ulang
3. **Avto-o'rnatish** — Telefon WiFi ADB orqali avtomobil ilovasini o'rnatadi (faqat birinchi marta, bir marta bosish)
4. **Avto-ulanish** — 3 ta ajratilgan WiFi TCP oqimi: video (port 9638), sensorli kiritish (port 9639) va boshqaruv (port 9637)
5. **Ilovalaringizni ishlating** — Avtomobilning ishga tushirish ekranidan har qanday ilovani ishga tushiring. U telefonda ishlaydi, avtomobilda ko'rinadi va bosishga javob beradi

Telefon ilovalaringizni virtual displeyda ishga tushiradi, ekranni H.264 video sifatida kodlaydi va uni avtomobilga uzatadi. Avtomobil ekranidagi bosishlar telefonga qaytarib yuboriladi va haqiqiy sensorli hodisalar sifatida kiritiladi. Telefonning jismoniy ekrani o'chirilgan holda qoladi (batareyani tejash) va mustaqil ravishda ishlatilishi mumkin.

## O'rnatish

<a href="https://github.com/andersonlucasg3/dilink-auto-android/releases/latest"><img src="https://img.shields.io/github/v/release/andersonlucasg3/dilink-auto-android?label=Download%20Latest%20Release" alt="Download Latest Release"></a>

So'nggi versiyani yuklab oling yoki manba kodidan qurib oling:

1. **Qurish:** `./gradlew :app-client:assembleDebug`
2. `app-client/build/outputs/apk/debug/app-client-debug.apk` manzilidagi APK ni **faqat telefoningizga o'rnating**
3. Telefoningizda **USB disk raskadrovkani yoqing** (Sozlamalar → Dasturchi parametrlari)
4. Telefonda **DiLink-Auto** ni oching va so'ralganda Barcha fayllarga kirish ruxsatini bering
5. **Hotspotni yoqing, so'ngra avtomobil USB ga ulang** — avtomobil ilovasi WiFi ADB orqali birinchi ishga tushirishda avto-o'rnatiladi

Avtomobil APK va VD server JAR telefon APK ichiga joylashtirilgan — avtomobilga hech qachon biror narsani o'zingiz o'rnatmaysiz.

## Joriy Holat

**Ishlaydi:**
- 60 kadr/s H.264 video oqimi (8 Mbit/s CBR, Main profili, qo'l siqish orqali sozlanadi)
- To'liq sensorli kiritish (multi-tach, kattalashtirish uchun chimchilash)
- Qidiruv, alifbo tartibida saralash, 64dp piktogrammalar bilan ilova ishga tushirgich
- Progress chiziqlari bilan avtomobil ekranida bildirishnomalar, ochish uchun bosing
- GitHub Releases (reliz) yoki oldindan relizlar (disk raskadrovka) orqali o'zini yangilash
- Avto-yangilash: telefon eskirgan avtomobil ilovasini aniqlaydi va uni WiFi ADB orqali yangilaydi
- Oqim vaqtida telefon ekrani o'chirilgan (batareyani tejash)
- Barcha kerakli ruxsatlar uchun yo'naltirilgan onboarding
- Internatsionalizatsiya: ingliz, portugal, rus, belarus, fransuz, qozoq, ukrain, o'zbek
- USB uzilgandan keyin displeyni tiklash (v0.14.0+)
- BYD DiLink 3.0 (1920x990) + Xiaomi 17 Pro Max (Android 16) + POCO F5 da sinovdan o'tkazilgan

**Yaqinda:** audio oqimi, media boshqaruvi, navigatsiya vidjetlari

**Ma'lum cheklovlar:**
- VD server jarayoni USB uzilganda qayta ishga tushadi (avtomatik ravishda qayta ulanadi).
- Hotspot qo'lda yoqilishi kerak (Android 16 cheklovi).
- Ba'zida vizual artefaktlar — dekoderni qayta ishga tushirish poygasi, keyingi kalit kadrda tiklanadi (~1 s).
- Yuklama ostida oqim kechikishi ~100-200 ms. CBR 8 Mbit/s.
- To'satdan USB uzilgandan keyin displey o'chirilgan holda qolishi mumkin (v0.14.0 da tuzatilgan).
- **Ba'zi ilovalar ekranni to'ldirmaydi (letterbox/faqat portret).** DiLink-Auto landshaft yo'nalishidagi virtual displeyni avtomobil ekraniga ko'zgusidek aks ettiradi. Landshaft yo'nalishini qo'llab-quvvatlamaydigan ilovalar chiziqlar bilan yoki tor holda ko'rsatiladi — bu DiLink-Auto tomonidan emas, balki har bir ilovaning o'zi tomonidan boshqariladi. Ko'zgulash tomonidan hech narsa qilish mumkin emas.

## Hujjatlar

| Hujjat | Auditoriya | Tavsif |
|----------|----------|-------------|
| [O'rnatish qo'llanmasi](./setup.md) | Foydalanuvchilar | Batafsil o'rnatish va muammolarni bartaraf etish |
| [Arxitektura](./architecture.md) | Dasturchilar | Modul dizayni, ulanish oqimi, dizayn qarorlari |
| [Protokol Spetsifikatsiyasi](./protocol.md) | Dasturchilar | Sim formati, xabar turlari, port tayinlash |
| [Mijoz (Telefon) Ilovasi](./client.md) | Dasturchilar | ConnectionService, VD JAR joylashtirish, avto-yangilash |
| [Server (Avtomobil) Ilovasi](./server.md) | Dasturchilar | Holat mashinasi, USB ADB, VideoDecoder, avtomobil UI |
| [Progress Trekeri](./progress.md) | Hissa qo'shuvchilar | Funksiya holati, bosqichlar, yo'l xaritasi |

## Loyiha Tuzilishi

Telefon APK (`app-client`) avtomobil APK (`app-server`) va VD server JAR ikkalasini ham o'z ichiga oladi. Telefon ilovasini o'rnatganingizda, kerakli hamma narsa ichiga joylashtirilgan.

```
DiLink-Auto/
├── protocol/       Umumiy kutubxona (kadrlash, xabarlar, aniqlash, USB ADB)
├── app-client/     Telefon APK — rele, VD joylashtirish, avtomobil avto-yangilash, FileLog
├── app-server/     Avtomobil APK — UI, ulanish holat mashinasi, video dekoder
├── vd-server/      VirtualDisplay server (JAR ga kompilyatsiya qilingan, telefon tomonidan joylashtiriladi)
├── docs/           Hujjatlar
└── gradle/         Qurish tizimi
```

## Qo'llab-quvvatlash

Ushbu loyiha mustaqil ravishda ishlab chiqiladi va jamiyat yordamiga tayanadi. Har bir hissa ishlab chiqish vaqtini, sinov qurilmalarini qoplashga va loyihani hayotiy saqlashga yordam beradi.

## Hissa Qo'shish

PR lar qabul qilinadi. Texnik kontekst uchun [Arxitektura](./architecture.md) va [Protokol](./protocol.md) bo'limlariga qarang. `./gradlew :app-client:assembleDebug` bilan quring (JDK 17+, Android SDK 34).

### Tarmoqlanish Modeli (Git-Flow + Muammo Turlari)

Tarmoqlar ishlatilgan **muammo shabloniga** asoslanib muammo agenti tomonidan avtomatik ravishda yaratiladi:

| Shablon | Yorliq | Tarmoq Namunasi | Maqsadi |
|----------|-------|---------------|---------|
| Xatoni Tuzatish | `bug` | `fix/N-agent` | Xatolarni tuzatish |
| Yangi Funksiya | `feature` | `feature/N-agent` | Yangi funksiyalar |
| Tekshiruv | `investigation` | `investigate/N-agent` | Kod bazasini tekshirish |
| Hujjatlashtirish | `documentation` | `docs/N-agent` | Hujjatlarni yangilash |
| Reliz | `release` | `release/vX.Y.Z` | Reliz tayyorlash |
| Agent Vazifasi (umumiy) | — | `issue/N-agent` | Umumiy |

`release/*` dan tashqari barcha tarmoqlar PR orqali `develop` ga birlashtiriladi, `release/*` esa `main` ga yo'naltiriladi.

### CI Ish Jarayonlari

| Ish Jarayoni | Trigger | Harakat |
|----------|---------|--------|
| `build.yml` | `main` ga Push/PR | Tekshiruv: reliz APK qurish |
| `build-develop.yml` | `develop`, `release/*` ga Push/PR | Tekshiruv: disk raskadrovka APK qurish |
| `build-pre-release.yml` | `vX.Y.Z-dev-NN` tegi | Disk raskadrovka APK qurish + GitHub oldindan reliz |
| `build-release.yml` | `vX.Y.Z` tegi | Imzolangan reliz APK qurish + GitHub Release |
| `sync-main-to-develop.yml` | `main` ga Push | `main` → `develop` birlashtirish (git-flow teskari sinxronlash) |
| `issue-agent.yml` | Muammo ochildi / izoh | Avtonom agent: tarmoq, qurish, PR |

Barcha CI **o'z-o'zidan joylashtirilgan WSL runnerlarida** ishlaydi.

**Reliz jarayoni:** Shablondan Release muammosini yarating. Agent `release/vX.Y.Z` ni yaratadi, o'zgarishlarni tayyorlaydi va `vX.Y.Z-dev-NN` tegini qo'yadi. Reliz tarmog'ini yuborish `build-pre-release.yml` ni ishga tushiradi, u `git tag --points-at HEAD` orqali kommitdagi `-dev` tegini topadi va oldindan relizni nashr etadi. Tayyor bo'lganda, `release/vX.Y.Z` birlashtirish kommitida `vX.Y.Z` tegi bilan `main` ga birlashtiriladi. `main` ga Push `build-release.yml` (imzolangan APK quradi + GitHub Release yaratadi) va `sync-main-to-develop.yml` (`main` ni `develop` ga avto-birlashtiradi) ish jarayonlarini ishga tushiradi.

**Oldindan reliz yangilanishlari:** Oldindan reliz kanalidagi foydalanuvchilar `-dev` qurilmalarini oladi. Reliz kanalidagi foydalanuvchilar faqat barqaror qurilmalarni oladi. Kanal Sozlamalarda sozlanadi.

## Litsenziya

MIT — [LICENSE](../LICENSE) ga qarang
