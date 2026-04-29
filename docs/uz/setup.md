# Sozlash Qo'llanmasi

## Talablar

- **Telefon:** USB disk raskadkasi yoqilgan har qanday Android 10+ qurilmasi
- **Avtomobil:** BYD DiLink 3.0+ (yoki USB xost portiga ega har qanday Android 10+ bosh qurilmasi)
- **USB kabeli:** Telefonni avtomobil USB portiga ulash
- **Dasturlash:** Android Studio yoki Gradle, JDK 17

**Internetga ulanish talab qilinmaydi** — DiLink-Auto hamma narsani telefoningizning WiFi kirish nuqtasi orqali mahalliy ravishda uzatadi (avtomobil va telefon bevosita muloqot qiladi). Internetga ulanish faqat telefoningizda ishlaydigan ilovalar uchun kerak (masalan, xaritalar, musiqa), DiLink-Auto uchun emas.

## Telefonni Sozlash (Bir Martalik)

1. **DiLink Auto Client-ni o'rnating**: `adb install app-client-debug.apk`
2. **Ilovani oching** — boshlang'ich sozlash ekrani sizni har bir ruxsatnoma bo'yicha yo'naltiradi:
   - **Barcha fayllarga kirish** — virtual displey serverini xotiraga joylashtiradi
   - **Batareyani optimallashtirish** — ekran o'chiq bo'lganda oqimli uzatishni faol ushlab turadi
   - **Maxsus imkoniyatlar xizmati** — avtomobil sensorli ekranini boshqarishni yoqadi
   - **Bildirishnomalarga kirish** — telefon bildirishnomalarini avtomobil displeyiga yo'naltiradi
3. Har bir qadam tegishli tizim sozlamalarini ochadi. Ruxsatnomani bering, so'ngra davom etish uchun "Orqaga" tugmasini bosing.
4. Istalgan qadamni o'tkazib yuborishingiz va keyinroq asosiy ekrandan sozlashingiz mumkin.

Hammasi shu. Simli disk raskadka, ulash kodlari, maxsus WiFi sozlamalari talab qilinmaydi.

## Avtomobilni Sozlash

**Internetga ulanish talab qilinmaydi.** Avtomobil APK-si telefon APK-si ichiga joylashtirilgan — telefon uni avtomobilga mahalliy WiFi orqali yuboradi. Sozlash yoki oqimli uzatish paytida hech qanday internet ulanishi kerak emas.

