# DiLink-Auto

*La version originale de ce document est le [README en anglais](../../README.md).*

Projetez les applications de votre téléphone sur l'écran intégré de votre voiture, avec une interaction tactile complète. Open source, aucun service Google requis.

Ceci est le **dépôt d'origine** de DiLink-Auto ([andersonlucasg3/dilink-auto-android](https://github.com/andersonlucasg3/dilink-auto-android)). En novembre 2026, il a réintégré les 252 commits du fork de longue date [ID-VerNe/dilink-auto-android](https://github.com/ID-VerNe/dilink-auto-android), qui a maintenu le projet en vie pendant que l'upstream était silencieux. Le développement continue en se concentrant sur les **ROM de téléphones chinois + les unités centrales BYD DiLink**, avec un réglage dédié pour le **DiLink 4.0 d'entrée de gamme** afin qu'il passe de « diaporama » à utilisable sur du matériel faible.

**Documentation / Documentação / 文档 / Документация / Documentation / Құжаттама / Документація / Hujjatlar**

GitHub n'affiche pas automatiquement la documentation dans votre langue. Choisissez-en une :

| Langue | README | Installation | Architecture | Client | Serveur | Protocole | Avancement |
|----------|--------|-------|--------------|--------|--------|----------|----------|
| English | **[README](../../README.md)** | [Installation](../setup.md) | [Architecture](../architecture.md) | [Client](../client.md) | [Serveur](../server.md) | [Protocole](../protocol.md) | [Avancement](../progress.md) |
| Português (BR) | [README](../pt-BR/README.md) | — | — | — | — | — | — |
| 简体中文 | [README](../zh-CN/README.md) | — | — | — | — | — | — |
| Français | [README](../fr/README.md) | — | — | — | — | — | — |
| Русский | [README](../ru/README.md) | — | — | — | — | — | — |
| Беларуская | [README](../be/README.md) | — | — | — | — | — | — |
| Қазақша | [README](../kk/README.md) | — | — | — | — | — | — |
| Українська | [README](../uk/README.md) | — | — | — | — | — | — |
| Oʻzbekcha | [README](../uz/README.md) | — | — | — | — | — | — |

Un tiret signifie que cette langue ne dispose actuellement que du README ; les guides détaillés sont encore uniquement en anglais et sont en cours de traduction.

Version actuelle : **0.19.0** (`versionCode 26111724`). Téléchargements : [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest).

Le versionnage suit le schéma semver de ce dépôt : `app.versionName` est `MAJOR.MINOR.PATCH` (les préversions ajoutent `-dev-N`) et `app.versionCode` est un entier monotone maintenu à la main. Les tags sont `v0.19.0` (version) et `v0.19.1-dev-3` (préversion).

Le problème qu'il résolvait à l'origine : les téléphones dotés de ROM chinoises (Xiaomi HyperOS, etc.) ne peuvent pas installer Android Auto, et l'unité centrale ne parle qu'Android Auto — rien ne comble le fossé. DiLink-Auto fonctionne avec n'importe quel téléphone Android 10+, avec ou sans services Google.

---

## Matériel testé

**BYD Qin PLUS DM-i 2023, finition Champion 55KM Leading.** Son unité centrale est le DiLink 4.0 d'entrée de gamme — Snapdragon 439 (8x Cortex-A53, uniquement des petits cœurs), Adreno 505, 4 Go de RAM, 16 Go d'eMMC, écran 1280x800, Wi-Fi 2,4 GHz uniquement, Android 9 / API 28, décodage matériel H.264 plafonné à 1080p. Tout le réglage des performances a été fait sur ce matériel.

---

## Ce qui a changé depuis la v0.18.0-dev

La version courte de l'évolution entre le point de fork (`84df5cb`, upstream/main) et le `main` actuel. Chaque élément correspond à de vrais commits, pas à des plans ; la chronologie détaillée se trouve dans [docs/progress.md](../progress.md).

### 1. Architecture de streaming VD directe

Le téléphone ne relaie plus la vidéo, le tactile ni l'audio. Le serveur VD se lie aux ports `9638` (vidéo), `9639` (entrée) et `9640` (audio, ouvert après une négociation bidirectionnelle) et parle directement au récepteur. Cela supprime 4 sockets + 2 changements de contexte de processus par frame, laissant 2 sockets + 0 changement de contexte. Le téléphone devient un pur orchestrateur : handshake et négociation de capacités, cycle de vie du VD (`VD_PORTS_BOUND`), et routage des logs de l'unité centrale. Le protocole a gagné `FrameCodec.readFrameBlocking()`, `MessageType.VD_PORTS_BOUND` et `AudioPayload`, et `VirtualDisplayClient` a été réduit au seul cycle de vie.

