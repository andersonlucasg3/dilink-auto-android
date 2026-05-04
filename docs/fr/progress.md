# Suivi de progression

Version actuelle : **v0.17.0** (stable)
Dernière mise à jour : 2026-05-02

## Jalons

### v0.17.0 (2026-05-02)

- **Redémarrage du décodeur éliminé** : `MediaCodec.setOutputSurface()` remplace `stop()`+`start()`. MirrorContent persiste dans l'arbre Compose avec `View.INVISIBLE` — zéro perte d'images lors de la navigation HOME↔APP↔NOTIFICATIONS.
- **30fps fixe** : Réduit de 60fps à 30fps. Le téléphone ne surchauffe plus. Charge CPU/GPU réduite de moitié. Trafic WiFi réduit de ~40%.
- **Framerate adaptatif supprimé** : Le mode 15fps causait des chutes d'images lors du changement d'application. Retour au 30fps fixe.
- **Documentation** : Tous les changements v0.17.0 documentés avec traduction complète en 8 langues.

### v0.17.0-dev-02 (2026-05-01)

- **Correction surchauffe du téléphone** : Élimination des patterns d'attente active CPU dans le pipeline de streaming qui causaient la surchauffe du téléphone. Remplacement des busy-waits `delay(1)` par des mécanismes blocking/selector appropriés.
- **AppIconCache déplacé côté voiture** : Le cache d'icônes côté voiture persiste les PNG sources (192x192) sur le disque. `prepareAll()` décode+redimensionne toutes les icônes sur un thread d'arrière-plan avant l'apparition de la grille ; `getPrepared()` est une recherche O(1) ConcurrentHashMap sans I/O lors du défilement. Élimination du décodage par tuile et du crash lors du défilement rapide.
- **AppTile simplifié** : Suppression de la collecte StateFlow par tuile, du DropdownMenu lazy et des effets click ripple. Tuiles légères avec clickable au lieu de combinedClickable pour le tap principal.
- **Déduplication de la grille d'apps** : Correction du crash LazyGrid par déduplication des éléments par packageName.

### v0.17.0-dev-01 (2026-04-30)

- **Fermeture individuelle des notifications et Tout effacer** : L'écran de notifications voiture a maintenant des boutons de fermeture par élément avec animations slide-out et un bouton "Tout effacer" dans l'en-tête. Nouveaux messages protocolaires : `NOTIFICATION_CLEAR` (0x04) et `NOTIFICATION_CLEAR_ALL` (0x05) sur le canal data. Icônes par élément depuis le payload `iconPng` du téléphone.
- **Actions contextuelles des applications** : Appui long sur les tuiles d'apps (lanceur) et apps récentes de la barre de nav affiche un menu déroulant avec Désinstaller et Infos application. Propagation de désinstallation via `APP_UNINSTALL` (0x1B) / `APP_UNINSTALLED` (0x06). Infos application affiche une boîte de dialogue côté voiture avec les métadonnées `APP_INFO_DATA` (0x07) du téléphone. Les actions du menu contextuel passent par le serveur VD pour un accès niveau shell.
- **Infrastructure de raccourcis d'applications** (désactivée dans l'UI) : Messages protocolaires `APP_SHORTCUTS` (0x18) / `APP_SHORTCUTS_LIST` (0x19) / `APP_SHORTCUT_ACTION` (0x1A) avec requête serveur VD + fallback APK XML. Désactivée en attendant le raffinement de la résolution des libellés (issue #57).
- **Correction du bouton retour** : GO_BACK ferme maintenant les activités une par une avant de revenir au menu d'accueil, utilisant un suivi de pile approprié et les messages `FOCUSED_APP` (0x16).
- **DPI Samsung DeX / Mode Bureau** (annulé) : L'implémentation initiale utilisant la détection `UiModeManager.currentModeType` et 213dpi a été annulée dans dev-02. Remplacée par une approche de suppression de flag au niveau VD.

### v0.16.0 (2026-04-29)

- **Shizuku** : L'app apparaît maintenant dans la liste des apps autorisées Shizuku (ajout de ShizukuProvider ContentProvider). La carte paramètres ouvre directement l'app Shizuku pour la gestion des permissions.
- **Correction Shizuku exec** : Correction du EBADF des ParcelFileDescriptors dans les transactions binder en dupliquant les FDs avant lecture. `pm install` via Shizuku pour l'auto-mise à jour silencieuse.
- **Mode Shizuku sur la voiture** : La connexion voiture ne reste plus bloquée sur "En attente du WiFi" quand Shizuku est actif — la boucle de nouvelle tentative IP passerelle ne s'arrête plus après la première tentative.
- **Vérification de version passée à versionName** : Les mises à jour de l'app voiture comparent maintenant les chaînes versionName (compatible semver) au lieu des entiers versionCode, permettant les mises à jour pré-release de la voiture.
- **Renforcement de la sécurité** : Suppression des permissions inutilisées `RECORD_AUDIO` et `SYSTEM_ALERT_WINDOW`. Le service d'accessibilité n'écoute plus les événements `typeAllMask` (utilise uniquement `dispatchGesture`).
- **Performance de la grille d'apps** : Correction du crash lors du défilement rapide sur l'écran voiture. `GridCells.Adaptive` → `GridCells.Fixed` avec colonnes calculées. Décodage bitmap lazy par tuile avec `inSampleSize=2` + `RGB_565`.
- **Stabilité réseau** : `NetworkCallback` côté téléphone maintenant filtré sur `TRANSPORT_WIFI` uniquement, ignorant les fluctuations de données mobiles 3G/4G.

### v0.15.0 (2026-04-28)

- **Démarrage automatique du service téléphone** : `ConnectionService` démarre automatiquement quand l'application téléphone est ouverte (par ex., via USB ADB de la voiture), supprimant le besoin d'appuyer manuellement sur Démarrer. ✅ Terminé
- **La voiture ne supprime plus la tâche du téléphone** : Suppression de `--activity-clear-task` du lancement USB ADB de la voiture. Si l'application téléphone est déjà ouverte, la voiture continue sans la perturber. ✅ Terminé
- **Bouton Partager les logs** : Le bouton "Partager les logs" sur l'écran principal compresse tous les fichiers `*.log` de `/sdcard/DiLinkAuto/` et partage via la feuille de partage Android. `FileLog.zipLogs()` crée un `dilinkauto-logs.zip`. ✅ Terminé
- **Configuration de l'encodeur** : Ajusté à 8Mbps CBR profil Main pour une compatibilité élargie. Ajout de la contre-pression (abandonne les non-keyframes quand la file d'écriture dépasse 6 trames). ✅ Terminé
- **Catchup VideoDecoder** : Quatre zones de vitesse graduées (normale, douce 1.5x, moyenne 2x, agressive 3x) pour une récupération de latence plus fluide. ✅ Terminé
- **Traduction française** : Ajout du français (fr) aux 7 langues existantes (maintenant 8 au total). ✅ Terminé
- **Vérification de mise à jour à l'ouverture** : La vérification d'auto-mise à jour s'exécute immédiatement à l'ouverture de l'application, avec notification de mise à jour et bouton de re-vérification. ✅ Terminé
- **Sélecteur de canal de distribution** : Carte de paramètres pour choisir entre les versions stables et les pré-versions de développement pour l'auto-mise à jour. ✅ Terminé
- **Refonte de CarLaunchScreen** : Disposition à deux colonnes optimisée pour les écrans larges de voiture. ✅ Terminé
- **Refactorisation UI application téléphone** : Réorganisation de l'écran principal, correction des bugs du flux d'installation. ✅ Terminé
- **Améliorations de l'intégration** : Prérequis de configuration voiture, UI de progression d'installation améliorée, carte Comment connecter améliorée. ✅ Terminé
- **Séparation UI voiture en deux modes** : Écran de lancement (plein écran, centré connexion) et mode streaming (barre nav + contenu). Transition fluide quand la liste d'apps arrive. ✅ Terminé
- **Corrections des artefacts vidéo** : Abandons intelligents du décodeur + catchup gradué + contre-pression encodeur éliminent les artefacts visuels. ✅ Terminé
- **Corrections entrée tactile** : Mappage correct des coordonnées au DPI fixe 480 du serveur VD, distribution tactile incrémentale sur MOVE, corrections geste tap et IP manuelle. ✅ Terminé
- **Restauration d'écran et stabilité réseau** : Restauration de l'écran après déconnexion USB, améliorations du callback réseau. ✅ Terminé
- **Internationalisation** : Toutes les nouvelles chaînes UI traduites en 8 langues (en, pt-BR, ru, be, fr, kk, uk, uz). ✅ Terminé
- **Automatisation CI/CD** : 6 workflows dédiés — validation (`build.yml`, `build-develop.yml`), pré-release sur tags `-dev` (`build-pre-release.yml`), release sur tags `vX.Y.Z` (`build-release.yml`), rétro-synchronisation main→develop (`sync-main-to-develop.yml`), et issue-agent autonome (`issue-agent.yml`). ✅ Terminé

