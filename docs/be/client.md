# Кліенцкае прыкладанне тэлефона (app-client)

## Агляд

Кліент тэлефона кіруе разгортваннем VD-сервера, аўтаабнаўленнем аўтамабіля і рэтрансляцыяй 3 злучэнняў. Прыкладанне тэлефона:

1. Слухае TCP-злучэнні ад аўтамабіля на парце 9637 (control, NIO ServerSocketChannel)
2. Адказвае на handshake інфармацыяй аб прыладзе, vdServerJarPath і чытае `targetFps`
3. Параўноўвае appVersionName з handshake (з fallback на versionCode) — адпраўляе `UPDATING_CAR` і аўтаматычна абнаўляе прыкладанне аўтамабіля праз dadb, калі версіі не супадаюць
4. Прымае відэа (9638) і ўваходныя (9639) злучэнні ад аўтамабіля пасля handshake
5. Разгортвае vd-server.jar у `/sdcard/DiLinkAuto/` і запускае VD-сервер (з аргументам FPS)
6. Прымае зваротнае злучэнне ад VD-сервера на localhost:19637 (NIO ServerSocketChannel)
7. Перадае H.264 відэа ад VD-сервера да аўтамабіля праз відэа-злучэнне
8. Перадае падзеі дотыку ад аўтамабіля (уваходнае злучэнне) да VD-сервера, апрацоўваюцца на `Dispatchers.IO`

**Ніякага захопу экрана. Ніякага MediaProjection.** Усё відэа паступае ад працэсу VD-сервера.

## Кампаненты

### ClientApp

Клас Application. Стварае каналы апавяшчэнняў (`dilinkauto_service`, `dilinkauto_update`), ініцыялізуе `UpdateManager` і `ShizukuManager` пры стварэнні.

### UpdateManager

Механізм самаабнаўлення, які правярае GitHub Releases на новыя версіі.
- `checkForUpdate(force)`: Запытвае `https://api.github.com/repos/andersonlucasg3/dilink-auto-android/releases/latest`, параўноўвае semver з імя тэга з усталяванай versionName. Выконвае 6-гадзінны перапынак, калі не прымусова.
- `downloadUpdate()`: Спампоўвае APK праз `HttpsURLConnection` з адлюстраваннем прагрэсу. Правярае праз `PackageManager.getPackageArchiveInfo()`.
- `installUpdate(context)`: Выкарыстоўвае Shizuku `pm install -r` для бясшумнай устаноўкі, калі Shizuku даступны; інакш адкрывае сістэмны ўсталёўшчык пакетаў праз URI `FileProvider`.
- Станы: Idle, Checking, Available, Downloading, ReadyToInstall, Installing, Installed, UpToDate, Error. Прадстаўлены праз `StateFlow`.

### MainActivity

Кропка ўваходу з двума экранамі:

- **OnboardingScreen** (першы запуск): 7-крокавы майстар — Welcome, All Files Access, Battery Optimization, Accessibility Service, Notification Access, Car Setup, Done. Кожны крок тлумачыць, што не працуе без адпаведнага дазволу. Аўтаматычна пераходзіць далей, калі дазвол дадзены. Карыстальнік можа прапусціць любы крок.
- **ClientScreen** (наступныя запускі): картка статусу, кнопка start/stop, Install on Car, картка самаабнаўлення, кнопка Share Logs і астатні статус дазволаў для ўсяго, прапушчанага падчас onboarding.

### ConnectionService

Сэрвіс пярэдняга плана, які кіруе жыццёвым цыклам злучэння тэлефон-аўтамабіль з 3 спецыялізаванымі злучэннямі. Аўтаматычна запускаецца пры адкрыцці прыкладання тэлефона (напрыклад, праз USB ADB аўтамабіля).

- **Control connection** (порт 9637): NIO TCP-сервер на `0.0.0.0`, апрацоўвае handshake, heartbeat, каманды прыкладанняў, канал DATA
- **Video connection** (порт 9638): прымаецца пасля handshake, перадаецца VirtualDisplayClient для рэтрансляцыі відэа
- **Input connection** (порт 9639): прымаецца пасля handshake, слухач кадраў INPUT апрацоўваецца на `Dispatchers.IO` для пазбягання NetworkOnMainThreadException пры запісе дотыкаў на localhost
- `deployAssets()`: здабывае vd-server.jar на sdcard, app-server.apk у filesDir
- Выяўляе неадпаведнасць версій → адпраўляе `UPDATING_CAR` → аўтаматычна абнаўляе прыкладанне аўтамабіля праз dadb (WiFi ADB, dadb 1.2.10)
- Разумны сеткавы зваротны выклік: фільтруецца па `TRANSPORT_WIFI` — рэагуе толькі на змены WiFi, ігнаруе ваганні мабільных даных 3G/4G
- Перадае відэакадры (H.264 CONFIG + FRAME) ад VD да аўтамабіля праз відэа-злучэнне
- Маршрутызуе падзеі дотыку ад аўтамабіля (уваходнае злучэнне) да VD-сервера
- Адпраўляе спіс прыкладанняў з іконкамі 96x96 PNG да аўтамабіля праз control-злучэнне
- Апрацоўвае каманды LAUNCH_APP, GO_BACK, GO_HOME і перасылае іх VD-серверу
- Ставіць у чаргу запускі прыкладанняў, калі VD-сервер яшчэ не падключаны
- Рэгіструе сэрвіс mDNS для аўтаматычнага выяўлення аўтамабілем
- `FileLog.rotate()` пры старце сэрвісу — архівуе лог папярэдняй сесіі
- Кнопка "Install on Car": ручная ўстаноўка + аўтаматычная пры неадпаведнасці версій handshake