### 2. Réglage du DiLink 4.0 d'entrée de gamme (travail principal)

Face au matériel ci-dessus :

- **Taille d'encodage plafonnée à 1920x1080.** Le téléphone agrandissait auparavant le VD jusqu'aux pixels physiques (1080x2340 → encodage 2340x1658) pour éviter le rognage de l'IME ; cela dépassait la limite de décodage matériel du 439, retombait en décodage logiciel et le 8x A53 exécutait du H.264 au-dessus de 1080p à un nombre de fps à un chiffre. Corrigé en découplant la taille du VD de la taille d'encodage — le VD reste agrandi (ce qui conserve le correctif de l'IME), la taille d'encodage suit le viewport rapporté par la voiture et est plafonnée à 1920x1080.
- **Débit 8 Mbps → 4 Mbps.** Le débit soutenu mesuré dans un environnement voiture à 2,4 GHz était de 5-15 Mbps, donc 8 Mbps ne laissait aucune marge. Le repli adaptatif a aussi été resserré : plancher 1,5 Mbps, fenêtre de récupération de 2 s, pas de 0,5 Mbps (il oscillait auparavant entre 8 et 2 Mbps).
- **Fréquence d'images 30 → 24 → 20.** La valeur par défaut est désormais 20 fps (50 ms par image). Voir l'issue #71 : 2,5 h de navigation laissaient le téléphone chaud, et baisser la fréquence d'images est la deuxième soupape de décompression après le plafond d'agrandissement du VD.
- **TextureView → SurfaceView.** Évite la composition GL plein écran par image de l'Adreno 505 (environ 2-5 ms/image). Une barrière `outputSurfaceValid` empêche de rendre dans une surface détruite lorsque le décodeur est masqué/affiché au fil de la navigation.
- **Priorités de threads.** L'A53 n'a pas de gros cœurs, donc les indications d'ordonnancement comptent : les threads encodeur/décodeur/drain de socket utilisent `THREAD_PRIORITY_URGENT_DISPLAY`, `LifeWriter` utilise `THREAD_PRIORITY_BACKGROUND`.
- **Valeurs par défaut du logging.** Dans les builds release, `carLogEnabled` est désactivé par défaut (il était activé auparavant, et les frames de log rivalisaient avec la vidéo sur le lien 2,4 GHz) ; `ConcurrentLinkedQueue.size()` (O(n)) est devenu un `AtomicInteger`.
- **Nettoyage d'AppIconCache à la déconnexion.** ~80-150 PNG s'accumulaient indéfiniment sur l'eMMC de 16 Go (5-9 Mo de tas). Un nouveau `clear()` est appelé depuis `handleDisconnect`, et `prepareAll` décode en parallèle avec un `Semaphore(4)`, en recyclant les bitmaps intermédiaires.
- **`Debug.getPss()` supprimé.** Cet appel binder obsolète s'exécutait à chaque connexion, coûtant 100-500 ms sur A53 ; il n'est désormais collecté qu'en cas de crash.
- **`@Immutable AppTileData`.** `AppInfo` transporte un `ByteArray`, ce qui rendait `AppTile` non skippable et forçait une recomposition complète à chaque réaffectation de `appList`. Un wrapper immuable qui exclut `iconPng` permet à la grille d'éviter la recomposition.

### 3. Compatibilité avec les unités centrales API 28

Sur l'unité BYD réelle (`k65v1_64_bsp`, Android 9 / API 28), `MediaCodecInfo.isHardwareAccelerated()` (API 29+) lève `NoSuchMethodError` et tue le processus. Le sélecteur spéculatif de décodeur matériel a été supprimé et `createDecoderByType` rétabli (`REGULAR_CODECS` classe déjà les décodeurs matériels en premier ; sur BYD, il choisit `OMX.qcom.video.decoder.avc`). `minSdk` est passé de 24 à 26 pour `app-server` et `protocol`, et la barrière lint NewApi a été réactivée. `am display move-stack` (API 29+) est désormais conditionné à `SDK_INT` pour API 26-28 et journalise au lieu de traiter un échec de shell comme un succès.

### 4. UI et interaction

- **Refonte de la barre de navigation :** réduite à Éjecter (déconnexion) / Accueil / Retour. Le bouton de notification, la barre des récents, l'horloge et les informations réseau ont été supprimés, ainsi que du code mort sur quatre modules (côté voiture `NotificationScreen`, `RecentAppsState` ; côté téléphone `NotificationService` ; types de messages `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*`). **Le transfert de notifications n'existe pas dans cette base de code.**
- **Épinglage d'applications :** un appui long sur l'icône d'une application permet Épingler en haut / Désépingler, persisté dans SharedPreferences.
- **Liste d'autorisation d'applications :** un nouvel `AllowlistScreen` permet à l'utilisateur de choisir quelles applications sont poussées vers la voiture ; les applications non sélectionnées sont filtrées dans `sendAppList`, ce qui réduit la charge utile sur le réseau et le travail de décodage d'icônes de la voiture. Les applications de navigation courantes sont présélectionnées au premier lancement. Le basculement envoie `ACTION_ALLOWLIST_UPDATED` afin qu'un service en cours renvoie la liste et que la grille de la voiture se mette à jour en direct.
- **Prise en charge des applications en mode portrait :** le calcul dynamique du DPI (`VideoConfig.calculateOptimalDpi`) empêche les applications portrait (Amap, WeChat) d'être écrasées dans une bande fine à l'intérieur du VD paysage ; l'IME est restauré à la fin de la session (le téléphone conservait auparavant le clavier linkpc) ; index d'injection multi-touch corrigé.
- **Surcharge du DPI de démarrage côté voiture :** le handshake a gagné un champ `dpiOverride` (0 = automatique, [120, 480] = valeur utilisateur), piloté par le réglage `startup_dpi` de la voiture et envoyé sur les deux orientations. Corrige le DPI par défaut trop petit en 1280x800.
- **Tokens de couleur et harmonisation typographique :** les `Color.Gray` / `0xFF888888` éparpillés passent à `onSurfaceVariant`, les rouges d'erreur en double sont fusionnés, les libellés passent de 12sp à 14sp. « WiFi Direct Mode » renommé en « WiFi ADB Mode » ; le texte `adb tcpip` côté voiture a été supprimé.
- **Correctif de l'écran noir à la rotation :** `onCarViewportChanged` passe l'état à CONNECTING et redéploie le VD lors d'une rotation, mais la barrière de mise en page du streaming utilisait `isConnected` (faux pendant les ~2 s du redéploiement), donc `CarShell` retombait sur `CarLaunchScreen`, la `TextureView` sortait de la composition et la nouvelle surface ne pouvait pas redémarrer le décodeur. La barrière accepte désormais aussi `state == CONNECTING && appList.isNotEmpty()`, ce qui maintient la mise en page de streaming (et son overlay d'attente de vidéo) pendant la rotation.