### v0.14.0

- **Source de version partagée** : Version code/name maintenant dans gradle.properties — modification unique pour les deux applications.
- **MAX_PAYLOAD_SIZE 2Mo → 128Mo** : La liste d'apps avec plus de 136 icônes PNG dépassait 2Mo, causant ProtocolException et pertes de connexion.
- **Correction restauration écran** : `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` avec `ACQUIRE_CAUSES_WAKEUP` restaure l'écran après déconnexion USB, même quand le serveur VD meurt sans nettoyage.
- **Compatibilité POCO F5** : `FLAG_KEEP_SCREEN_ON` empêche le verrouillage d'écran pendant le streaming. Entrée tactile confirmée fonctionnelle sur POCO F5 avec Xiaomi 17 Pro Max.
- **Journalisation tactile côté voiture** : Événements tactiles MirrorScreen et succès sendTouchEvent journalisés pour débogage.
- **Crédit développeur dans À propos** : "Développé avec ❤" avec lien GitHub dans les 7 langues.

### v0.13.1 — Première version (2026-04-26)

- **Flux d'intégration** : Configuration guidée des permissions au premier lancement (Tous les fichiers, Batterie, Accessibilité, Notifications). Détection automatique des autorisations, solution de repli par sondage pour les paramètres de type dialogue.
- **Auto-mise à jour (UpdateManager)** : Vérifie l'API GitHub Releases, télécharge l'APK avec progression, installe via l'installateur de paquet système. Délai de 6 heures.
- **Réorganisation de la vue principale** : Écran principal axé sur l'usage quotidien (guide de connexion, statut, démarrer/arrêter, mises à jour). Écran paramètres avec permissions, installation voiture, à propos et liens de don.
- **Installation USB + WiFi sur la voiture** : Scanner de sous-réseau parallèle sonde les 254 IP pour l'ADB de la voiture. Combiné avec découverte ARP/neighbor/gateway. Hôte USB tenté mais l'USB-A de la voiture est hôte uniquement.
- **Serveur VD maintenant module Gradle Kotlin** : Dépend de :protocol et kotlinx-coroutines. Partage NioReader, FrameCodec.writeAll.
- **Performance** : Élimination de l'allocation ByteArray intermédiaire par trame dans l'encodeur. Capacité initiale NioReader 256Ko. isKeyFrame mis en cache dans FrameData.
- **Encodeur** : CBR 8Mbps, profil Main, FPS configurable (défaut 30, voiture demande 60), PRIORITY 0 (temps réel). I_FRAME_INTERVAL=1s. `repeat-previous-frame-after`=500ms pour contenu statique.
- **Dons** : Badges GitHub Sponsors et Pix (Brésil) dans README et paramètres de l'application.
- **Icône vectorielle adaptative** : Silhouette de voiture avec signaux sans fil, appliquée aux deux applications.
- **Internationalisation** : Ressources de chaînes en anglais, portugais (pt-BR), russe (ru), biélorusse (be), français (fr), kazakh (kk), ukrainien (uk) et ouzbek (uz).
- **Signature de release** : Keystore fixe avec mot de passe fort. Les builds CI signent les APK de release via GitHub Secrets.

### v0.13.0 — Correction Auth USB ADB (2026-04-25)