Avtomobilga qo'lda o'rnatish talab qilinmaydi. Avtomobil APK-si telefon APK-si ichiga joylashtirilgan. Birinchi ulanishda (yoki versiya mos kelmasligida) telefon avtomobilga `UPDATING_CAR` yuboradi (u "Yangilanmoqda..." holatini ko'rsatadi), so'ngra dadb (WiFi ADB) orqali avtomatik o'rnatadi.

Shu bilan bir qatorda, avtomobil APK-sini qo'lda o'rnatish uchun telefon ilovasidagi "Avtomobilga o'rnatish" tugmasidan foydalaning.

Qo'lda o'rnatishda: `adb install app-server-debug.apk` (avtomobilga ADB kirishni talab qiladi).

## Kundalik Foydalanish

1. **Telefonni avtomobil USB portiga ulang** — avtomobil ilovasi avtomatik ishga tushadi (yoki bir tarmoqda WiFi orqali ulaning)
2. Avtomobil va telefon parallel WiFi + USB treklari orqali avtomatik ravishda ulanadi
3. Telefon VD serverini joylashtiradi va oqimli uzatish 60fps tezlikda boshlanadi
4. Telefon ilovalari bilan ishlash uchun avtomobil sensorli ekranidan foydalaning

## Qurish

```bash
# Telefon APK-ni qurish (serverni qurish + joylashtirishni avtomatik ishga tushiradi)
./gradlew :app-client:assembleDebug

# APK joylashuvi:
# app-client/build/outputs/apk/debug/app-client-debug.apk  (telefon -- joylashtirilgan avtomobil APK-sini o'z ichiga oladi)
```

Qurish tizimi avtomatik ravishda app-server-ni quradi va uni app-client-ga joylashtiradi, shuning uchun faqat bitta APK qo'lda o'rnatilishi kerak.

## Bu Qanday Ishlaydi

Telefon avtomobilga ulanganda:

1. **Avtomobil parallel treklarni ishga tushiradi** — WiFi kashfiyoti va USB aniqlash bir vaqtda bajariladi
2. **A trek (WiFi):** shlyuz IP + mDNS kashfiyoti → telefon boshqaruv portiga NIO ulanish (9637)
3. **B trek (USB):** qurilmalarni skanerlash → USB ADB ulanish → `am start` orqali telefon ilovasini ishga tushirish
4. **Qo'l siqish:** avtomobil viewport + DPI + appVersionCode + targetFps yuboradi; telefon qurilma ma'lumoti + vdServerJarPath yuboradi
5. **Versiyani tekshirish:** telefon appVersionCode-ni taqqoslaydi — agar mos kelmasa, avtomobilga UPDATING_CAR xabarini yuboradi, so'ngra dadb orqali avtomatik yangilaydi
6. **3 ulanishni sozlash:** avtomobil qo'l siqishdan so'ng video (9638) + kiritish (9639) ulanishlarini ochadi
7. **Telefon VD serverini joylashtiradi** — vd-server.jar-ni `/sdcard/DiLinkAuto/` ga chiqaradi, `app_process`-ni shell UID sifatida FPS argumenti bilan ishga tushiradi
8. **VD serveri telefonga teskari ulanadi** localhost:19637 manzilida (NIO bloklanmaydigan)
9. **VD serveri VirtualDisplay yaratadi** telefonning tabiiy DPI (480dpi) qiymatida, GPU masshtabni pasaytirish va davriy qayta chizish bilan
10. **Video oqimi** WiFi TCP orqali (video ulanish 9638) — H.264, Asosiy profil, 8Mbps CBR, 60fps gacha sozlanadi

## Muammolarni Bartaraf Etish

### ADB autentifikatsiya dialogi har safar paydo bo'ladi (v0.13.1 da TUZATILGAN)
Tuzatildi — muammo AUTH_TOKEN-ni ikki marta xeshlashda edi. ADB xom 20-baytli tokenni yuboradi, u oldindan xeshlangan SHA-1 dayjest sifatida ko'rib chiqilishi kerak. Eski kod `SHA1withRSA` dan foydalandi, u uni qayta xeshladi. Endi SHA-1 DigestInfo prefiksi bilan `NONEwithRSA` ishlatiladi (oldindan xeshlangan), bu AOSP `RSA_sign(NID_sha1)` ga mos keladi. Telefon qayta ulanishda AUTH_SIGNATURE-ni qabul qiladi va "Har doim ruxsat berish" to'g'ri saqlanadi.

Agar dialog yangilanishdan keyin birinchi ulanishda hali ham paydo bo'lsa, "Har doim ruxsat berish" belgisini qo'ying — u keyingi ulanishlarda qayta paydo bo'lmasligi kerak. Agar muammo davom etsa, telefonning Dasturchi parametrlarida "ADB avtorizatsiya taym-autini o'chirish" (Android 11+) ni tekshiring.

### Telefon ilovasi ishga tushmaydi
- Telefonda USB disk raskadkasi yoqilganligiga ishonch hosil qiling
- Avtomobilning USB porti xost rejimini qo'llab-quvvatlashini tekshiring (barcha portlar qo'llab-quvvatlamaydi)
- Boshqa USB kabelini sinab ko'ring (ba'zi kabellar faqat zaryadlash uchun)