### 5. Stabilité de session

Une session active n'est jamais tuée par une tentative de reconnexion — auparavant, une nouvelle tentative de passerelle Wi-Fi ou une connexion manuelle pouvait tuer une session en cours. La boucle de reconnexion s'arrête après 3 échecs ADB consécutifs. L'ADB TCP se reconnecte lorsque l'IP du téléphone change, et retombe automatiquement sur l'ADB TCP lorsque l'USB n'est pas disponible.

### 6. Stabilisation de PipelineServer

`VirtualDisplayServer` (1287 lignes, 9 threads, 2 files d'attente) est devenu `PipelineServer` (thread unique, 0 file d'attente) : horloge de frame → rendu GL → drain de l'encodeur → écriture TCP, avec un contrôle de flux naturel (une écriture TCP bloquée bloque le `eglSwapBuffers` suivant). Il corrige aussi : une texture GL créée dans un contexte EGL temporaire puis détruite (le thread du pipeline liait la texture 0) ; `app_process` tué lorsque le shell non interactif se terminait (corrigé avec `shellBackground`, qui garde le flux du shell attaché) ; et l'ordre de restauration du panneau physique (il appelait `input keyevent 224` après la fermeture du shell).

### 7. Interrupteur de log de diagnostic

Réglages → Débogage a gagné un interrupteur de log de diagnostic. Lorsqu'il est désactivé, il n'y a aucune écriture disque. L'état est propagé à la voiture via le message de données `LOG_TOGGLE` et, depuis l'issue #72, également synchronisé automatiquement après chaque handshake afin que le log de la voiture suive l'interrupteur du téléphone au lieu d'être silencieusement réinitialisé à la reconnexion. Valeurs par défaut : activé pour les builds debug/préversion, désactivé pour la release ; un choix explicite de l'utilisateur persiste.

### 8. Interface en chinois simplifié