Cause racine trouvée et corrigée : `Signature.getInstance("SHA1withRSA")` double-hachait le AUTH_TOKEN ADB. Le token de 20 octets d'ADB est une valeur pré-hachée — `RSA_sign(NID_sha1)` d'AOSP le traite comme déjà haché. Utilise maintenant `NONEwithRSA` avec préfixe ASN.1 SHA-1 DigestInfo ajouté manuellement (signature pré-hachée). "Toujours autoriser" persiste maintenant correctement — AUTH_SIGNATURE accepté à la reconnexion sans dialogue.

### v0.13.0 — Alimentation écran + Encodage clé (2026-04-25)

- **Alimentation écran via SurfaceControl (Android 14+)** : Charge `DisplayControl` depuis `/system/framework/services.jar` via `ClassLoaderFactory.createClassLoader()` + librairie native `android_servers`. Repli sur `cmd display power-off/on` si la réflexion échoue.
- **Restauration écran à la déconnexion** : `VirtualDisplayClient.disconnect()` du téléphone exécute `cmd display power-on 0` + `KEYCODE_WAKEUP` comme filet de sécurité quand le processus serveur VD est tué avant nettoyage.
- **Réécriture encodage clé ADB** : Réécriture de `encodePublicKey()` correspondant exactement à la référence AOSP — constantes corrigées, `bigIntToLEPadded()` explicite, journalisation d'en-tête de structure.
- **Catchup décodeur** : Quand la file dépasse `100ms * TARGET_FPS / 1000` trames (6 à 60fps), saute une non-keyframe sur deux. L'image bouge toujours à vitesse 2x, rattrape progressivement sans saut.
- **Tampon de logs voiture** : 200 → 10 000 messages. Les logs d'auth USB ADB survivent maintenant jusqu'à ce que la connexion de contrôle les vide.

### v0.12.5 — Stabilité de connexion (2026-04-24)

- **Callback réseau intelligent** : `onLost` vérifie maintenant si le réseau perdu est celui qui porte la connexion. Ignore les pertes non liées (cycle de données mobiles). Auparavant, toute perte réseau tuait la session de streaming.
- **Diagnostics auth USB ADB** : Journalisation complète du flux d'authentification acheminée via carLogSend → FileLog téléphone. A révélé que AUTH_SIGNATURE est rejetée à chaque fois (l'adbd du téléphone ne reconnaît pas la clé stockée). En cours d'investigation.
- **Journalisation aperçu clé AUTH_RSAPUBLICKEY** : Log les premiers/derniers octets de la clé publique envoyée au téléphone pour comparaison avec le format ADB standard.

### v0.12.0–v0.12.4 — Corrections de bugs & finitions (2026-04-24)

- **Entrée tactile corrigée** : `handleInputFrame` distribué sur `Dispatchers.IO` (était Main, causait `NetworkOnMainThreadException` sur écriture socket localhost)
- **Lecteur commandes NIO serveur VD** : Correction boucle infinie — `break` dans switch ne cassait que le switch, pas la boucle d'analyse. Utilise maintenant `break parseLoop;` break étiqueté.
- **Dédoublonnage lancement app** : Suppression de `--activity-clear-task` de `am start`. Les apps existantes reprennent au lieu de redémarrer.
- **Bitrate** : Défini à 8Mbps CBR (ajusté de 12Mbps dans les versions ultérieures pour compatibilité).
- **FPS configurable** : Ajout du champ `targetFps` à HandshakeRequest. La voiture demande 60fps. Le serveur VD accepte FPS comme argument ligne de commande, l'utilise pour `KEY_FRAME_RATE` et `FRAME_INTERVAL_MS`.
- **Barre nav** : 72dp → 76dp, icônes 32dp → 40dp, hauteur ligne 52dp → 60dp, texte 12sp → 14sp.
- **Icônes app lanceur** : 40dp → 64dp, cellules grille 140dp → 160dp, texte bodyMedium → bodyLarge.
- **Clavier barre de recherche** : `windowSoftInputMode="adjustNothing"` + `imePadding()` sur TextField. Le clavier ne pousse pas l'activité, seule la barre de recherche bouge.
- **Notifications** : Dédoublonnage par ID (mises à jour de progression remplacent l'existant), support barre de progression (déterminée + indéterminée), appui pour lancer l'app propriétaire sur VD + basculer en vue miroir.
- **Apps récentes** : `pruneUnavailable()` supprime les apps qui ne sont plus sur le téléphone quand la liste d'apps est mise à jour.
- **Stockage clé USB ADB** : Ordre de priorité : `/sdcard/DiLinkAuto/` → `getExternalFilesDir` → `getFilesDir`. La migration recherche tous les emplacements.
- **Flux de mise à jour** : Le téléphone envoie le message `UPDATING_CAR`. La voiture affiche "Mise à jour de l'application voiture..." et ne reconnecte pas.
- **Correction crash flux mise à jour** : La voiture saute la connexion vidéo/entrée quand le flag `updatingFromPhone` est activé.
- **logSink VideoDecoder/UsbAdbConnection** : Les logs côté voiture acheminés via le protocole vers le FileLog du téléphone.

### v0.11.0–v0.11.3 — Pipeline non-bloquant + Correction encodeur (2026-04-24)

- **VideoConfig** : Constantes partagées `TARGET_FPS` et `FRAME_INTERVAL_MS`. Toutes les attentes/sondages du chemin vidéo plafonnés à l'intervalle de trame.
- **SurfaceScaler redessin périodique** : Appelle toujours `glDrawArrays + eglSwapBuffers` à chaque intervalle de trame, même sans nouvelle trame du VD. Appelle `updateTexImage` seulement quand une nouvelle trame est disponible. Alimente l'encodeur sur contenu statique.
- **Serveur VD NIO** : Remplacement de `DataOutputStream/DataInputStream` bloquants par file d'écriture NIO (`ConcurrentLinkedQueue<ByteBuffer>`) + lecteur de commandes basé Selector. Aucune I/O bloquante dans le pipeline.
- **Sondage encodeur** : Timeout `dequeueOutputBuffer` réduit de 100ms à `FRAME_INTERVAL_MS` (16ms à 60fps).
- **Sondage file VideoDecoder** : 100ms → `FRAME_INTERVAL_MS`.
- **Timeout select NioReader** : 100ms → `FRAME_INTERVAL_MS` (configurable via paramètre constructeur).
- **Park writer Connection** : 50ms → `FRAME_INTERVAL_MS`.
- **Boucle accept VirtualDisplayClient** : 100ms → `FRAME_INTERVAL_MS`.
- **Démarrage anticipé VideoDecoder** : Démarre sur SurfaceTexture hors écran quand la première trame CONFIG arrive (avant MirrorScreen). MirrorScreen redémarre le décodeur avec la vraie surface TextureView.
- **File VideoDecoder** : 3 → 30 trames. Trames mises en file même avant l'appel de `start()`.
- **FileLog** : Journalisation fichier (`/sdcard/DiLinkAuto/client.log`) contourne le filtrage logcat HyperOS. Rotation : archive en `client-YYYYMMDD-HHmmss.log`, garde 10 max.

### v0.10.0 — Architecture 3 connexions (2026-04-24)

Division de la connexion TCP multiplexée unique en 3 connexions dédiées pour éliminer les interférences inter-canaux causant des blocages vidéo :
- **Connexion contrôle** (port 9637) : handshake, heartbeat, commandes d'app, canal DATA
- **Connexion vidéo** (port 9638) : H.264 CONFIG + FRAME uniquement (téléphone → voiture)
- **Connexion entrée** (port 9639) : événements tactiles uniquement (voiture → téléphone)

Chaque connexion a sa propre instance `Connection` avec SocketChannel, NioReader et file d'écriture indépendants. Heartbeat/watchdog sur contrôle uniquement.

### v0.9.2 — Build diagnostique (2026-04-23)

Journalisation complète pour investigation du blocage de trame vidéo après ~420 trames :
- **Boucle relais vidéo** : logs avant/après readByte, taille charge utile, msgTypes inconnus
- **NioReader** : logs quand channel.read() retourne 0 (chaque 100ème occurrence avec état tampon)
- **Writer Connection** : logs toutes les 60 trames vidéo (compte, taille, profondeur file, blocages), logs blocages écriture
- **Correction blocage writer** : `Thread.yield()` → `delay(1)` dans writeBuffersToChannel — libère le thread IO vers le pool de coroutines au lieu de l'attente active (découverte d'investigation : Thread.yield affamait la coroutine de relais vidéo)
- **Écouteurs de trame** : gestionnaires de trames non-vidéo distribués async (`scope.launch`) pour que le traitement lourd (décodage liste apps) ne bloque pas le lecteur de drainer TCP