### Barcha fayllarga kirish ruxsatnomasi rad etildi
- Telefon ilovasiga vd-server.jar-ni `/sdcard/DiLinkAuto/` ga joylashtirish uchun MANAGE_EXTERNAL_STORAGE kerak
- Sozlamalar -> Ilovalar -> DiLink Auto -> Ruxsatnomalar -> Barcha fayllarga kirish -> YOQISH bo'limiga o'ting

### Video oqimi yo'q / qora ekran
- Telefon va avtomobil bir tarmoqda ekanligiga ishonch hosil qiling
- Ikkala ilova ham ishlayotganligini tekshiring (telefon "Oqimli uzatish" ko'rsatadi, avtomobil video ko'rsatadi)
- VD serveriga ishga tushish uchun vaqt kerak bo'lishi mumkin — ulangandan keyin 5-10 soniya kuting
- SurfaceScaler davriy qayta chizishi statik kontentda ham kadrlarni ishlab chiqarishi kerak
- Diagnostik ma'lumot uchun `/sdcard/DiLinkAuto/client.log` ni tekshiring

### Ulanish uziladi
- Ilgari aloqasiz tarmoq uzilishlari (mobil ma'lumotlarning almashtirilishi) faol uzishni keltirib chiqargan
- v0.12.5 da tuzatildi: aqlli tarmoq qayta chaqiruvi ulanishni tashimaydigan tarmoqlardagi uzilishlarni e'tiborsiz qoldiradi
- Agar muammo davom etsa, `/sdcard/DiLinkAuto/client.log` da "Network lost" yozuvlarini tekshiring

### Avtomobil ilovasi yangilanmaydi
- Telefon avtomobil ilovasini qo'l siqish paytida versiya mos kelmasligi aniqlanganda avtomatik yangilaydi
- Avtomobil yangilanish paytida "Avtomobil ilovasi yangilanmoqda..." holatini ko'rsatadi
- Shuningdek, telefon ilovasidagi "Avtomobilga o'rnatish" tugmasi orqali yangilanishni qo'lda ishga tushirishingiz mumkin
- dadb avtomobilga WiFi ADB orqali yetib olishiga ishonch hosil qiling

### Jurnallar
- Telefon jurnallari: `/sdcard/DiLinkAuto/client.log` (joriy seans)
- Oldingi seanslar: `/sdcard/DiLinkAuto/client-YYYYMMDD-HHmmss.log`
- VD serveri jurnallari: `/data/local/tmp/vd-server.log` (telefonda, ADB orqali o'qiladi)
- Avtomobil jurnallari: protokolning DATA kanali orqali telefonning client.log fayliga yo'naltiriladi (teg: `CarLog`)
- Jurnallarni olish: `adb shell "cat /sdcard/DiLinkAuto/client.log"`

## HyperOS (Xiaomi) Bo'yicha Maslahatlar

HyperOS da ishonchli ishlash uchun:
1. Sozlamalar -> Ilovalar -> DiLink Auto -> Avtoishga tushirish -> Yoqish
2. Sozlamalar -> Batareya -> DiLink Auto -> Cheklovlarsiz
3. Ilovani so'nggi ilovalarda qulflang (kartani uzoq bosish -> Qulflash)
## Samsung One UI bo'yicha maslahatlar

One UI 5+ (Android 13+) tizimidagi Samsung qurilmalari DiLink-Auto-ning to'g'ri ishlashiga to'sqinlik qilishi mumkin bo'lgan qo'shimcha xavfsizlik va energiya tejash xususiyatlariga ega. Bu Galaxy A, M, S, Z va Tab seriyalariga tegishli.

### Auto Blocker-ni o'chirish (USB ADB uchun juda muhim)

**Auto Blocker** USB buyruqlarini bloklaydi va avtomobilning telefonga USB ADB orqali ulanishiga to'sqinlik qilishi mumkin. Bu Samsung-ga xos eng keng tarqalgan muammo.

1. **Sozlamalar → Xavfsizlik va maxfiylik → Auto Blocker → O'chirilgan**
2. Agar Auto Blocker-ni yoqilgan holda qoldirishni xohlasangiz, kamida **"USB kabeli orqali buyruqlarni bloklash"** opsiyasini o'chiring

### Barcha fayllarga kirishga ruxsat berish

Samsung ruxsatlar menejeri siz yaqinda ochmagan ilovalarning ruxsatlarini avtomatik qaytarib olishi mumkin:

1. **Sozlamalar → Ilovalar → DiLink Auto → Ruxsatlar → Fayllar va media → Barcha fayllarni boshqarishga ruxsat berish**
2. **"Barcha fayllarni boshqarishga ruxsat berish"** tumblerini yoqing
3. Sozlamalarni yopgandan keyin u YOQILGAN holda qolganligini tekshiring (Samsung tasdiqlovchi pop-ap ko'rsatishi mumkin)

### Batareyani optimallashtirishni o'chirish

Samsung batareya boshqaruvi standart Android-ga qaraganda agressivroq:

1. **Sozlamalar → Ilovalar → DiLink Auto → Batareya → Cheklovsiz**
2. **Sozlamalar → Batareya → Fon foydalanish cheklovlari → Hech qachon uxlamaydigan ilovalar → DiLink Auto qo'shish**
3. **Sozlamalar → Batareya → Fon foydalanish cheklovlari → Chuqur uyqudagi ilovalar → DiLink Auto o'chirish** agar ro'yxatda bo'lsa

### Ilovani so'nggilarda mahkamlash

Samsung One UI xotirani bo'shatish uchun fon ilovalarini o'chirishi mumkin:

1. So'nggi ilovalarni oching (3 tugmali navigatsiya bilan pastdan yuqoriga suring yoki jest navigatsiyasi)
2. Karta yuqori qismidagi DiLink Auto belgisini bosing
3. **"Ochiq qoldirish"** ni tanlang

### Samsung Device Care avtomatik optimallashtirishni o'chirish

Samsung Device Care fon xizmatlarini avtomatik to'xtatishi mumkin:

1. **Sozlamalar → Batareya va qurilmaga g'amxo'rlik → Avtomatlashtirish → Kundalik avtooptimallashtirish → O'chirilgan**
2. **Sozlamalar → Batareya va qurilmaga g'amxo'rlik → Avtomatlashtirish → Avtoqayta ishga tushirish → O'chirilgan**

### "DeX" ruxsat pop-apini ko'rsangiz

Ba'zi Samsung qurilmalari ilova virtual displey yaratishga harakat qilganda "Samsung DeX" yoki "tashqi displey" ruxsatlari haqida pop-ap ko'rsatadi. Galaxy A/M seriyalari DeX-ni qo'llab-quvvatlamasa ham, dialog paydo bo'lishi mumkin. Shunchaki **"Ruxsat berish"** yoki **"Hozir boshlash"** tugmasini bosing. Agar dialog qayta-qayta paydo bo'lsa, **Sozlamalar → Ulangan qurilmalar → Samsung DeX** bo'limiga o'ting va "HDMI ulanganda avtomatik ishga tushirish" opsiyasini o'chiring.

### Knox xavfsizlik mulohazalari

Samsung Knox DiLink-Auto quyidagilarga kirganda xavfsizlik bildirishnomasini ko'rsatishi mumkin:
- Virtual displey yuzasi (video kodlash uchun)
- USB nosozliklarni tuzatish ko'prigi (avtomobil ADB ulanishi uchun)
- Barcha fayllar xotirasi (VD serverini joylashtirish uchun)

Bu kutilgan xatti-harakatlardir. Knox bilan bog'liq har qanday so'rovlarda "Ruxsat berish" yoki "OK" tugmasini bosing. Agar so'rovlar to'xtamasa, Knox himoyasini vaqtincha "O'rta" darajaga tushirishingiz mumkin: **Sozlamalar → Xavfsizlik va maxfiylik → Samsung Knox**.