Client et serveur embarquent `values-zh-rCN` / `values-zh`. Les chaînes d'application existent en anglais, portugais (BR), français, russe, biélorusse, kazakh, ukrainien, ouzbek et chinois.

### 9. Santé du code

Plusieurs cycles d'audits et de refactorings SRP/DRY : constantes partagées entre modules (`AppPrefs`, `AppTargets`, `VdDeploy`, `VdDeployArgs`, `WifiGatewayIp`, `AppIconLimits`) et collaborateurs extraits.

- **`protocol-core` / `protocol` :** `NioWriter` (écriture en spin sur le canal de socket avec timeout), `BackoffPolicy` (repli exponentiel paramétré), `DecodeLivenessTracker`, `ConnectionFactory`, `ConnectionKeepAlive`, `UsbAdbKeyStore`, `Ipv4Validator`, `AppIconLimits` ; code mort de `UsbAdbConnection` supprimé.
- **`vd-server` :** `AsyncSocketSender`, `AudioEncoderSelector`, `ProcessWatchdog`, `VideoEncoderSelector`, `PortAcceptor`, `ActivityDumpParser`, `VdDisplayHealer`.
- **`app-client` :** `ConnectionService` allégé ; `ShizukuVdDeployer`, `ShizukuNav`, `ClientNetworkMonitor`, `CarAppInstallHost`, `AllowlistRepository`, `SessionState` extraits ; `InputInjectionService` déprécié.
- **`app-server` :** `CarConnectionService` réduit à ~407 lignes ; la machine à états scindée en `CarSessionCoordinator` (machine à états multicanal 9637/9638/9639/9640 et re-handshake), `CarAdbTracker` (suivi ADB USB/TCP) et `CarAppListReceiver` (liste d'applications et limites de sécurité des icônes).
- **`app-desktop` :** consomme `AppIconLimits`, `DecodeLivenessTracker`, `BackoffPolicy`, aligné sur les dernières primitives du protocole.

### 10. Correctif de fuite du VD, nettoyage idempotent, auto-guérison de l'écran noir

**Cause racine :** chaque reconnexion fuyait un VirtualDisplay. L'ancien code utilisait `pkill -9`, qui court-circuite le hook d'arrêt de la JVM, donc `PipelineServer.cleanup()` ne s'exécutait jamais — le VD n'était pas libéré, le panneau physique restait éteint, et le réglage `screen_off_timeout` était perdu (le snapshot capturait la sentinelle `2147483647` que le code lui-même avait écrite). Après quelques reconnexions, l'écran de la voiture devenait noir.

**Correctif (12 fichiers) :** une sentinelle de demande d'arrêt (`/sdcard/DiLinkAuto/stop-request`) que le watchdog du moteur consomme afin qu'il sorte par le chemin normal et exécute le `cleanup()` complet (ART n'exécute pas les hooks d'arrêt sur un simple SIGTERM) ; une sonde basée sur `pgrep -f` (toybox rejette `pkill -0`) ; un repli `kill -9` qui localise le pid avec `pgrep -f` au lieu de `pkill -f` (ce dernier tue aussi le shell wrapper et rend le résultat ADB illisible) ; un `cleanupGuard` `AtomicBoolean` pour que les 7 sites d'appel n'exécutent pas `cleanupSession()` à répétition (une déconnexion déclenchait 9 appels « Force-waking physical display ») ; `waitForVdServerExit()` des deux côtés pour que deux moteurs ne se disputent jamais le même VD et les mêmes ports ; `PhoneDisplayRestorer` sur sa propre portée de durée de vie du processus avec `NonCancellable` ; un `cleanup()` réordonné ; la validation de valeur de `DisplayPowerController.restoreSetting()` ; et `BlackScreenDetector` (`BLACK_SCREEN_SUSTAIN_MS = 3000`) déplacé dans `protocol-core` pour que voiture et desktop le partagent, avec `rehandshakeForBlackScreen()` verrouillé à au plus une tentative par session.

L'automatisation de régression vit dans `scripts/` : `verify-blackscreen-fix.sh`, `e2e-reconnect-cleanup.sh`, `verify-reconnect-cleanup.sh` (32 vérifications pilotées via la vraie UI desktop ; dernière exécution complète 32/32, 2m48s).

### 11. Récepteur Windows `:app-desktop`

Outre l'unité centrale, le même protocole prend en charge un **programme de bureau Windows** comme récepteur : l'écran du téléphone se reflète dans une fenêtre PC et la souris devient du tactile. Le module vit dans `app-desktop/` (Kotlin/JVM + Compose Desktop 1.6.2, JDK 17) et réutilise `protocol-core` — aucun changement côté téléphone.

- **Transport :** trois connexions TCP directes vers le téléphone — 9637 (contrôle + données), 9638 (vidéo), 9639 (entrée) — comme la voiture, sans relais.
- **Répartition du déploiement :** `connectionMethod` dans la réponse de handshake décide qui déploie le serveur VD. Avec Shizuku, c'est le téléphone ; sinon le desktop déploie avec l'`adb.exe` local (`connect` → tuer l'ancien moteur **et attendre qu'il sorte réellement** → `exec app_process`), ce qui exige `"dev_mode": true` dans `config.json`.
- **Décodage :** décodage logiciel JavaCV/FFmpeg plus décodage matériel D3D11VA, avec repli automatique quand le matériel échoue au démarrage ou produit zéro frame.
- **UI :** trois pages de navigation — Miroir / Applications (lanceur) / Affichage (surcharge DPI, bascule de décodage matériel, « Appliquer et se reconnecter », garder éveillé, écran physique du téléphone).
- **Auto-guérison :** 10 s d'écran noir soutenu déclenchent une reconnexion automatique, au plus deux fois par processus.
- **Config et logs :** `%APPDATA%\DiLinkAuto` (`config.json` / `desktop.log`), surchargeables via `DILINK_DESKTOP_HOME` ; `DILINK_ADB` définit le chemin d'adb.

```bash
# Image d'application autonome (JRE embarqué) : copie build/compose/binaries/main/app/DiLinkAuto
./gradlew :app-desktop:createDistributable

# Sonde headless : valide seulement le handshake/VD/fps ; sans Shizuku, définissez DILINK_DEV_MODE=1 pour le déploiement ADB
app-desktop --probe
```

> Statut : le chemin Shizuku correspond au côté voiture ; le **chemin de déploiement ADB n'a pas encore été vérifié sur un appareil réel**. Le module ne fait pas partie des assets de release (sa dépendance JavaCV/FFmpeg est GPL, alors que ce projet est MIT).

### 12. Tests unitaires et durcissement de la sécurité

**Couverture de tests :** deux modules n'avaient aucune source de test (`vd-server` n'avait pas de `src/test`, celui de `protocol` était vide). À partir de `8157c86`, des tests unitaires JUnit4 ont été ajoutés sur les 6 modules, couvrant la logique non testée la plus risquée (framing du protocole, primitives cryptographiques ADB, arguments de déploiement du VD, débit adaptatif, barrière de quoting shell, barrières de version, classification du statut d'installation, format du rapport de crash). Un cycle supplémentaire de « coutures de testabilité » préservant le comportement a ajouté 44 cas de plus. Aujourd'hui le dépôt compte plus de 1 200 cas `@Test` sur 6 modules (JUnit4 + kotlinx-coroutines-test, sans Robolectric/Mockito/MockK ; les modules Android utilisent `isReturnDefaultValues=true` avec des fakes écrits à la main). L'inventaire, les invariants inter-modules verrouillés et les problèmes ouverts se trouvent dans [docs/IMPLEMENTATION_REPORT_TESTING.md](../IMPLEMENTATION_REPORT_TESTING.md).

**Durcissement des pairs (`a7d2b72`) :** chaque pair socket était auparavant considéré comme digne de confiance — une frame malformée de 6 octets pouvait tuer le processus du téléphone ou le serveur VD en UID shell. Les cinq décodeurs vérifient désormais avec `require()` avant les `getX()` bruts ; les coroutines de lecture attrapent `Throwable`, journalisent la cause et se déconnectent au lieu de mourir ; `MAX_PAYLOAD_SIZE` est passé de 128 Mo à 16 Mo et le buffer de `NioReader` a un plafond strict récupéré aux frontières de frame.

**Autorisation du canal VD (`8601fb1`) :** les ports 9638/9639 acceptaient auparavant n'importe quel hôte du même Wi-Fi, permettant à quiconque d'injecter des touches et d'exécuter `am start` / `pm uninstall` en shell. Ils n'acceptent désormais que les connexions du récepteur actuellement servi ; les pairs rejetés sont fermés et le serveur continue d'attendre.

**Quoting shell (`5eae3e7`) :** les lignes de commande `VdDeploy` passent par `shellQuote`, donc les chaînes contrôlées par un pair n'atteignent jamais `sh` nues.

**Desktop (`1f6836a`) :** `adbPort` / `jarPath` de la réponse de handshake sont validés avant usage (ils étaient auparavant passés directement au déploiement, c'est-à-dire une exécution de code arbitraire depuis n'importe quel pair) ; les processus enfants adb ne fuient plus.

**Session voiture (`bc2634c` / `35d7821`) :** les sessions utilisent un verrou de génération pour que `cleanupGuard` ne soit pas consommé trop tôt par le chemin de changement de réseau ; la récupération d'écran noir n'appelle plus `stop()` sur le décodeur (personne ne le redémarrait, donc le chemin de récupération garantissait un écran noir permanent).

**Chaîne d'approvisionnement de release :** le keystore de release était auparavant commité ; il n'est plus que référencé, avec les secrets de signature dans le CI (l'historique git doit encore être nettoyé). Le CI n'exécute plus de code de PR sur des runners self-hosted (`9517205`) et ne produit plus d'APK non signés / sur-strippés ni d'artefacts orphelins (`efde81f`). Tous les workflows tournent sur l'`ubuntu-latest` hébergé par GitHub.

---

## État actuel des fonctionnalités

**Fonctionnel :**

- Miroir vidéo H.264, 20 fps par défaut, CBR 4 Mbps, profil Main (réglé pour le DiLink 4.0 d'entrée de gamme ; fonctionne aussi sur les unités haut de gamme)
- Miroir audio : AAC-LC 48 kHz stéréo à 128 kbps sur une connexion TCP dédiée (9640), tampon de gigue de 150 ms, remplissage de silence constant à 46,875 fps, négociation bidirectionnelle de la capacité `FEATURE_AUDIO`, interrupteur audio persisté sur le client
- Entrée tactile complète (multi-touch, pincement pour zoomer)
- Lanceur d'applications : recherche, ordre alphabétique, icônes 64dp, menu sur appui long (désinstaller, infos application, épingler)
- Liste d'autorisation d'applications : seules les applications sélectionnées sont poussées vers la voiture
- Surcharge du DPI de démarrage côté voiture (corrige le DPI par défaut trop petit en 1280x800)
- Suivi d'orientation : le VD est reconstruit quand la voiture tourne, les applications portrait ne sont plus écrasées dans une bande
- Prise en charge de Shizuku : déployer le serveur VD sans ADB USB et mettre à jour l'app voiture toute seule avec un `pm install` silencieux
- Auto-mise à jour de l'app voiture : le téléphone compare le `versionCode` et met à jour l'app voiture via Wi-Fi ADB
- Écran du téléphone éteint pendant le streaming (économise la batterie) ; le panneau physique est restauré à la déconnexion
- Onboarding guidé des permissions
- Interface en chinois simplifié plus 8 autres langues d'interface
- Architecture VD directe : pas de relais sur le téléphone ; vidéo, tactile et audio passent directement
- Correctif de fuite du VD : arrêt gracieux par sentinelle (`cleanup()` complet, `kill -9` seulement en repli sur timeout), garde de nettoyage idempotent, attente de sortie du VD
- Auto-guérison de l'écran noir : la voiture reconstruit le VD (re-handshake) après 3 s d'écran noir soutenu ; le récepteur Windows utilise le même détecteur avec une fenêtre de 10 s
- Récepteur Windows (`app-desktop`) : fenêtre miroir, tactile à la souris, lanceur d'applications, panneau DPI/décodage matériel, déploiement adb, app-image `jpackage`

**Supprimé (par rapport à l'upstream d'avant la fusion) :**

- Transfert de notifications (notifications du téléphone → écran de la voiture) : bouton de notification de la barre de navigation, liste de notifications et messages de protocole `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` ont disparu
- Barre des récents, horloge, informations réseau

**Non implémenté :**

- Contrôles multimédias
- Widgets de navigation (le miroir audio est complet)

**Limitations connues :**

- Samsung Auto Blocker doit être désactivé pour que l'ADB USB fonctionne (Paramètres → Sécurité → Auto Blocker → désactivé).
- La gestion de la batterie Samsung nécessite une exemption explicite.
- Samsung Knox peut afficher une invite de sécurité la première fois que le display virtuel est accédé.
- Le hotspot du téléphone doit être activé manuellement (restriction Android 16).
- Des glitches visuels occasionnels dus à une course de redémarrage du décodeur ; la keyframe suivante (~1 s) rétablit l'image.
- La latence de streaming est d'environ 100-200 ms sous charge.
- Certaines applications ne remplissent pas l'écran (letterbox / portrait uniquement). DiLink-Auto reflète un display virtuel paysage, donc les applications qui ne prennent pas en charge le paysage affichent des barres noires — cela est décidé par l'application.
- Sur les unités API 26-28, si la réflexion `DisplayControl` échoue, le repli shell `cmd display power-reset` / `power-off` est aussi API 29+, donc le panneau physique peut ne pas revenir (il journalise au lieu d'échouer silencieusement).
- La progression de l'installation ne s'affiche pas dans les langues d'interface autres que l'anglais : `InstallStatus.parse` classe les chaînes de statut localisées par sous-chaînes anglaises, donc le chinois (et d'autres) tombent dans `IDLE`. Verrouillé par des tests, non corrigé ([C-6](../IMPLEMENTATION_REPORT_TESTING.md)).
- La première installation de l'app voiture pouvait être sautée avec un numéro de version extrême : `readInstalledVersion` renvoie `"0"` quand l'app voiture est absente, donc un `versionName` embarqué de `0` / `0.0` / `0.0.0` est comparé comme « à jour ». Le `versionName` actuel est `0.19.0`, donc cela ne se déclenche pas ([C-5](../IMPLEMENTATION_REPORT_TESTING.md)).

---

## Comment ça marche

1. **Activez le hotspot** — allumez le hotspot Wi-Fi du téléphone ; la voiture s'y connecte.
2. **Branchez** — connectez le téléphone au port USB de la voiture (ou rejoignez le même réseau Wi-Fi).
3. **Installation automatique** — le téléphone installe l'app voiture via Wi-Fi ADB (la première fois seulement, en un tap).
4. **Connexion automatique** — quatre flux TCP Wi-Fi : contrôle (9637), vidéo (9638), tactile (9639), audio (9640, ouvert après négociation).
5. **Utilisez vos applications** — lancez n'importe quelle application depuis le lanceur de la voiture ; elle s'exécute sur le téléphone, apparaît sur l'écran de la voiture et reçoit le tactile.

Le téléphone exécute les applications sur un display virtuel et les encode en H.264, qui est poussé vers la voiture. Les événements tactiles de l'écran de la voiture sont réinjectés dans le téléphone comme de vrais événements tactiles. L'écran physique du téléphone reste éteint pour économiser la batterie et reste utilisable indépendamment.

Architecture, flux de connexion et décisions de conception : [docs/architecture.md](../architecture.md).

---

## Prérequis

**Téléphone :**

- Android 10+ (`minSdk = 29`)
- Débogage USB activé (Options pour les développeurs)
- Permission d'accès à tous les fichiers (guidée au premier lancement)

**Voiture :**

- BYD DiLink 3.0+, vérifié sur la **BYD Qin PLUS DM-i 2023 Champion 55KM Leading** (DiLink 4.0 d'entrée de gamme, Snapdragon 439 / Android 9 / 1280x800)
- Android 8.0+ (`minSdk = 26`)
- Un port USB-A libre (ou le même réseau Wi-Fi)

**Le hotspot du téléphone doit être activé** — la voiture s'y connecte. Pas de codes d'appairage, pas de compte Google.

**Aucune connexion Internet n'est requise.** DiLink-Auto diffuse localement via le hotspot du téléphone ; le téléphone et la voiture dialoguent directement. Internet n'est nécessaire qu'aux applications qui tournent sur votre téléphone.

---

## Installation

Téléchargez l'APK depuis [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest), ou compilez-le vous-même :

1. **Compiler :** `./gradlew :app-client:assembleDebug`
2. **Installer sur le téléphone :** `app-client/build/outputs/apk/debug/app-client-debug.apk`
3. **Activer le débogage USB** (Paramètres → Options pour les développeurs)
4. **Ouvrir DiLink-Auto** et suivre l'onboarding d'accès à tous les fichiers
5. **Activer le hotspot et brancher sur la voiture** — l'app voiture est installée automatiquement au premier lancement via Wi-Fi ADB

L'APK de la voiture et le JAR du serveur VD sont tous deux empaquetés dans l'APK du téléphone, donc rien n'a besoin d'être installé séparément sur la voiture et installer l'app voiture ne nécessite pas Internet.

> **Note de mise à niveau de signature :** passer d'un build signé en debug à un build signé officiellement change le certificat — **désinstallez d'abord l'ancien paquet**, sinon l'installation échoue avec `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Règles complètes de release et de signature : [docs/release.md](../release.md).

---

## Structure du projet

L'APK du téléphone (`app-client`) embarque l'APK de la voiture (`app-server`) et le JAR du serveur VD (`vd-server`). Le récepteur est remplaçable : le même protocole tourne sur la voiture (`app-server`) ou sous Windows (`app-desktop`).

```
DiLink-Auto/
├── protocol-core/  Bibliothèque partagée pure-JVM (frames, messages, constantes, séquence de déploiement VD ; aucune dépendance Android)
├── protocol/       Adaptation plateforme (ADB, hooks Android, connexions USB/TCP)
├── app-client/     APK du téléphone — orchestration, déploiement du VD, auto-mise à jour de la voiture, liste d'autorisation d'applications
├── app-server/     APK de la voiture — UI, machine à états de connexion, décodeur vidéo
├── app-desktop/    Récepteur Windows — fenêtre miroir, décodage FFmpeg, déploiement adb, jpackage
├── vd-server/      Serveur VirtualDisplay (compilé en JAR, déployé par le téléphone)
├── scripts/        Automatisation (vérification de logs, reconnexion/nettoyage de bout en bout)
├── docs/           Documentation (point d'entrée multilingue)
└── gradle/         Système de build
```

| Module | Rôle | minSdk |
|--------|------|--------|
| `protocol-core` | Bibliothèque de protocole partagée pure-JVM (Connection/NioWriter/NioReader/FrameCodec/Messages/AudioPayload/VdDeploy/BackoffPolicy/DecodeLivenessTracker/BlackScreenDetector) | — |
| `protocol` | Bibliothèque de protocole partagée (UsbAdbConnection, AdbProtocol, VideoConfig, ConnectionFactory, ConnectionKeepAlive, UsbAdbKeyStore) | 26 |
| `app-client` | Application du téléphone (ConnectionService, ShizukuVdDeployer, ClientNetworkMonitor, CarAppInstallHost, AllowlistRepository, FileLog) | 29 |
| `app-server` | Application de la voiture (CarSessionCoordinator, CarAdbTracker, CarAppListReceiver, AudioPlayer, VideoDecoder, CarShell, HomeScreen, PersistentNavBar) | 26 |
| `app-desktop` | Récepteur Windows (DesktopApp, DesktopConnectionService, VideoDecodePipeline, SwingVideoView) | — |
| `vd-server` | Processus en UID shell (PipelineServer, AudioPipeline, AudioEncoderSelector, PortAcceptor, TouchInjector, GlPipeline, DisplayPowerController) | — |

---

## Build

```bash
# Compile l'APK du téléphone (exécute buildVdServer + embedServerApk, en empaquetant vd-server.jar et app-server.apk dans les assets du client)
./gradlew :app-client:assembleDebug

# Emplacement de l'APK :
# app-client/build/outputs/apk/debug/app-client-debug.apk  (téléphone, embarque l'APK de la voiture)

# Tests unitaires (6 modules, JUnit4 + kotlinx-coroutines-test, sans Robolectric/Mockito/MockK)
./gradlew :protocol-core:test :protocol:test :vd-server:test :app-desktop:test
./gradlew :app-client:testDebugUnitTest :app-server:testDebugUnitTest
```

Nécessite JDK 17 et le SDK Android (compileSdk 34). Les builds release nécessitent `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_PASSWORD` (`RELEASE_KEYSTORE_FILE` / `RELEASE_KEY_ALIAS` en option) ; une variable requise manquante échoue à la configuration au lieu de produire silencieusement un APK non signé qui ne pourrait pas être installé par-dessus l'APK officiel. Le keystore lui-même n'est jamais commité.

Sur Windows, les commandes équivalentes utilisent `.\gradlew.bat`.

---

## Documentation

La documentation complète (installation, architecture, protocole, client, serveur, avancement) commence à [docs/README.md](../README.md). Les normes de processus se trouvent dans [AGENTS.md](../../AGENTS.md), [docs/ISSUE_GUIDE.md](../ISSUE_GUIDE.md), [docs/PR_GUIDE.md](../PR_GUIDE.md) et [docs/release.md](../release.md).

---

## Contribuer

Les pull requests sont bienvenues. Le développement se fait sur `main` et `develop` (git-flow : les fonctionnalités partent de `develop`, les releases sont fusionnées dans `main` puis rétro-fusionnées). Avant d'ouvrir une PR, assurez-vous que `./gradlew :app-client:assembleDebug` et les commandes de tests unitaires ci-dessus passent.

---

## Licence

MIT, voir [LICENSE](../../LICENSE). Créé à l'origine par [andersonlucasg3](https://github.com/andersonlucasg3) ; merci à l'auteur original et à tous ceux qui ont maintenu le projet en vie dans le fork.