### v0.9.0-v0.9.1 — Investigation blocage écriture (2026-04-23)

Investigation de la cause racine du blocage vidéo. Ajout journalisation taille tampon TCP, diagnostics blocage écriture.
- Confirmé gel tampon d'envoi TCP à 108 916 octets restants pendant l'envoi de liste d'apps
- Confirmé que les trames vidéo elles-mêmes n'ont aucun blocage d'écriture (file=0 pendant vidéo)
- Confirmé que la clé USB ADB est stable (LOADED fp=c4e88a05) — l'authentification répétée est un comportement HyperOS

### v0.8.4-v0.8.8 — Corrections bugs + Routage logs (2026-04-23)

- **Routage logs voiture** : Tous les appels `Log.*` côté voiture acheminés via `carLogSend()` qui envoie via canal DATA `CAR_LOG` au téléphone. Le téléphone log avec le tag `CarLog` dans logcat. Tampon jusqu'à 200 messages avant établissement connexion.
- **Lancement serveur VD rétabli** à `shellNoWait` + `exec app_process` (approche v0.6.2). Le détachement `setsid`/`nohup` cassait la connectivité localhost. Le serveur VD meurt à la déconnexion USB mais récupère au rebranchement.
- **VD ServerSocket** : `startListening()` ouvre de façon synchrone, `waitForVDServer()` saute si déjà en attente
- **Persistance clé USB ADB** : `getExternalFilesDir` avec vérification d'écriture + migration depuis `getFilesDir` + journalisation empreinte
- **ClosedSelectorException** : vérifications `selector.isOpen` dans writer et NioReader
- **Correction récursion infinie** : remplacement bulk Log→carLogSend a accidentellement touché carLogSend lui-même

### v0.8.3 — Finition finale + Correction attente VD + Diagnostic clé USB (2026-04-23)

Jalon final + corrections bugs :
- **Garde attente VD** : `waitForVDServer` saute si déjà en attente — empêche fermeture/réouverture de ServerSocket quand la voiture envoie plusieurs handshakes pendant la reconnexion (cause racine du serveur VD incapable de se connecter, confirmé via logcat téléphone montrant 4x `startListening` en 45s)
- **Diagnostic clé USB** : La voiture écrit les infos de clé (`LOADED`/`GENERATED` + empreinte + chemin) dans `/data/local/tmp/car-adb-key.log` sur le téléphone après connexion USB ADB. Permet de diagnostiquer si l'auth se répète parce que la clé change ou que le téléphone ne persiste pas "Toujours autoriser".
- **M3** : Multi-touch groupé — nouveau type de message `TOUCH_MOVE_BATCH` (0x04) transporte tous les pointeurs dans une trame. Événements MOVE groupés côté voiture, dégroupés côté téléphone. Réduit les appels système de N*60/sec à 60/sec pour les gestes à N doigts.
- **M10** : État éjection persisté dans SharedPreferences — survit au kill/redémarrage de l'app voiture. Effacé au rebranchement USB ou ACTION_START.
- **L4** : NioReader utilise heap ByteBuffer au lieu de direct — nettoyage GC déterministe.

### v0.8.2 — Finition + VD ServerSocket + Persistance clé USB (2026-04-23)

6 corrections de finition + 2 corrections critiques :
- **Correctif urgent** : VirtualDisplayClient divisé en `startListening()` (bind synchrone) + `acceptConnection()` (attente asynchrone). ServerSocket ouvert AVANT réponse handshake — corrige le serveur VD incapable de se connecter sur localhost:19637.
- **Persistance clé USB** : Stockage clé utilise `getExternalFilesDir` avec vérification d'écriture + migration depuis `getFilesDir`. Journalisation empreinte à chaque connexion pour diagnostiquer si la clé change entre connexions.
- **M12** : `checkStackEmpty` utilise `grep -E` plus simple au lieu du parsing fragile par section `sed`
- **L2** : Tampons socket localhost serveur VD définis à 256Ko
- **L3** : Décodeur utilise `System.nanoTime()/1000` pour les horodatages (était incrément fixe 33ms)
- **L5** : `UsbAdbConnection.readFile()` utilise boucle de lecture + try-with-resources
- **L6** : SurfaceScaler HandlerThread correctement quitté à l'arrêt
- **L7** : Icônes d'app augmentées de 48x48 à 96x96px

