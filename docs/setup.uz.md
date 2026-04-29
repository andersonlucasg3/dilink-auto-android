# Sozlash qo'llanmasi

## Kerakli shartlar

- **Telefon:** USB nosozliklarni tuzatish yoqilgan har qanday Android 10+ qurilmasi
- **Avtomobil:** BYD DiLink 3.0+ (yoki USB xost portiga ega har qanday Android 10+ bosh qurilmasi)
- **USB kabeli:** Telefondan avtomobilning USB portiga
- **Dasturlash:** Android Studio yoki Gradle, JDK 17

## Telefonni sozlash (bir martalik)

1. **DiLink Auto Client-ni o'rnating**: `adb install app-client-debug.apk`
2. **Ilovani oching** — boshlang'ich sozlash ekrani sizni har bir ruxsat bo'yicha yo'naltiradi:
   - **Barcha fayllarga kirish** — virtual displey serverini xotiraga joylashtiradi
   - **Batareyani optimallashtirish** — ekran o'chiq bo'lganda uzatishni davom ettiradi
   - **Maxsus imkoniyatlar xizmati** — avtomobildan sensorli boshqaruvni yoqadi
   - **Bildirishnomalarga kirish** — telefon bildirishnomalarini avtomobil displeyiga yuboradi
3. Har bir qadam tegishli tizim sozlamalarini ochadi. Ruxsatni bering, so'ngra davom etish uchun Orqaga tugmasini bosing.
4. Istalgan qadamni o'tkazib yuborib, keyinroq asosiy ekrandan sozlashingiz mumkin.

Bo'ldi. Simli tarmoqsiz nosozliklarni tuzatish, juftlash kodlari, maxsus WiFi sozlamalari kerak emas.

## Avtomobilni sozlash

Avtomobilga qo'lda o'rnatish shart emas. Avtomobil APK-si telefon APK-si ichiga joylashtirilgan. Birinchi ulanishda (yoki versiya mos kelmasligida) telefon avtomobilga `UPDATING_CAR` yuboradi (u "Yangilanmoqda..." holatini ko'rsatadi), so'ngra dadb (WiFi ADB) orqali avtomatik o'rnatadi.

Shu bilan bir qatorda, telefon ilovasidagi "Avtomobilga o'rnatish" tugmasidan foydalanib, avtomobil APK-sini qo'lda yuborishingiz mumkin.

Qo'lda o'rnatishda: `adb install app-server-debug.apk` (avtomobilga ADB kirishini talab qiladi).

## Kundalik foydalanish

1. **Telefonni avtomobilning USB portiga ulang** — avtomobil ilovasi avtomatik ishga tushadi (yoki bir tarmoqda WiFi orqali ulaning)
2. Avtomobil va telefon parallel WiFi + USB treklari orqali avtomatik ulanadi
3. Telefon VD serverini joylashtiradi va uzatish 60 kadr/s tezlikda boshlanadi
4. Telefon ilovalari bilan o'zaro aloqa qilish uchun avtomobilning sensorli ekranidan foydalaning

## Qurish

```bash
# Telefon APK-sini qurish (serverni qurish + joylashtirishni avtomatik ishga tushiradi)
./gradlew :app-client:assembleDebug

# APK joylashuvi:
# app-client/build/outputs/apk/debug/app-client-debug.apk  (telefon -- ichiga joylangan avtomobil APK-sini o'z ichiga oladi)
```

Qurish tizimi avtomatik ravishda app-serverni quradi va uni app-client-ga joylashtiradi, shuning uchun faqat bitta APK-ni qo'lda o'rnatish kerak.

## Bu qanday ishlaydi

Telefon avtomobilga ulanganda:

1. **Avtomobil parallel treklarni ishga tushiradi** — WiFi topish va USB aniqlash bir vaqtda bajariladi
2. **A treki (WiFi):** shlyuz IP + mDNS topish → telefonning boshqaruv portiga NIO ulanish (9637)
3. **B treki (USB):** qurilmalarni skanerlash → USB ADB ulanish → `am start` orqali telefon ilovasini ishga tushirish
4. **Handshake:** avtomobil viewport + DPI + appVersionCode + targetFps yuboradi; telefon qurilma ma'lumoti + vdServerJarPath yuboradi
5. **Versiyani tekshirish:** telefon appVersionCode-ni taqqoslaydi — agar mos kelmasa, avtomobilga UPDATING_CAR xabarini yuboradi, so'ngra dadb orqali avtomatik yangilaydi
6. **3 ulanishni sozlash:** avtomobil handshake-dan keyin video (9638) + input (9639) ulanishlarini ochadi
7. **Telefon VD serverini joylashtiradi** — vd-server.jar-ni `/sdcard/DiLinkAuto/` ichiga chiqaradi, `app_process`-ni shell UID sifatida FPS argumenti bilan ishga tushiradi
8. **VD serveri telefonga teskari ulanadi** localhost:19637 bo'yicha (bloklanmaydigan NIO)
9. **VD serveri VirtualDisplay yaratadi** telefonning tabiiy DPI (480dpi) da GPU kichraytirishi va davriy qayta chizish bilan
10. **Video WiFi TCP orqali uzatiladi** (video ulanishi 9638) — H.264, Main profili, 8 Mbit/s CBR, 60 kadr/s gacha sozlanadi

## Muammolarni bartaraf etish

### ADB autentifikatsiya dialogi har safar paydo bo'ladi (v0.13.1 da TUZATILDI)
Tuzatildi — muammo AUTH_TOKEN-ni ikki marta xeshlashda edi. ADB 20 baytlik xom tokenni yuboradi, uni oldindan xeshlangan SHA-1 dayjesti sifatida ko'rib chiqish kerak. Eski kod `SHA1withRSA` ishlatgan, u uni qayta xeshlagan. Endi AOSP `RSA_sign(NID_sha1)` ga mos keluvchi SHA-1 DigestInfo prefiksi bilan (oldindan xeshlangan) `NONEwithRSA` ishlatiladi. Telefon qayta ulanishda AUTH_SIGNATURE-ni qabul qiladi va "Har doim ruxsat berish" to'g'ri saqlanadi.

Agar dialog yangilanishdan keyingi birinchi ulanishda hali ham paydo bo'lsa, "Har doim ruxsat berish" belgilang — u qayta paydo bo'lmasligi kerak. Agar muammo saqlanib qolsa, Dasturchi opsiyalarida "ADB avtorizatsiya taym-autini o'chirish" (Android 11+) ni tekshiring.

### Telefon ilovasi ishga tushmaydi
- Telefonda USB nosozliklarni tuzatish yoqilganiga ishonch hosil qiling
- Avtomobilning USB porti xost rejimini qo'llab-quvvatlashini tekshiring (barcha portlar qo'llab-quvvatlamaydi)
- Boshqa USB kabelini sinab ko'ring (ba'zi kabellar faqat zaryadlash uchun)