### VirtualDisplayClient

Прымае зваротнае злучэнне ад працэсу VD-сервера на `localhost:19637`. Прымае два параметры Connection: `videoConnection` і `controlConnection`.

- NIO ServerSocketChannel accept (неблакавальны) — VD-сервер падключаецца ДА тэлефона
- Чытае: `MSG_VIDEO_CONFIG`, `MSG_VIDEO_FRAME`, `MSG_STACK_EMPTY`, `MSG_DISPLAY_READY`
- Піша: `CMD_LAUNCH_APP`, `CMD_GO_BACK`, `CMD_GO_HOME`, `CMD_INPUT_TOUCH` (0x32)
- Відэакадры перадаюцца праз `videoConnection.sendVideo()` (ізалявана ад кантрольнага трафіку)
- Сігнал пустога стэку (`MSG_STACK_EMPTY`) перасылаецца аўтамабілю праз `controlConnection.sendControl()`
- Запісы дотыкаў на localhost сінхронныя з `FrameCodec.writeAll()` пад `writeLock`
- Пры адключэнні: аднаўляе фізічны дысплей (`cmd display power-on 0` + `KEYCODE_WAKEUP`) як сетку бяспекі, калі працэс VD-сервера забіты да ачысткі

### AdbBridge

Рэзервовы памочнік shell-каманд. Прадастаўляе `execShell()` і `execFast()` з выкарыстаннем `Runtime.exec()` для аперацый VD-сервера і кіравання харчаваннем дысплея, калі прамы API reflection не спрацоўвае.

### VirtualDisplayManager

Кіруе запускам прыкладанняў на фізічным дысплеі, калі VD не выкарыстоўваецца. Злучае з `InputInjectionService` для ін'екцыі ўводу на аснове жэстаў.

### VideoEncoder

MediaProjection + MediaCodec H.264 кадавальнік з выкарыстаннем віртуальнага дысплея `AUTO_MIRROR`. Альтэрнатыўны шлях кадавання (не выкарыстоўваецца ў асноўным струменевым канвееры, які праходзіць праз VD-сервер).

### FileLog

Файлавы логер, які абыходзіць фільтрацыю Android logcat (HyperOS фільтруе `Log.i/d` для не-сістэмных прыкладанняў).

- Піша ў `/sdcard/DiLinkAuto/client.log`
- `rotate()`: архівуе бягучы лог як `client-YYYYMMDD-HHmmss.log`, пачынае новы
- `zipLogs()`: стварае `dilinkauto-logs.zip` з усіх файлаў `.log` для абмену
- Захоўвае максімум 10 логаў (9 архіўных + бягучы)
- Патокава-бяспечны: ConcurrentLinkedQueue без блакаванняў, якая спусташаецца патокам запісу
- Таксама выклікае `android.util.Log.*` для стандартнага вываду logcat

### Multi-Touch Relay

Падзеі дотыку паступаюць ад аўтамабіля праз уваходнае злучэнне як CMD_INPUT_TOUCH (0x32) з неапрацаванымі данымі MotionEvent. `handleInputFrame` апрацоўваецца на `Dispatchers.IO` (не Main) для магчымасці запісу на localhost сокет. Тэлефон перадае падзеі DOWN/MOVE/UP з pointerId наўпрост VD-серверу, які апрацоўвае поўнае стварэнне MotionEvent з усімі актыўнымі паказальнікамі.

## Неабходныя дазволы

| Permission | Purpose |
|-----------|---------|
| MANAGE_EXTERNAL_STORAGE | All Files Access для разгортвання VD JAR на sdcard |
| Accessibility Service | Ін'екцыя дотыкаў на віртуальны дысплей праз dispatchGesture (без маніторынгу падзей) |
| Notification Access | Перасылка апавяшчэнняў на аўтамабіль (з прагрэсам) |
| API Shizuku | Павышаны shell-доступ для разгортвання VD-сервера без ADB і бясшумнага самаабнаўлення |
| QUERY_ALL_PACKAGES | Сетка лаўнчара прыкладанняў |
| REQUEST_INSTALL_PACKAGES | Аўтаабнаўленне прыкладання аўтамабіля праз dadb |

## Залежнасці

- Jetpack Compose + Material 3
- kotlinx-coroutines
- dadb 1.2.10 (WiFi ADB для аўтаабнаўлення аўтамабіля)
- Shizuku api/provider/aidl 13.1.5
- Protocol module (агульны з прыкладаннем аўтамабіля)