### v0.8.1 — Tactile + Performance décodeur + Correctifs urgents (2026-04-23)

4 optimisations de performance + 2 corrections crash :
- **M2** : `checkStackEmpty()` exécuté sur thread d'arrière-plan — lecteur de commandes n'est plus bloqué 300ms+ après appui Retour
- **M4** : Pools `PointerProperties[10]` + `PointerCoords[10]` pré-alloués dans serveur VD — élimine la pression GC par événement tactile
- **M5** : File de trames décodeur réduite de 6 à 3 (borne latence 200ms → 100ms)
- **M6** : `cmd display power-off 0` exécuté sur thread fire-and-forget — suppression de la gigue du chemin d'injection tactile
- **Correction crash** : `ClosedSelectorException` dans writer Connection + NioReader — ajout vérifications `selector.isOpen` et bloc catch. Race : `disconnect()` ferme les selectors pendant que les coroutines reader/writer sont encore en exécution.
- **Correction bug** : `usbConnecting` réinitialisé dans `startConnection()` maintenant aussi gardé par `usbAdb == null` (deuxième emplacement de race auth USB, le premier corrigé dans v0.7.3)

### v0.8.0 — Performance pipeline I/O (2026-04-23)

3 optimisations performance I/O + correctif urgent :
- **H2** : Écritures groupées — `channel.write(ByteBuffer[])` fusionne en-tête+charge utile en un seul appel système/segment TCP
- **H3** : Relais vidéo alloue `ByteArray(size)` de taille exacte directement — supprime `relayBuf` + `copyOf` intermédiaires
- **M1** : Le serveur VD enveloppe DataOutputStream dans `BufferedOutputStream(65536)` — fusionne les petites écritures localhost
- **Correctif urgent** : `waitForVDServer()` appelé AVANT envoi réponse handshake — garantit que ServerSocket sur :19637 est ouvert avant que la voiture déploie le serveur VD (régression v0.7.4 : séquencement mettait l'attente VD après la réponse, empêchant le serveur VD de se connecter)

### v0.7.4 — File d'écriture + Séquençage flux (2026-04-23)

Changement d'architecture d'écriture + améliorations de flux :
- **File d'écriture** : Remplacement de `synchronized(outputLock)` par `ConcurrentLinkedQueue` sans verrou + coroutine writer dédiée. Writer utilise `delay(1)` quand le tampon d'envoi TCP est plein (libère le thread IO vers le pool). Ne bloque plus les autres coroutines pendant les écritures.
- **H10** : Handshake → auto-mise à jour → déploiement VD séquencés. L'auto-mise à jour met en pause l'initialisation, déconnecte, attend la reconnexion voiture.
- **H11** : Messages de statut voiture progressifs : "Préparation..." → "Démarrage..." → "En attente du flux vidéo..."
- **H12** : La voiture affiche "Vérifiez la boîte de dialogue d'autorisation sur le téléphone" pendant la connexion USB ADB
- **Correction bug** : Le serveur VD lance l'activité d'accueil sur VD après création — l'encodeur obtient du contenu immédiatement
- **Correction bug** : `usbConnecting` réinitialisé seulement quand `usbAdb == null` — empêche les dialogues d'auth USB-ADB en double

### v0.7.3 — Résilience réseau + Correction gel HyperOS (2026-04-23)

Résilience réseau + correction critique HyperOS découverte via preuves logcat :
- **Exemption batterie** : `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — empêche le "greeze" HyperOS de geler l'application cliente quand l'écran est éteint. Cause racine du bug "trames seulement pendant le toucher" : le serveur VD (processus shell) produisait 960+ trames mais le NioReader de l'app cliente était gelé par la gestion d'alimentation OS. Invite affichée au premier lancement.
- **C4/M11** : La piste WiFi voiture réessaye maintenant l'IP passerelle toutes les 3s (était tentative unique). Ajout `ConnectivityManager.NetworkCallback` sur voiture qui re-déclenche la piste WiFi quand le WiFi devient disponible. Gère le hotspot activé après branchement USB.
- **H9** : Le téléphone déconnecte proactivement sur perte réseau en état CONNECTED/STREAMING (était : attendre 10s timeout heartbeat). `onLost` → `cleanupSession()` → boucle d'écoute redémarre. `onAvailable` réinitialise seulement en WAITING (pas de perturbation des connexions actives).
- **Correction bug** : Le serveur VD lance l'activité d'accueil sur VD après création (`am start --display <id> HOME`) — garantit que l'encodeur a du contenu immédiatement.
- **Correction bug** : `usbConnecting` réinitialisé seulement quand `usbAdb == null` — empêche les dialogues d'auth USB-ADB en double.

### v0.7.2 — Stabilité côté voiture + Correction Selector (2026-04-23)

5 corrections de stabilité + correction critique NioReader :
- **H1** : Remplacement du sondage `delay(1)` par **Selector** dans NioReader — `selector.select(100)` se réveille instantanément via epoll quand les données arrivent. Corrige la vidéo non diffusée (les trames ne passaient que pendant le toucher sur Android 10 voiture à cause de `delay(1)` prenant 10-16ms). Ajout `wakeup()`/`close()` pour arrêt propre depuis disconnect.
- **H7** : `@Volatile` sur `wifiReady`, `usbReady`, `vdServerStarted`, `usbConnecting` — empêche état CONNECTING bloqué
- **H8** : `VideoDecoder.stop()` joint le thread d'alimentation (timeout 2s) avant `codec.stop()` — empêche crash natif
- **M7** : Suppression double `cleanup()` dans serveur VD — empêche IllegalStateException sur double libération
- **M8** : `cleanupSession()` réinitialise `_serviceState` à WAITING — empêche UI périmée pendant délai reconnexion
- **M9** : `onCreate()` efface `activeConnection` et `_serviceState` statiques — empêche état périmé au redémarrage du service

### v0.7.1 — Corrections critiques de bugs (2026-04-23)

6 corrections critiques/haute priorité issues de la revue complète :
- **C1** : `writeAll()` timeout d'écriture 5s — empêche gel système sur tampon d'envoi plein
- **C3** : Flag tentative auto-mise à jour — casse la boucle infinie mise à jour/redémarrage
- **C5** : WakeLock libération automatique 4h — empêche drain batterie sur sortie anormale
- **C6** : `@Volatile` sur VirtualDisplayClient channel/reader + écritures avec timeout protégé
- **H5** : `Connection.connect()` try/catch — ferme SocketChannel sur annulation
- **H6** : Écouteur déconnexion enveloppé dans try/catch — empêche propagation d'exception

### v0.7.0 — NIO complet + Correction service (2026-04-23)

Toutes les opérations socket converties en NIO non-bloquant. L'enregistrement mDNS ne bloque plus la boucle d'écoute. Code version lu à l'exécution via PackageManager.

**Changements :**
- **NioReader** : Nouveau lecteur tamponné non-bloquant pour SocketChannel (sondage delay(1), coopératif coroutine)
- **Connection.kt** : SocketChannel reste non-bloquant tout au long — plus de configureBlocking(true) après connect/accept
- **FrameCodec.kt** : Ajout méthodes NIO `readFrame(NioReader)` et `writeFrameToChannel(SocketChannel, Frame)`
- **VirtualDisplayClient.kt** : Lectures NIO (NioReader) + écritures ByteBuffer, canal reste non-bloquant
- **VirtualDisplayServer.java** : NIO SocketChannel pour connect (finishConnect non-bloquant avec nouvelle tentative)
- **ConnectionService.kt** : probePort() converti en NIO ; enregistrement mDNS lancé en arrière-plan avec timeout 5s (corrige service ne démarrant pas sans WiFi)
- **Code version** : Suppression constante `APP_VERSION_CODE` — les deux apps lisent versionCode à l'exécution via `PackageManager.getPackageInfo()`

### v0.6.2 — Modèle connexion parallèle + Mise à jour automatique (2026-04-23)

Réécriture majeure de l'architecture : pistes WiFi + USB parallèles, sockets NIO non-bloquants, mise à jour automatique pilotée par téléphone, multi-touch via IInputManager.

**Fonctionnel :**
- **Machine d'état connexion parallèle** : Découverte WiFi + USB ADB fonctionnent simultanément, VD déployé quand les deux sont prêts
- **Mise à jour automatique** : Le téléphone détecte l'app voiture obsolète au handshake, pousse la mise à jour via WiFi ADB (dadb)
- **Le téléphone déploie le JAR VD** : Extrait vers `/sdcard/DiLinkAuto/vd-server.jar` au lancement (CRC32 vérifié)
- **APK voiture intégré dans téléphone** : Le système de build compile l'APK voiture dans les assets du téléphone
- **Sockets NIO non-bloquants** : Tous les accept/connect utilisent `ServerSocketChannel`/`SocketChannel` — annulation instantanée, pas de EADDRINUSE
- **Entrée multi-touch** : Injection directe MotionEvent via `ServiceManager → IInputManager` (supporte tap, swipe, pinch)
- **Gestion alimentation écran** : `cmd display power-off 0` pendant streaming, proximité/lever désactivés, re-power-off throttled après injection tactile
- **Récupération machine d'état** : `connectionScope` annule toutes les coroutines à la déconnexion, reconnexion avec backoff exponentiel
- **Déconnexion utilisateur** : Le bouton d'éjection arrête la reconnexion (reste IDLE)
- **Recherche d'app** : Champ de recherche en bas de la grille d'apps, apps triées alphabétiquement
- **Panneau notifications** : Icône cloche dans la barre nav avec compteur de badge
- **Barre nav 72dp** : Icônes plus grandes (32dp) et texte (12sp) pour écrans de voiture
- **Vérification version handshake** : Champ `appVersionCode` dans HandshakeRequest, `vdServerJarPath` dans HandshakeResponse
- **Gestion changement réseau** : Le téléphone réinitialise la boucle d'écoute sur changements d'interface réseau (basculement hotspot)
- **Encodage H.264** : 8Mbps CBR, profil High, mode faible latence
- **Timeout handshake** : Timeout 10s avec annulation correcte (pas de timeouts périmés)

**Changements d'architecture depuis v0.5.0 :**
- Suppression du sondage SSID hotspot/connexion automatique WiFi depuis la voiture (simplifié)
- Déplacement UsbAdbConnection + AdbProtocol vers le module protocol (partagé par les deux apps)
- Le serveur VD se connecte AU téléphone (connexion inverse) au lieu du téléphone se connectant au serveur VD
- Le serveur VD quitte à la déconnexion téléphone (one-shot, la voiture re-déploie si nécessaire)
- Le téléphone extrait le JAR VD vers stockage partagé, la voiture lit le chemin depuis le handshake

### v0.5.0 — USB ADB + Configuration automatisée (2026-04-22)

Changement majeur d'architecture : la **voiture** déploie le serveur VD sur le téléphone via USB ADB. Wireless Debugging éliminé.

### v0.4.0 — VirtualDisplay réduit par GPU (2026-04-22)

Les applications s'affichent au DPI natif du téléphone (480dpi), le GPU réduit au viewport voiture. Pipeline SurfaceScaler EGL/GLES.

### v0.3.0 — Barre de navigation persistante (2026-04-21)

UI voiture avec barre nav gauche toujours visible, TextureView, vraies icônes d'applications.

### v0.2.0–v0.2.3 — Fondation Virtual Display (2026-04-21)

Création VD, auto-ADB, serveur résilient, support multi-applications.

### v0.1.0–v0.1.1 — Implémentation initiale (2026-04-21)

Projet créé. Miroir d'écran sur émulateurs.

---

## Traqueur de corrections

Revue complète effectuée le 2026-04-23 couvrant performance, stabilité et continuité de flux.

### Phase 1 — Critique (corriger avant prochaine release)

| ID | Catégorie | Découverte | Statut |
|----|----------|---------|--------|
| C1 | Stabilité/Perf | `writeAll()` tourne indéfiniment sur tampon d'envoi plein — pas de timeout, détient `outputLock`, bloque tous les émetteurs. Risque gel système | **v0.7.1** |
| C2 | Flux | Le serveur VD meurt à la déconnexion USB (`shellNoWait` lie le processus au flux ADB). Reconnexion complète 5-15s | REVENU — `setsid`/`nohup` cassait localhost. Utilisation `shellNoWait`+`exec` (approche v0.6.2). Reconnecte au rebranchement. |
| C3 | Flux | L'auto-mise à jour n'a pas de rupture de boucle — si `pm install` échoue silencieusement, cycle de redémarrage infini | **v0.7.1** |
| C4 | Flux | La piste WiFi voiture s'exécute une fois et abandonne — hotspot activé après branchement USB → bloqué pour toujours | **v0.7.3** |
| C5 | Stabilité | WakeLock acquis sans timeout — drain batterie si service tué sans `onDestroy()` | **v0.7.1** |
| C6 | Stabilité | `VirtualDisplayClient.touch()` boucle écriture non-bloquante + champ `channel` non volatile — course de données | **v0.7.1** |

### Phase 2 — Haute (latence & stabilité)

| ID | Catégorie | Découverte | Statut |
|----|----------|---------|--------|
| H1 | Perf | Sondage NIO `delay(1)` ajoute 1-4ms latence plancher par lecture + 1000 réveils/sec au repos. Utiliser Selector ou `runInterruptible` | **v0.7.2** |
| H2 | Perf | Deux appels système par écriture trame (en-tête 6 octets + charge utile). Utiliser `GatheringByteChannel.write(ByteBuffer[])` | **v0.8.0** |
| H3 | Perf | `ByteArray.copyOf()` par trame dans relais vidéo (~30 allocs/sec de 10-100Ko). Passer offset+length | **v0.8.0** |
| H4 | Perf | `synchronized(outputLock)` sérialise vidéo+tactile+heartbeat. Écriture keyframe bloque tactile ~200ms | **v0.7.4** |
| H5 | Stabilité | `Connection.connect()` fuit SocketChannel sur annulation — pas de try/finally | **v0.7.1** |
| H6 | Stabilité | `disconnectListener` invoqué de façon synchrone dans CAS — deadlock potentiel | **v0.7.1** |
| H7 | Stabilité | Flags état voiture (`wifiReady`, `usbReady`) non volatiles — peut rester bloqué en CONNECTING | **v0.7.2** |
| H8 | Stabilité | `VideoDecoder.stop()` ne joint pas le thread d'alimentation avant `codec.stop()` — risque crash natif | **v0.7.2** |
| H9 | Flux | Callback réseau téléphone ignore CONNECTED/STREAMING — basculement hotspot cause 10s trame gelée | **v0.7.3** |
| H10 | Flux | Handshake + auto-mise à jour + déploiement VD en concurrence — deployAssets peut ne pas être fini, opérations ADB concurrentes | **v0.7.4** |
| H11 | Flux | Pas de retour utilisateur pendant démarrage serveur VD 5-12s — la voiture montre un spinner statique | **v0.7.4** |
| H12 | Flux | Premier dialogue auth USB ADB sur téléphone sans guidage sur écran voiture — timeout 30s | **v0.7.4** |

### Phase 3 — Moyenne (problèmes notables)

| ID | Catégorie | Découverte | Statut |
|----|----------|---------|--------|
| M1 | Perf | Le serveur VD flush après chaque trame sur localhost — appel système inutile | **v0.8.0** |
| M2 | Perf | `checkStackEmpty()` bloque lecteur commandes 500ms — blackout tactile après Retour | **v0.8.1** |
| M3 | Perf | Multi-touch envoie N trames séparées par MOVE — devrait grouper tous les pointeurs | **v0.8.3** |
| M4 | Perf | MotionEvent PointerProperties/Coords alloués par injection — mettre en pool | **v0.8.1** |
| M5 | Perf | File trames décodeur profondeur 6 (200ms) — réduire à 2-3 pour latence plus faible | **v0.8.1** |
| M6 | Perf | `execFast("cmd display power-off 0")` sur thread tactile — déplacer vers timer | **v0.8.1** |
| M7 | Stabilité | Double `cleanup()` dans serveur VD — handleClient finally + run l'appellent tous les deux | **v0.7.2** |
| M8 | Stabilité | `cleanupSession()` ne réinitialise pas `_serviceState` — UI périmée pendant délai | **v0.7.2** |
| M9 | Stabilité | MutableStateFlow statique dans companion survit aux redémarrages service — activeConnection périmé | **v0.7.2** |
| M10 | Flux | Déconnexion utilisateur (éjection) non persistée — la voiture reconnecte après kill processus | **v0.8.3** |
| M11 | Flux | mDNS voiture + sonde IP passerelle tentative unique — besoin nouvelle tentative périodique | **v0.7.3** |
| M12 | Flux | Parsing `dumpsys activity` dans checkStackEmpty fragile entre versions Android | **v0.8.2** |

### Phase 4 — Basse (finition)

| ID | Catégorie | Découverte | Statut |
|----|----------|---------|--------|
| L1 | Perf | `TouchEvent.encode()` alloue ByteArray 25 octets par événement — pas poolable avec file d'écriture async | WONTFIX |
| L2 | Perf | Socket localhost serveur VD manque config tailles tampon send/receive | **v0.8.2** |
| L3 | Perf | Décodeur vidéo utilise horodatage fixe 33 333us — devrait utiliser horloge murale | **v0.8.2** |
| L4 | Stabilité | NioReader direct ByteBuffer non libéré déterministiquement | **v0.8.3** |
| L5 | Stabilité | `UsbAdbConnection.readFile()` ne garantit pas lecture complète | **v0.8.2** |
| L6 | Stabilité | SurfaceScaler HandlerThread jamais quitté | **v0.8.2** |
| L7 | Flux | Icônes app 48x48px — floues sur écrans voiture, besoin 96-128px | **v0.8.2** |

---

## Problèmes connus

| Problème | Impact | Statut |
|-------|--------|--------|
| Dialogue auth USB ADB au rebranchement | Téléphone demande "Autoriser débogage USB ?" à chaque fois | **CORRIGÉ v0.13.1** — double-hachage AUTH_TOKEN avec SHA1withRSA. Utilise maintenant NONEwithRSA + SHA-1 DigestInfo pré-haché. "Toujours autoriser" persiste. |
| Le serveur VD meurt à la déconnexion USB | Le stream s'arrête si USB débranché | Accepté — détachement `setsid`/`nohup` cassait connectivité localhost. La voiture re-déploie à la reconnexion. |
| Injection tactile réveille écran physique | L'écran s'allume brièvement pendant l'interaction | Atténué avec re-power-off throttled (1s, sur thread d'arrière-plan) |
| Apps portrait en letterbox sur VD paysage | Écran d'accueil Petal Maps étroit | Limitation Android |
| Le hotspot doit être activé manuellement | L'utilisateur active avant de brancher | Limitation Android 16 |

---

## Architecture (Actuelle)

```
Téléphone (Xiaomi 17 Pro Max, HyperOS 3, Android 16)
├── DiLink Auto Client App
│   ├── ConnectionService (accept 3 ports : 9637/9638/9639)
│   │   ├── Contrôle (9637) : handshake, heartbeat, commandes, données, logs voiture
│   │   ├── Vidéo (9638) : relais H.264 du serveur VD à la voiture
│   │   ├── Entrée (9639) : événements tactiles de la voiture, distribués sur Dispatchers.IO
│   │   ├── Déploiement JAR VD vers /sdcard/DiLinkAuto/ (CRC32 vérifié)
│   │   ├── Mise à jour auto voiture : envoie UPDATING_CAR, puis dadb push+install
│   │   ├── Callback réseau intelligent (ignore pertes réseau non liées)
│   │   ├── Exemption batterie (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
│   │   └── FileLog : /sdcard/DiLinkAuto/client.log (rotation, 10 max)
│   ├── VirtualDisplayClient (videoConnection + controlConnection)
│   │   ├── startListening() — ServerSocket synchrone sur localhost:19637
│   │   ├── acceptConnection() — accept NIO non-bloquant
│   │   ├── NioReader (basé Selector, timeout FRAME_INTERVAL_MS)
│   │   └── Relais vidéo via videoConnection, pile vide via controlConnection
│   └── NotificationService (capture notifications téléphone avec progression)
│
├── VD Server (app_process, shell UID 2000)
│   ├── File d'écriture NIO (ConcurrentLinkedQueue) + lecteur commandes basé Selector
│   ├── Injection IInputManager (ServiceManager → injectInputEvent)
│   ├── Multi-touch : pools PointerProperties/Coords pré-alloués (10 slots)
│   ├── VirtualDisplay (TRUSTED + OWN_DISPLAY_GROUP + OWN_FOCUS)
│   ├── SurfaceScaler (réduction GPU EGL/GLES, saute travail GL au repos, repeat-previous-frame encodeur)
│   ├── Encodeur H.264 (8Mbps CBR, profil Main, FPS configurable, contre-pression à 6 trames)
│   ├── Extinction écran (thread d'arrière-plan, proximité/lever désactivés)
│   └── Connexion NIO inverse au téléphone sur localhost:19637
│
Voiture (BYD DiLink 3.0, Android 10)
├── DiLink Auto Server App
│   ├── CarConnectionService — 3 connexions + piste USB parallèle
│   │   ├── controlConnection (9637) : heartbeat, commandes, données
│   │   ├── videoConnection (9638) : trames vidéo → VideoDecoder
│   │   ├── inputConnection (9639) : événements tactiles de MirrorScreen
│   │   ├── Piste B (USB) : UsbAdbConnection avec logSink → carLogSend
│   │   ├── Gestion UPDATING_CAR : affiche statut, saute reconnexion
│   │   ├── Démarrage anticipé décodeur : surface hors écran au premier CONFIG
│   │   ├── carLogSend() + callbacks logSink → FileLog téléphone
│   │   └── État éjection persisté dans SharedPreferences
│   ├── VideoDecoder (file=15, démarrage anticipé, logSink, catchup 4 zones)
│   ├── PersistentNavBar (76dp, icônes 40dp, texte 14sp, apps récentes élaguées)
│   ├── LauncherScreen (icônes 64dp, grille 160dp, recherche imePadding)
│   ├── NotificationScreen (barres progression, appui pour lancer, dédoublonnage par ID)
│   └── MirrorScreen (TextureView + transfert tactile, redémarrage décodeur)
```

## Flux de connexion

```
1. App téléphone démarre → déploie JAR VD, rotation FileLog, demande exemption batterie
2. Téléphone branché USB voiture → voiture détecte USB_DEVICE_ATTACHED
3. Piste A (WiFi) : voiture découvre téléphone via IP passerelle (réessai 3s) ou mDNS
4. Piste B (USB) : voiture connecte USB ADB (logSink diagnostics), lance app téléphone
5. Contrôle (9637) : connexion TCP → handshake (viewport + DPI + version + targetFps)
6. Téléphone : vérifie version → si différence, envoie UPDATING_CAR → mise à jour auto via dadb
7. Vidéo (9638) + Entrée (9639) : voiture connecte en parallèle après handshake
8. Téléphone : accepte les deux, ouvre VD ServerSocket sur localhost:19637
9. USB : voiture démarre serveur VD (shellNoWait + exec app_process, FPS comme argument)
10. Serveur VD : VD + SurfaceScaler (redessin périodique) + encodeur → connexion NIO localhost:19637
11. Voiture : démarre VideoDecoder sur surface hors écran à la première trame CONFIG
12. MirrorScreen s'affiche → décodeur redémarre avec vraie surface TextureView
13. Vidéo : VD → SurfaceScaler → encodeur → file écriture NIO → localhost → NioReader téléphone → videoConnection → WiFi TCP → NioReader voiture → VideoDecoder → TextureView
14. Tactile : voiture TextureView → inputConnection → WiFi TCP → téléphone (Dispatchers.IO) → Selector NIO serveur VD → injection IInputManager
15. Logs voiture : carLogSend() + callbacks logSink → DATA CAR_LOG → FileLog téléphone
```