### Barcha fayllarga kirish ruxsati rad etildi
- Telefon ilovasi vd-server.jar-ni `/sdcard/DiLinkAuto/` ichiga joylashtirish uchun MANAGE_EXTERNAL_STORAGE kerak
- Sozlamalar -> Ilovalar -> DiLink Auto -> Ruxsatlar -> Barcha fayllarga kirish -> YOQISH

### Video ko'rsatilmayapti / qora ekran
- Telefon va avtomobil bir tarmoqda ekanligiga ishonch hosil qiling
- Ikkala ilova ham ishlayotganligini tekshiring (telefon "Uzatilmoqda" ko'rsatadi, avtomobil video ko'rsatadi)
- VD serveriga ishga tushish uchun vaqt kerak bo'lishi mumkin — ulangandan keyin 5-10 soniya kuting
- Davriy qayta chizishga ega SurfaceScaler statik kontentda ham kadrlar chiqarishi kerak
- Diagnostika ma'lumoti uchun `/sdcard/DiLinkAuto/client.log` ni tekshiring

### Ulanish uziladi
- Ilgari aloqasiz tarmoq uzilishlari (mobil ma'lumotlarni almashtirish) proaktiv ajratishni keltirib chiqargan
- v0.12.5 da tuzatilgan: aqlli tarmoq qayta chaqiruvi ulanishni tashimaydigan tarmoqlardagi uzilishlarni e'tiborsiz qoldiradi
- Agar muammo saqlanib qolsa, `/sdcard/DiLinkAuto/client.log` ichidan "Network lost" yozuvlarini tekshiring

### Avtomobil ilovasi yangilanmayapti
- Telefon handshake paytida versiya mos kelmasligi aniqlanganda avtomobil ilovasini avtomatik yangilaydi
- Avtomobil yangilanish paytida "Avtomobil ilovasi yangilanmoqda..." holatini ko'rsatadi
- Shuningdek, telefon ilovasidagi "Avtomobilga o'rnatish" tugmasi orqali yangilashni qo'lda ishga tushirishingiz mumkin
- dadb avtomobilga WiFi ADB orqali yetib borishiga ishonch hosil qiling

### Loglar
- Telefon loglari: `/sdcard/DiLinkAuto/client.log` (joriy sessiya)
- Oldingi sessiyalar: `/sdcard/DiLinkAuto/client-YYYYMMDD-HHmmss.log`
- VD server loglari: `/data/local/tmp/vd-server.log` (telefonda, ADB orqali o'qiladi)
- Avtomobil loglari: protokol DATA kanali orqali telefon client.log-iga yo'naltiriladi (teg: `CarLog`)
- Loglarni olish: `adb shell "cat /sdcard/DiLinkAuto/client.log"`

## HyperOS (Xiaomi) bo'yicha maslahatlar

HyperOS tizimida ishonchli ishlash uchun:
1. Sozlamalar -> Ilovalar -> DiLink Auto -> Avtoishga tushirish -> Yoqish
2. Sozlamalar -> Batareya -> DiLink Auto -> Cheklovlarsiz
3. Ilovani so'nggi ilovalarda mahkamlang (kartani uzoq bosish -> Qulflash)

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
