# Трэкер прагрэсу

Бягучая версія: **v0.17.0** (стабільная)
Апошняе абнаўленне: 2026-05-02

## Этапы

### v0.17.0 (2026-05-02)

- **Перазапуск дэкодэра ліквідаваны**: `MediaCodec.setOutputSurface()` замяняе `stop()`+`start()`. MirrorContent застаецца ў дрэве Compose з пераключэннем `View.INVISIBLE`.
- **Фіксаваныя 30fps`: Зніжана з 60fps да 30fps. Тэлефон не пераграваецца. Нагрузка CPU/GPU зніжана ўдвая.
- **Адаптыўны framerate выдалены**: Рэжым 15fps выклікаў падзенне кадраў пры пераключэнні праграм.
- **Дакументацыя**: Усе змены v0.17.0 задакументаваны з перакладам на 8 моў.

### v0.17.0-dev-02 (2026-05-01)

- **Выпраўленне перагрэву тэлефона**: Ухілены шаблоны актыўнага чакання CPU у канвееры струменевай перадачы, якія выклікалі перагрэў тэлефона. Заменены busy-waits `delay(1)` на механізмы на аснове blocking/selector.
- **AppIconCache перанесены на бок аўтамабіля**: Кэш іконак на баку аўтамабіля захоўвае зыходныя PNG (192x192) на дыск. `prepareAll()` дэкадуе+змяняе памер усіх іконак у фонавым патоку перад з'яўленнем сеткі; `getPrepared()` гэта O(1) пошук ConcurrentHashMap без I/O пры пракрутцы. Ліквідавана дэкадаванне на плітку і падзенне пры хуткай пракрутцы.
- **AppTile спрошчаны**: Выдалены StateFlow collect на плітку, лянівы DropdownMenu і эфекты click ripple. Лёгкія пліткі з clickable замест combinedClickable для асноўнага націску.
- **App grid дэдублікацыя**: Выпраўлена падзенне LazyGrid шляхам дэдублікацыі элементаў па packageName.

### v0.17.0-dev-01 (2026-04-30)

- **Закрыццё апавяшчэнняў па адным і Clear All**: Экран апавяшчэнняў аўтамабіля цяпер мае кнопкі закрыцця па элеменце з анімацыяй slide-out і кнопку "Clear All" у загалоўку. Новыя паведамленні пратаколу: `NOTIFICATION_CLEAR` (0x04) і `NOTIFICATION_CLEAR_ALL` (0x05) на канале даных. Іконкі элементаў з payload `iconPng` тэлефона.
- **Кантэкстныя дзеянні прыкладанняў**: Доўгі націск на пліткі прыкладанняў (launcher) і нядаўнія прыкладанні панэлі навігацыі паказвае выпадальнае меню з Выдаліць і Інфармацыя пра прыкладанне. Распаўсюджанне выдалення праз `APP_UNINSTALL` (0x1B) / `APP_UNINSTALLED` (0x06). Інфармацыя пра прыкладанне паказвае дыялог на баку аўтамабіля з метаданымі `APP_INFO_DATA` (0x07) ад тэлефона. Дзеянні кантэкстнага меню праходзяць праз VD-сервер для доступу на ўзроўні shell.
- **Інфраструктура ярлыкоў прыкладанняў** (адключана ў UI): Паведамленні пратаколу `APP_SHORTCUTS` (0x18) / `APP_SHORTCUTS_LIST` (0x19) / `APP_SHORTCUT_ACTION` (0x1A) з запытам праз VD-сервер + APK XML fallback. Адключана да дапрацоўкі разрознення метак (issue #57).
- **Выпраўленне кнопкі назад**: GO_BACK цяпер закрывае актыўнасці па адной перад вяртаннем у галоўнае меню, выкарыстоўваючы адсочванне стэку і паведамленні `FOCUSED_APP` (0x16).
- **DPI для Samsung DeX / Рэжым працоўнага стала** (адменена): Першапачатковая рэалізацыя з выяўленнем `UiModeManager.currentModeType` і 213dpi была адменена ў dev-02. Заменена падыходам выдалення флага на ўзроўні VD.

### v0.16.0 (2026-04-29)

- **Shizuku**: Праграма цяпер з'яўляецца ў спісе аўтарызаваных праграм Shizuku (дададзены ShizukuProvider ContentProvider). Картка налад адкрывае праграму Shizuku непасрэдна для кіравання дазволамі.
- **Выпраўленне Shizuku exec**: Выпраўлены EBADF ад ParcelFileDescriptors у транзакцыях binder шляхам дубліравання FD перад чытаннем. `pm install` праз Shizuku для бясшумнага самаабнаўлення.
- **Рэжым Shizuku на аўтамабілі**: Падключэнне аўтамабіля больш не затрымліваецца на "Чаканне WiFi" пры актыўным Shizuku — цыкл паўторных спроб IP шлюза больш не абрываецца пасля першай спробы.
- **Праверка версій пераведзена на versionName**: Абнаўленні праграмы аўтамабіля цяпер параўноўваюць радкі versionName (семантычнае версіянаванне) замест цэлых лікаў versionCode, што дазваляе перадрэлізныя абнаўленні аўтамабіля.
- **Узмацненне бяспекі**: Выдалены нявыкарыстоўваемыя дазволы `RECORD_AUDIO` і `SYSTEM_ALERT_WINDOW`. Служба адмысловых магчымасцей больш не слухае падзеі `typeAllMask` (выкарыстоўвае толькі `dispatchGesture`).
- **Прадукцыйнасць сеткі прыкладанняў**: Выпраўлены збой пры хуткай пракрутцы на дысплеі аўтамабіля. `GridCells.Adaptive` → `GridCells.Fixed` з вылічальнымі калонкамі. Лянівае дэкадзіраванне bitmap на плітку з `inSampleSize=2` + `RGB_565`.
- **Стабільнасць сеткі**: `NetworkCallback` на баку тэлефона цяпер фільтруецца толькі па `TRANSPORT_WIFI`, ігнаруючы ваганні мабільных даных 3G/4G.

### v0.15.0 (2026-04-28)

- **Phone service auto-start**: `ConnectionService` аўтаматычна запускаецца пры адкрыцці прыкладання тэлефона (напрыклад, праз USB ADB аўтамабіля), пазбаўляючы ад неабходнасці ручнога націску Start. ✅ Done
- **Car no longer clears phone task**: Выдалена `--activity-clear-task` з запуску тэлефона праз USB ADB аўтамабіля. Калі прыкладанне тэлефона ўжо адкрыта, аўтамабіль працягвае без яго перапынення. ✅ Done
- **Share Logs button**: Кнопка "Share Logs" на галоўным экране архівуе ўсе файлы `*.log` з `/sdcard/DiLinkAuto/` і перадае праз Android share sheet. `FileLog.zipLogs()` стварае `dilinkauto-logs.zip`. ✅ Done
- **Encoder configuration**: Наладжана на 8Mbps CBR Main profile для шырэйшай сумяшчальнасці прылад. Дададзены backpressure (адкідвае неключавыя кадры, калі чарга запісу перавышае 6 кадраў). ✅ Done
- **VideoDecoder catchup**: Чатыры зоны паскарэння (normal, gentle 1.5x, medium 2x, aggressive 3x) для больш плыўнага аднаўлення затрымкі. ✅ Done
- **French translation**: Дададзена французская (fr) да існуючых 7 моў (цяпер 8 усяго). ✅ Done
- **Update check on app open**: Праверка самаабнаўлення запускаецца адразу пры адкрыцці прыкладання, з апавяшчэннем аб абнаўленні і кнопкай паўторнай праверкі. ✅ Done
- **Distribution channel selector**: Картка налад для выбару паміж stable releases і dev prereleases для самаабнаўлення. ✅ Done
- **CarLaunchScreen redesign**: Двухкалонкавы макет, аптымізаваны для шырокіх аўтамабільных дысплеяў. ✅ Done
- **Phone app UI refactor**: Рэарганізаваны галоўны экран, выпраўлены памылкі патоку ўстаноўкі. ✅ Done
- **Onboarding improvements**: Перадумовы наладкі аўтамабіля, палепшаны UI прагрэсу ўстаноўкі, палепшана картка How to Connect. ✅ Done
- **Car UI two-mode separation**: Экран запуску (поўнаэкранны, арыентаваны на злучэнне) і рэжым streaming (панэль навігацыі + змесціва). Плыўны пераход пры паступленні спісу прыкладанняў. ✅ Done
- **Video artifact fixes**: Разумнае скіданне дэкодэра + graduated catchup + backpressure кадавальніка ліквідуюць візуальныя артэфакты. ✅ Done
- **Touch input fixes**: Карэктнае адлюстраванне каардынат пры фіксаваным 480dpi DPI VD-сервера, інкрэментная адпраўка дотыкаў на MOVE, выпраўленні жэсту націску і ручнога IP. ✅ Done
- **Screen restore and network stability**: Аднаўленне дысплея пасля адключэння USB, паляпшэнні сеткавага зваротнага выкліку. ✅ Done
- **Internationalization**: Усе новыя радкі UI перакладзены на 8 моў (en, pt-BR, ru, be, fr, kk, uk, uz). ✅ Done
- **CI/CD automation**: 6 спецыялізаваных workflows — validation (`build.yml`, `build-develop.yml`), pre-release на `-dev` тэгах (`build-pre-release.yml`), release на `vX.Y.Z` тэгах (`build-release.yml`), зваротная сінхранізацыя main→develop (`sync-main-to-develop.yml`), і аўтаномны issue-agent (`issue-agent.yml`). ✅ Done

### v0.14.0

- **Shared version source**: Код/імя версіі цяпер у gradle.properties — адна праўка для абодвух прыкладанняў.
- **MAX_PAYLOAD_SIZE 2MB → 128MB**: Спіс прыкладанняў з 136+ іконкамі PNG перавышаў 2MB, выклікаючы ProtocolException і скіды злучэння.
- **Display restore fix**: `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` з `ACQUIRE_CAUSES_WAKEUP` аднаўляе дысплей пасля адключэння USB, нават калі VD-сервер памірае без ачысткі.
- **POCO F5 compatibility**: `FLAG_KEEP_SCREEN_ON` прадухіляе блакаванне экрана падчас streaming. Увод дотыку пацверджана працуе на POCO F5 з Xiaomi 17 Pro Max.
- **Car-side touch logging**: Падзеі дотыку MirrorScreen і поспех sendTouchEvent лагуюцца для адладкі.
- **Developer credit in About**: "Developed with ❤" са спасылкай на GitHub на ўсіх 7 мовах.

### v0.13.1 — First Release (2026-04-26)

- **Onboarding flow**: Кіраваная наладка дазволаў пры першым запуску (All Files, Battery, Accessibility, Notifications). Аўтаматычна выяўляе дазволы, рэзервовы апыт для дыялогавых налад.
- **Self-update (UpdateManager)**: Правярае GitHub Releases API, спампоўвае APK з прагрэсам, усталёўвае праз сістэмны ўсталёўшчык пакетаў. 6-гадзінны перапынак.
- **Main view reorganization**: Галоўны экран арыентаваны на штодзённае выкарыстанне (кіраўніцтва па злучэнні, статус, start/stop, абнаўленні). Экран налад з дазволамі, устаноўкай на аўтамабіль, about і спасылкамі для ахвяраванняў.
- **USB + WiFi install on car**: Паралельны сканер падсетак правярае ўсе 254 IP на ADB аўтамабіля. Камбінавана з ARP/neighbor/gateway discovery. USB host паспрабаваны, але USB-A аўтамабіля толькі host.
- **VD server now Kotlin Gradle module**: Залежыць ад :protocol і kotlinx-coroutines. Падзяляе NioReader, FrameCodec.writeAll.
- **Performance**: Ліквідавана прамежкавае вылучэнне ByteArray на кадр у кадавальніку. Пачатковая ёмістасць NioReader 256KB. isKeyFrame кэшуецца ў FrameData.
- **Encoder**: CBR 8Mbps, Main profile, наладжвальны FPS (па змаўчанні 30, аўтамабіль запытвае 60), PRIORITY 0 (real-time). I_FRAME_INTERVAL=1s. `repeat-previous-frame-after`=500ms для статычнага змесціва.
- **Donations**: GitHub Sponsors і Pix (Brazil) бэйджы ў README і наладах прыкладання.
- **Adaptive vector icon**: Сілуэт аўтамабіля з бесправаднымі сігналамі, ужыты да абодвух прыкладанняў.
- **Internationalization**: Радковыя рэсурсы на English, Portuguese (pt-BR), Russian (ru), Belarusian (be), French (fr), Kazakh (kk), Ukrainian (uk), і Uzbek (uz).
- **Release signing**: Фіксаваны keystore з надзейным паролем. CI зборкі падпісваюць release APK праз GitHub Secrets.

### v0.13.0 — USB ADB Auth Fix (2026-04-25)

Знойдзена і выпраўлена асноўная прычына: `Signature.getInstance("SHA1withRSA")` двойчы хэшуе AUTH_TOKEN ADB. 20-байтавы токен ADB — гэта папярэдне хэшаванае значэнне — `RSA_sign(NID_sha1)` у AOSP разглядае яго як ужо хэшаванае. Цяпер выкарыстоўваецца `NONEwithRSA` з ручным дабаўленнем прэфікса SHA-1 DigestInfo ASN.1 (папярэдне хэшаванае подпісанне). "Always allow" цяпер захоўваецца карэктна — AUTH_SIGNATURE прымаецца пры перападключэнні без дыялогу.

### v0.13.0 — Display Power + Key Encoding (2026-04-25)

- **Display power via SurfaceControl (Android 14+)**: Загружае `DisplayControl` з `/system/framework/services.jar` праз `ClassLoaderFactory.createClassLoader()` + натыўную бібліятэку `android_servers`. Рэзервовы варыянт: `cmd display power-off/on`, калі reflection не спрацоўвае.
- **Screen restore on disconnect**: `VirtualDisplayClient.disconnect()` тэлефона выконвае `cmd display power-on 0` + `KEYCODE_WAKEUP` як сетку бяспекі, калі працэс VD-сервера забіты да ачысткі.
- **ADB key encoding rewrite**: Перапісана `encodePublicKey()` у дакладнай адпаведнасці з эталонам AOSP — выпраўлены канстанты, яўны `bigIntToLEPadded()`, лагаванне загалоўка struct.
- **Decoder catchup**: Калі чарга перавышае `100ms * TARGET_FPS / 1000` кадраў (6 пры 60fps), прапускае кожны другі неключавы кадр. Малюнак рухаецца з 2x хуткасцю, паступова даганяючы без рыўкоў.
- **Car log buffer**: 200 → 10,000 паведамленняў. Лагі аўтэнтыфікацыі USB ADB цяпер захоўваюцца, пакуль control-злучэнне не ачысціць іх.

### v0.12.5 — Connection Stability (2026-04-24)

- **Smart network callback**: `onLost` цяпер правярае, ці з'яўляецца страчаная сетка той, што нясе злучэнне. Ігнаруе незвязаныя страты (цыкліраванне мабільных даных). Раней любая страта сеткі забівала струменевую сесію.
- **USB ADB auth diagnostics**: Поўнае лагаванне патоку аўтэнтыфікацыі, маршрутызаванае праз carLogSend → FileLog тэлефона. Выявіла, што AUTH_SIGNATURE адхіляецца кожны раз (adbd тэлефона не распазнае захаваны ключ). На даследаванні.
- **AUTH_RSAPUBLICKEY key preview logging**: Лагуе першыя/апошнія байты публічнага ключа, адпраўленага на тэлефон, для параўнання са стандартным фарматам ADB.

### v0.12.0–v0.12.4 — Bug Fixes & Polish (2026-04-24)

- **Touch input fixed**: `handleInputFrame` апрацоўваецца на `Dispatchers.IO` (было Main, выклікала `NetworkOnMainThreadException` пры запісе на localhost socket)
- **VD server NIO command reader**: Выпраўлены бясконцы цыкл — `break` унутры switch толькі выходзіў з switch, не з цыклу разбору. Цяпер выкарыстоўваецца `break parseLoop;` з пазначаным выхадам.
- **App launch dedup**: Выдалена `--activity-clear-task` з `am start`. Існуючыя прыкладанні аднаўляюцца замест перазапуску.
- **Bitrate**: Усталявана 8Mbps CBR (зменена з 12Mbps у пазнейшых выпусках для сумяшчальнасці прылад).
- **FPS configurable**: Дададзена поле `targetFps` у HandshakeRequest. Аўтамабіль запытвае 60fps. VD-сервер прымае FPS як аргумент каманднага радка, выкарыстоўвае яго для `KEY_FRAME_RATE` кадавальніка і `FRAME_INTERVAL_MS`.
- **Nav bar**: 72dp → 76dp, іконкі 32dp → 40dp, вышыня радка 52dp → 60dp, тэкст 12sp → 14sp.
- **Launcher app icons**: 40dp → 64dp, ячэйкі сеткі 140dp → 160dp, тэкст bodyMedium → bodyLarge.
- **Search bar keyboard**: `windowSoftInputMode="adjustNothing"` + `imePadding()` на TextField. Клавіятура не штурхае activity, рухаецца толькі радок пошуку.
- **Notifications**: Дэдублікацыя па ID (абнаўленні прагрэсу замяняюць існуючыя), падтрымка паласы прагрэсу (determinate + indeterminate), запуск па націску прыкладання-ўладальніка на VD + пераключэнне на люстэркавы выгляд.
- **Recent apps**: `pruneUnavailable()` выдаляе прыкладанні, якіх больш няма на тэлефоне пры абнаўленні спісу.
- **USB ADB key storage**: Прыярытэтны парадак: `/sdcard/DiLinkAuto/` → `getExternalFilesDir` → `getFilesDir`. Міграцыя шукае ва ўсіх месцах.
- **Update flow**: Тэлефон адпраўляе паведамленне `UPDATING_CAR`. Аўтамабіль паказвае "Updating car app..." статус і не перападключаецца.
- **Update flow crash fix**: Аўтамабіль прапускае відэа/уваходныя злучэнні, калі ўсталяваны флаг `updatingFromPhone`.
- **VideoDecoder/UsbAdbConnection logSink**: Лагі з боку аўтамабіля маршрутызуюцца праз пратакол да FileLog тэлефона.

### v0.11.0–v0.11.3 — Non-Blocking Pipeline + Encoder Fix (2026-04-24)

- **VideoConfig**: Агульныя канстанты `TARGET_FPS` і `FRAME_INTERVAL_MS`. Усе чаканні/апытанні відэа-шляху абмежаваны інтэрвалам кадра.
- **SurfaceScaler periodic re-draw**: Заўсёды выклікае `glDrawArrays + eglSwapBuffers` кожны інтэрвал кадра, нават калі няма новага кадра ад VD. Выклікае `updateTexImage` толькі пры наяўнасці новага кадра. Сілкуе кадавальнік на статычным змесце.
- **VD server NIO**: Заменены блакавальны `DataOutputStream/DataInputStream` на NIO чаргу запісу (`ConcurrentLinkedQueue<ByteBuffer>`) + чытальнік каманд на аснове Selector. Ніякага блакавальнага I/O нідзе ў канвееры.
- **Encoder poll**: `dequeueOutputBuffer` timeout зменшаны са 100ms да `FRAME_INTERVAL_MS` (16ms пры 60fps).
- **VideoDecoder queue poll**: 100ms → `FRAME_INTERVAL_MS`.
- **NioReader select timeout**: 100ms → `FRAME_INTERVAL_MS` (наладжвальны праз параметр канструктара).
- **Connection writer park**: 50ms → `FRAME_INTERVAL_MS`.
- **VirtualDisplayClient accept loop**: 100ms → `FRAME_INTERVAL_MS`.
- **VideoDecoder early start**: Запускаецца на пазаэкранным SurfaceTexture пры паступленні першага кадра CONFIG (перад MirrorScreen). MirrorScreen перазапускае дэкодэр з рэальнай паверхняй TextureView.
- **VideoDecoder queue**: 3 → 30 кадраў. Кадры ставяцца ў чаргу нават да выкліку `start()`.
- **FileLog**: Файлавы логер (`/sdcard/DiLinkAuto/client.log`) абыходзіць фільтрацыю logcat у HyperOS. Ратацыя: архівуе як `client-YYYYMMDD-HHmmss.log`, захоўвае макс. 10.

### v0.10.0 — 3-Connection Architecture (2026-04-24)

Адзінае мультыплексаванае TCP-злучэнне падзелена на 3 спецыялізаваныя злучэнні для ліквідацыі міжканальнай інтэрферэнцыі, якая выклікала спыненні відэа:
- **Control connection** (порт 9637): handshake, heartbeat, каманды прыкладанняў, DATA channel
- **Video connection** (порт 9638): толькі H.264 CONFIG + FRAME (phone → car)
- **Input connection** (порт 9639): толькі падзеі дотыку (car → phone)

Кожнае злучэнне мае ўласны экзэмпляр `Connection` з незалежнымі SocketChannel, NioReader і чаргой запісу. Heartbeat/watchdog толькі на control.

### v0.9.2 — Diagnostic Build (2026-04-23)

Усёабдымнае лагаванне для даследавання спынення відэакадраў пасля ~420 кадраў:
- **Video relay loop**: лагуе да/пасля readByte, памер payload, невядомыя msgTypes
- **NioReader**: лагуе, калі channel.read() вяртае 0 (кожнае 100-е здарэнне са станам буфера)
- **Connection writer**: лагуе кожныя 60 відэакадраў (колькасць, памер, глыбіня чаргі, спыненні), лагуе спыненні запісу
- **Writer stall fix**: `Thread.yield()` → `delay(1)` у writeBuffersToChannel — вызваляе паток IO назад у пул каруцін замест busy-waiting (выснова даследавання: Thread.yield марыў голадам каруціну рэтрансляцыі відэа)
- **Frame listeners**: апрацоўшчыкі не-відэа кадраў апрацоўваюцца асінхронна (`scope.launch`), каб цяжкая апрацоўка (дэкадаванне спісу прыкладанняў) не блакавала чытальнік ад спусташэння TCP

### v0.9.0-v0.9.1 — Write Stall Investigation (2026-04-23)

Даследаванне асноўнай прычыны спынення відэа. Дададзена лагаванне памеру буфера TCP, дыягностыка спыненняў запісу.
- Пацверджана, што буфер адпраўкі TCP завісае на 108,916 байт астатку падчас адпраўкі спісу прыкладанняў
- Пацверджана, што самі відэакадры маюць нуль спыненняў запісу (queue=0 падчас відэа)
- Пацверджана, што ключ USB ADB стабільны (LOADED fp=c4e88a05) — паўторная аўтэнтыфікацыя з'яўляецца паводзінамі HyperOS

### v0.8.4-v0.8.8 — Bug Fixes + Log Routing (2026-04-23)

- **Car log routing**: Усе выклікі `Log.*` з боку аўтамабіля маршрутызуюцца праз `carLogSend()`, якая адпраўляе праз DATA-канал `CAR_LOG` на тэлефон. Тэлефон лагуе з тэгам `CarLog` у logcat. Буфер да 200 паведамленняў да ўсталявання злучэння.
- **VD server launch reverted** да `shellNoWait` + `exec app_process` (падыход v0.6.2). Аддзяленне `setsid`/`nohup` парушыла сувязь localhost. VD-сервер памірае пры адключэнні USB, але аднаўляецца пры паўторным падключэнні.
- **VD ServerSocket**: `startListening()` адкрываецца сінхронна, `waitForVDServer()` прапускае, калі ўжо чакае
- **USB ADB key persistence**: `getExternalFilesDir` з праверкай запісвальнасці + міграцыя з `getFilesDir` + лагаванне адбіткаў
- **ClosedSelectorException**: праверкі `selector.isOpen` у writer і NioReader
- **Infinite recursion fix**: масавая замена Log→carLogSend выпадкова закранула саму carLogSend

### v0.8.3 — Final Polish + VD Wait Fix + USB Key Diagnostic (2026-04-23)

Апошні этап + выпраўленні памылак:
- **VD wait guard**: `waitForVDServer` прапускае, калі ўжо чакае — прадухіляе закрыццё/пераадкрыццё ServerSocket, калі аўтамабіль адпраўляе некалькі handshake падчас перападключэння (асноўная прычына немагчымасці падключэння VD-сервера, пацверджана праз logcat тэлефона, які паказвае 4x `startListening` за 45s)
- **USB key diagnostic**: Аўтамабіль піша інфармацыю аб ключы (`LOADED`/`GENERATED` + адбітак + шлях) у `/data/local/tmp/car-adb-key.log` на тэлефоне пасля USB ADB connect. Дазваляе дыягнаставаць, ці паўтараецца аўтэнтыфікацыя з-за змены ключа, ці тэлефон не захоўвае "Always allow."
- **M3**: Пакетны multi-touch — новы тып паведамлення `TOUCH_MOVE_BATCH` (0x04) змяшчае ўсе паказальнікі ў адным кадры. Падзеі MOVE пакетуюцца на баку аўтамабіля, распакетуюцца на баку тэлефона. Скарачае колькасць сістэмных выклікаў з N*60/sec да 60/sec для N-пальцавых жэстаў.
- **M10**: Стан Eject захоўваецца ў SharedPreferences — перажывае забойства/перазапуск прыкладання аўтамабіля. Ачышчаецца пры паўторным падключэнні USB або ACTION_START.
- **L4**: NioReader выкарыстоўвае heap ByteBuffer замест direct — дэтэрмінаваная ачыстка GC.

### v0.8.2 — Polish + VD ServerSocket + USB Key Persistence (2026-04-23)

6 паляпшэнняў + 2 крытычныя выпраўленні:
- **Hotfix**: VirtualDisplayClient падзелены на `startListening()` (сінхроннае звязванне) + `acceptConnection()` (асінхроннае чаканне). ServerSocket адкрываецца ПЕРАД адказам handshake — выпраўляе немагчымасць падключэння VD-сервера на localhost:19637.
- **USB key persistence**: Захоўванне ключоў выкарыстоўвае `getExternalFilesDir` з праверкай запісвальнасці + міграцыя з `getFilesDir`. Лагаванне адбіткаў пры кожным злучэнні для дыягностыкі змены ключа паміж злучэннямі.
- **M12**: `checkStackEmpty` выкарыстоўвае прасцейшы `grep -E` замест крохкага разбору секцый `sed`
- **L2**: Буферы сокета localhost VD-сервера ўсталяваны на 256KB
- **L3**: Дэкодэр выкарыстоўвае `System.nanoTime()/1000` для timestamp (было фіксаванае павелічэнне 33ms)
- **L5**: `UsbAdbConnection.readFile()` выкарыстоўвае цыкл чытання + try-with-resources
- **L6**: HandlerThread SurfaceScaler карэктна завяршаецца пры спыненні
- **L7**: Іконкі прыкладанняў павялічаны з 48x48 да 96x96px

### v0.8.1 — Touch + Decoder Performance + Hotfixes (2026-04-23)

4 аптымізацыі прадукцыйнасці + 2 выпраўленні крахаў:
- **M2**: `checkStackEmpty()` працуе ў фонавым патоку — чытальнік каманд больш не блакуецца на 300ms+ пасля націску Back
- **M4**: Папярэдне вылучаныя пулы `PointerProperties[10]` + `PointerCoords[10]` у VD-серверы — ліквідуе ціск GC на кожны дотык
- **M5**: Чарга кадраў дэкодэра зменшана з 6 да 3 (200ms → 100ms мяжа затрымкі)
- **M6**: `cmd display power-off 0` працуе ў патоку "fire-and-forget" — прыбрана дрыгаценне са шляху ін'екцыі дотыку
- **Crash fix**: `ClosedSelectorException` у Connection writer + NioReader — дададзены праверкі `selector.isOpen` і блок catch. Гонка: `disconnect()` закрывае селектары, пакуль каруціны чытальніка/пісьменніка яшчэ выконваюцца.
- **Bug fix**: Скід `usbConnecting` у `startConnection()` цяпер таксама ахоўваецца `usbAdb == null` (другое месца гонкі USB auth, першае было выпраўлена ў v0.7.3)

### v0.8.0 — I/O Pipeline Performance (2026-04-23)

3 аптымізацыі прадукцыйнасці I/O + hotfix:
- **H2**: Gathering writes — `channel.write(ByteBuffer[])` аб'ядноўвае загаловак+payload у адзін сістэмны выклік/сегмент TCP
- **H3**: Video relay вылучае `ByteArray(size)` дакладнага памеру наўпрост — выдаляе прамежкавы `relayBuf` + `copyOf`
- **M1**: VD-сервер ахінае DataOutputStream у `BufferedOutputStream(65536)` — аб'ядноўвае малыя запісы localhost
- **Hotfix**: `waitForVDServer()` выклікаецца ПЕРАД адпраўкай адказу handshake — гарантуе, што ServerSocket на :19637 адкрыты да таго, як аўтамабіль разгорне VD-сервер (рэгрэсія v0.7.4: паслядоўнасць ставіла VD wait пасля адказу, выклікаючы збой падключэння VD-сервера)

### v0.7.4 — Write Queue + Flow Sequencing (2026-04-23)

Змена архітэктуры запісу + паляпшэнні патоку:
- **Write queue**: Заменены `synchronized(outputLock)` на бязблакавальную `ConcurrentLinkedQueue` + спецыялізаваную каруціну запісу. Пісьменнік выкарыстоўвае `delay(1)`, калі буфер адпраўкі TCP запоўнены (вызваляе паток IO у пул). Больш ніякага блакавання іншых каруцін падчас запісу.
- **H10**: Handshake → auto-update → VD deploy паслядоўна. Auto-update прыпыняе ініцыялізацыю, адключае, чакае перападключэння аўтамабіля.
- **H11**: Прагрэсіўныя статусныя паведамленні аўтамабіля: "Preparing..." → "Starting..." → "Waiting for video stream..."
- **H12**: Аўтамабіль паказвае "Check phone for authorization dialog" падчас USB ADB connect
- **Bug fix**: VD-сервер запускае home activity на VD пасля стварэння — кадавальнік адразу атрымлівае змесціва
- **Bug fix**: `usbConnecting` скідаецца толькі калі `usbAdb == null` — прадухіляе дубляванне дыялогаў USB-ADB auth

### v0.7.3 — Network Resilience + HyperOS Freeze Fix (2026-04-23)

Устойлівасць сеткі + крытычнае выпраўленне HyperOS, выяўленае праз доказы logcat:
- **Battery exemption**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — прадухіляе "greeze" HyperOS ад замарожвання кліенцкага прыкладання, пакуль экран выключаны. Асноўная прычына памылкі "frames only during touch": VD-сервер (shell process) вырабляў 960+ кадраў, але NioReader кліенцкага прыкладання быў замарожаны кіраваннем харчаваннем OS. Запыт паказваецца пры першым запуску.
- **C4/M11**: WiFi трэк аўтамабіля цяпер паўтарае gateway IP кожныя 3s (было аднаразова). Дададзены `ConnectivityManager.NetworkCallback` на аўтамабілі, які паўторна запускае WiFi трэк, калі WiFi становіцца даступным. Апрацоўвае hotspot, уключаны пасля падключэння USB.
- **H9**: Тэлефон актыўна адключае пры страце сеткі ў стане CONNECTED/STREAMING (было: чаканне 10s для тайм-аўту heartbeat). `onLost` → `cleanupSession()` → цыкл праслухоўвання перазапускаецца. `onAvailable` скідае толькі ў WAITING (без парушэння актыўных злучэнняў).
- **Bug fix**: VD-сервер запускае home activity на VD пасля стварэння (`am start --display <id> HOME`) — гарантуе, што кадавальнік адразу мае змесціва.
- **Bug fix**: `usbConnecting` скідаецца толькі калі `usbAdb == null` — прадухіляе дубляванне дыялогаў USB-ADB auth.

### v0.7.2 — Car-Side Stability + Selector Fix (2026-04-23)

5 выпраўленняў стабільнасці + крытычнае выпраўленне NioReader:
- **H1**: Заменена апытанне `delay(1)` на **Selector** у NioReader — `selector.select(100)` прачынаецца імгненна праз epoll пры паступленні даных. Выпраўляе неструменевае відэа (кадры ішлі толькі падчас дотыку на Android 10 аўтамабіля з-за таго, што `delay(1)` займала 10-16ms). Дададзены `wakeup()`/`close()` для чыстага завяршэння з disconnect.
- **H7**: `@Volatile` на `wifiReady`, `usbReady`, `vdServerStarted`, `usbConnecting` — прадухіляе захрасанне ў стане CONNECTING
- **H8**: `VideoDecoder.stop()` чакае паток падачы (тайм-аўт 2s) перад `codec.stop()` — прадухіляе native crash
- **M7**: Выдалена двайная `cleanup()` у VD-серверы — прадухіляе IllegalStateException пры двайным вызваленні
- **M8**: `cleanupSession()` скідае `_serviceState` у WAITING — прадухіляе састарэлы UI падчас затрымкі перападключэння
- **M9**: `onCreate()` ачышчае статычныя `activeConnection` і `_serviceState` — прадухіляе састарэлы стан пры перазапуску сэрвісу

### v0.7.1 — Critical Bug Fixes (2026-04-23)

6 крытычных/высокіх выпраўленняў з усёабдымнага агляду:
- **C1**: `writeAll()` 5s тайм-аўт запісу — прадухіляе завісанне сістэмы на запоўненым буферы адпраўкі
- **C3**: Флаг спробы аўтаабнаўлення — разрывае бясконцы цыкл абнаўлення/перазапуску
- **C5**: WakeLock 4h аўта-вызваленне — прадухіляе разрад батарэі пры аварыйным выхадзе
- **C6**: `@Volatile` на VirtualDisplayClient channel/reader + запісы з абаронай тайм-аўтам
- **H5**: `Connection.connect()` try/catch — закрывае SocketChannel пры адмене
- **H6**: Слухач адключэння ахінуты ў try/catch — прадухіляе распаўсюджванне выключэння

### v0.7.0 — Full NIO + Service Fix (2026-04-23)

Усе аперацыі з сокетамі пераведзены на неблакавальны NIO. Рэгістрацыя mDNS больш не блакуе цыкл праслухоўвання. Код версіі чытаецца падчас выканання праз PackageManager.

**Changes:**
- **NioReader**: Новы неблакавальны буферызаваны чытальнік для SocketChannel (апытанне delay(1), каруцінна-кааператыўны)
- **Connection.kt**: SocketChannel застаецца неблакавальным на працягу ўсяго часу — больш няма configureBlocking(true) пасля connect/accept
- **FrameCodec.kt**: Дададзены метады NIO `readFrame(NioReader)` і `writeFrameToChannel(SocketChannel, Frame)`
- **VirtualDisplayClient.kt**: NIO чытанні (NioReader) + запісы ByteBuffer, канал застаецца неблакавальным
- **VirtualDisplayServer.java**: NIO SocketChannel для connect (неблакавальны finishConnect з паўторам)
- **ConnectionService.kt**: probePort() пераведзены на NIO; рэгістрацыя mDNS запускаецца ў фоне з тайм-аўтам 5s (выпраўляе незапуск сэрвісу без WiFi)
- **Version code**: Выдалена канстанта `APP_VERSION_CODE` — абодва прыкладанні чытаюць versionCode падчас выканання праз `PackageManager.getPackageInfo()`

### v0.6.2 — Parallel Connection Model + Auto-Update (2026-04-23)

Буйная перапрацоўка архітэктуры: паралельныя трэкі WiFi + USB, неблакавальныя сокеты NIO, аўтаабнаўленне праз тэлефон, multi-touch праз IInputManager.

**Working:**
- **Parallel connection state machine**: WiFi discovery + USB ADB працуюць адначасова, VD разгортваецца, калі абодва гатовыя
- **Auto-update**: Тэлефон выяўляе састарэлае прыкладанне аўтамабіля пры handshake, адпраўляе абнаўленне праз WiFi ADB (dadb)
- **Phone deploys VD JAR**: Здабываецца ў `/sdcard/DiLinkAuto/vd-server.jar` пры запуску (правяраецца CRC32)
- **Car APK embedded in phone**: Сістэма зборкі кампілюе APK аўтамабіля ў assets тэлефона
- **NIO non-blocking sockets**: Усе accept/connect выкарыстоўваюць `ServerSocketChannel`/`SocketChannel` — імгненная адмена, без EADDRINUSE
- **Multi-touch input**: Прамая ін'екцыя MotionEvent праз `ServiceManager → IInputManager` (падтрымлівае tap, swipe, pinch)
- **Screen power management**: `cmd display power-off 0` падчас streaming, proximity/lift wake адключана, абмежаванае паўторнае выключэнне пасля ін'екцыі дотыку
- **State machine recovery**: `connectionScope` адмяняе ўсе каруціны пры адключэнні, экспаненцыяльнае перападключэнне з backoff
- **User disconnect**: Кнопка Eject спыняе перападключэнне (застаецца IDLE)
- **App search**: Поле пошуку ўнізе сеткі прыкладанняў, прыкладанні адсартаваны па алфавіце
- **Notification panel**: Іконка званка ў панэлі навігацыі з лічыльнікам
- **72dp nav bar**: Буйнейшыя іконкі (32dp) і тэкст (12sp) для аўтамабільных дысплеяў
- **Handshake version check**: Поле `appVersionCode` у HandshakeRequest, `vdServerJarPath` у HandshakeResponse
- **Network change handling**: Тэлефон перазапускае цыкл праслухоўвання пры зменах сеткавага інтэрфейсу (пераключэнне hotspot)
- **H.264 encoding**: 8Mbps CBR, High profile, рэжым нізкай затрымкі
- **Handshake timeout**: 10s тайм-аўт з карэктнай адменай (без састарэлых тайм-аўтаў)

**Architecture changes from v0.5.0:**
- Выдалена апытанне SSID hotspot/аўтападключэнне WiFi з аўтамабіля (спрошчана)
- Перанесена UsbAdbConnection + AdbProtocol у модуль protocol (агульны для абодвух прыкладанняў)
- VD-сервер падключаецца ДА тэлефона (зваротнае злучэнне) замест падключэння тэлефона да VD-сервера
- VD-сервер завяршаецца пры адключэнні тэлефона (аднаразовы, аўтамабіль паўторна разгортвае пры неабходнасці)
- Тэлефон здабывае VD JAR у агульнае сховішча, аўтамабіль чытае шлях з handshake

### v0.5.0 — USB ADB + Automated Setup (2026-04-22)

Буйная змена архітэктуры: **аўтамабіль** разгортвае VD-сервер на тэлефон праз USB ADB. Wireless Debugging ліквідавана.

### v0.4.0 — GPU-Scaled VirtualDisplay (2026-04-22)

Прыкладанні рэндэрынгу пры родным DPI тэлефона (480dpi), GPU маштабуе да вакна аўтамабіля. Канвеер SurfaceScaler EGL/GLES.

### v0.3.0 — Persistent Navigation Bar (2026-04-21)

UI аўтамабіля з заўсёды бачнай левай панэллю навігацыі, TextureView, рэальныя іконкі прыкладанняў.

### v0.2.0–v0.2.3 — Virtual Display Foundation (2026-04-21)

Стварэнне VD, self-ADB, устойлівы сервер, падтрымка некалькіх прыкладанняў.

### v0.1.0–v0.1.1 — Initial Implementation (2026-04-21)

Праект створаны. Люстэркавае адлюстраванне экрана на эмулятарах.

---

## Fix Tracker

Усёабдымны агляд выкананы 2026-04-23, ахопліваючы прадукцыйнасць, стабільнасць і бесперапыннасць патоку.

### Phase 1 — Critical (fix before next release)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| C1 | Stability/Perf | `writeAll()` круціцца бясконца на запоўненым буферы адпраўкі — няма тайм-аўту, трымае `outputLock`, блакуе ўсіх адпраўшчыкоў. Рызыка завісання сістэмы | **v0.7.1** |
| C2 | Flow | VD-сервер памірае пры адключэнні USB (`shellNoWait` звязвае працэс з струменем ADB). Поўнае перападключэнне 5-15s | REVERTED — `setsid`/`nohup` парушылі localhost. Выкарыстоўваецца `shellNoWait`+`exec` (падыход v0.6.2). Перападключаецца пры паўторным падключэнні. |
| C3 | Flow | Auto-update не мае break-цыклу — калі `pm install` ціха збоіць, бясконцы цыкл перазапуску | **v0.7.1** |
| C4 | Flow | WiFi трэк аўтамабіля працуе адзін раз і здаецца — hotspot уключаны пасля падключэння USB → назаўсёды захрасае | **v0.7.3** |
| C5 | Stability | WakeLock захоплены без тайм-аўту — разрад батарэі, калі сэрвіс забіты без `onDestroy()` | **v0.7.1** |
| C6 | Stability | `VirtualDisplayClient.touch()` неблакавальны цыкл запісу + поле `channel` не volatile — гонка даных | **v0.7.1** |

### Phase 2 — High (latency & stability)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| H1 | Perf | Апытанне NIO `delay(1)` дадае 1-4ms мінімальнай затрымкі на чытанне + 1000 абуджэнняў/сек у бяздзейнасці. Выкарыстоўваць Selector або `runInterruptible` | **v0.7.2** |
| H2 | Perf | Два сістэмныя выклікі на запіс кадра (6-байтавы загаловак + payload). Выкарыстоўваць `GatheringByteChannel.write(ByteBuffer[])` | **v0.8.0** |
| H3 | Perf | `ByteArray.copyOf()` на кожны кадр у рэтрансляцыі відэа (~30 алокацый/сек па 10-100KB). Перадаваць offset+length | **v0.8.0** |
| H4 | Perf | `synchronized(outputLock)` серыялізуе відэа+дотык+heartbeat. Запіс ключавога кадра блакуе дотык ~200ms | **v0.7.4** |
| H5 | Stability | `Connection.connect()` уцякае SocketChannel пры адмене — няма try/finally | **v0.7.1** |
| H6 | Stability | `disconnectListener` выклікаецца сінхронна ў CAS — патэнцыйны deadlock | **v0.7.1** |
| H7 | Stability | Флагі стану аўтамабіля (`wifiReady`, `usbReady`) не volatile — могуць захраснуць у CONNECTING | **v0.7.2** |
| H8 | Stability | `VideoDecoder.stop()` не чакае паток падачы перад `codec.stop()` — рызыка native crash | **v0.7.2** |
| H9 | Flow | Сеткавы зваротны выклік тэлефона ігнаруе CONNECTED/STREAMING — пераключэнне hotspot выклікае 10s замарожаны кадр | **v0.7.3** |
| H10 | Flow | Handshake + auto-update + VD deploy усе гоняцца — deployAssets можа быць не скончана, адначасовыя аперацыі ADB | **v0.7.4** |
| H11 | Flow | Няма зваротнай сувязі для карыстальніка падчас 5-12s запуску VD-сервера — аўтамабіль паказвае статычны спіннер | **v0.7.4** |
| H12 | Flow | Дыялог першай аўтэнтыфікацыі USB ADB на тэлефоне без указанняў на экране аўтамабіля — 30s тайм-аўт | **v0.7.4** |

### Phase 3 — Medium (noticeable issues)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| M1 | Perf | VD-сервер скідае пасля кожнага кадра на localhost — непатрэбны сістэмны выклік | **v0.8.0** |
| M2 | Perf | `checkStackEmpty()` блакуе чытальнік каманд на 500ms — адсутнасць дотыку пасля Back | **v0.8.1** |
| M3 | Perf | Multi-touch адпраўляе N асобных кадраў за MOVE — трэба пакетаваць усе паказальнікі | **v0.8.3** |
| M4 | Perf | PointerProperties/Coords MotionEvent вылучаюцца пры кожнай ін'екцыі — выкарыстоўваць пул | **v0.8.1** |
| M5 | Perf | Чарга кадраў дэкодэра глыбінёй 6 (200ms) — зменшыць да 2-3 для меншай затрымкі | **v0.8.1** |
| M6 | Perf | `execFast("cmd display power-off 0")` у патоку дотыку — перамясціць на таймер | **v0.8.1** |
| M7 | Stability | Двайная `cleanup()` у VD-серверы — handleClient finally + run выклікаюць абодва | **v0.7.2** |
| M8 | Stability | `cleanupSession()` не скідае `_serviceState` — састарэлы UI падчас затрымкі | **v0.7.2** |
| M9 | Stability | Статычны MutableStateFlow у companion перажывае перазапускі сэрвісу — састарэлы activeConnection | **v0.7.2** |
| M10 | Flow | Адключэнне карыстальніка (eject) не захоўваецца — аўтамабіль перападключаецца пасля забойства працэсу | **v0.8.3** |
| M11 | Flow | mDNS аўтамабіля + gateway IP probe аднаразовыя — патрэбен перыядычны паўтор | **v0.7.3** |
| M12 | Flow | Разбор `dumpsys activity` у checkStackEmpty крохкі на розных версіях Android | **v0.8.2** |

### Phase 4 — Low (polish)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| L1 | Perf | `TouchEvent.encode()` вылучае 25-байтавы ByteArray на падзею — нельга выкарыстоўваць пул з асінхроннай чаргой запісу | WONTFIX |
| L2 | Perf | Сокет localhost VD-сервера без наладкі памеру буфера адпраўкі/атрымання | **v0.8.2** |
| L3 | Perf | Відэадэкодэр выкарыстоўвае фіксаваны timestamp 33,333us — павінен выкарыстоўваць сістэмны гадзіннік | **v0.8.2** |
| L4 | Stability | NioReader direct ByteBuffer не вызваляецца дэтэрмінавана | **v0.8.3** |
| L5 | Stability | `UsbAdbConnection.readFile()` не гарантуе поўнае чытанне | **v0.8.2** |
| L6 | Stability | SurfaceScaler HandlerThread ніколі не завяршаецца | **v0.8.2** |
| L7 | Flow | Іконкі прыкладанняў 48x48px — размытыя на аўтамабільных дысплеях, патрэбна 96-128px | **v0.8.2** |

---

## Known Issues

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
