# Architecture

## Aperçu

DiLink-Auto est un projet Gradle à quatre modules :

```
DiLink-Auto/
├── protocol/        Librairie Android -- partagée par les deux applications (module Gradle)
├── app-client/      Application Android -- s'exécute sur le téléphone (module Gradle)
├── app-server/      Application Android -- s'exécute sur la voiture (module Gradle)
├── vd-server/       Librairie Android -- serveur VirtualDisplay (module Gradle)
```

## Architecture Virtual Display

Le **téléphone** déploie et démarre le serveur VD localement. Le serveur VD se connecte en retour à l'application téléphone (connexion inverse sur localhost:19637, entièrement NIO non-bloquant). Les applications s'affichent sur un VirtualDisplay au DPI natif du téléphone (480dpi), réduit par GPU à la résolution du viewport de la voiture pour l'encodage H.264. L'écran physique du téléphone est complètement indépendant.

```
+- Téléphone -------------------------------------------------------+
|                                                                   |
|  +---------------------+                                          |
|  | Écran physique       |  Indépendant -- l'utilisateur peut      |
|  | (affiche DiLink Auto  |  utiliser le téléphone normalement     |
|  |  ou autre chose)      |                                        |
|  +---------------------+                                          |
|                                                                   |
|  +---------------------+      +--------------------------------+  |
|  | ConnectionService    |      | VD Server (shell UID 2000)     |  |
|  | 3 serveurs TCP NIO   |<----+| Déployé par l'app TÉLÉPHONE   |  |
|  |  9637 : contrôle      | 19637| Connexion inverse au téléphone |  |
|  |  9638 : vidéo         |  NIO | NIO non-bloquant localhost    |  |
|  |  9639 : entrée        |      |                                |  |
|  | Déploiement JAR VD    |      | VirtualDisplay (DPI téléphone) |  |
|  | Mise à jour auto      |      | GPU SurfaceScaler (réduction)  |  |
|  |  voiture              |      |   redessin périodique au repos |  |
|  | FileLog               |      | Encodeur H.264 (viewport auto) |  |
|  | Relaie vidéo à la     |      | Lanceur d'apps (am start)      |  |
|  |  voiture              |      | Injecteur entrée (IInputManager)|  |
|  | Transfère le tactile  |      | File d'écriture NIO + lecture  |  |
|  |  au VD                |      |  Selector                      |  |
|  +----------+-----------+      +--------------------------------+  |
+-------------+------------------------------------------------------+
              | WiFi TCP (3 connexions)
              | 9637 : contrôle + données
              | 9638 : vidéo H.264
              | 9639 : entrée tactile
              v
+- Voiture (BYD DiLink 3.0) ----------------------------------------+
|                                                                   |
|  CarConnectionService                                             |
|  Modèle de connexion parallèle : pistes WiFi + USB simultanées    |
|                                                                   |
|  Piste A (WiFi) :                                                 |
|  +-- IP passerelle + découverte mDNS                             |
|  +-- Connexion contrôle (9637) → handshake (viewport+DPI+FPS)    |
|  +-- Connexions vidéo (9638) + entrée (9639) après handshake      |
|  +-- Reçoit vidéo H.264 → VideoDecoder → TextureView              |
|  +-- Envoie événements tactiles via connexion entrée              |
|                                                                   |
|  Piste B (USB) :                                                  |
|  +-- Scan périphériques USB → connexion USB ADB (logSink diag)   |
|  +-- Lance l'app téléphone (am start)                             |
|                                                                   |
|  Le téléphone met à jour l'app voiture via dadb si version diff.  |
|  La voiture reçoit message UPDATING_CAR → affiche statut, stop   |
|  reconnexion                                                      |
|                                                                   |
|  UI : CarLaunchScreen → (icônes apps arrivent) → CarShell + NavBar|
|  Deux modes : lancement (focus connexion, pas de nav) et streaming|
|  Barre nav (76dp) : Notifications (badge+progression), Accueil,   |
|  Retour                                                           |
|  Icônes 40dp, texte 14sp                                          |
+-------------------------------------------------------------------+
```

## Pourquoi l'USB ADB depuis la voiture ?

`app_process` doit s'exécuter en tant que shell UID (2000) pour créer des VirtualDisplays capables d'héberger des applications tierces. La voiture se connecte à `adbd` du téléphone via le mode hôte USB en utilisant une implémentation personnalisée du protocole ADB (`UsbAdbConnection` dans le module protocol/).

**Approche actuelle :** La voiture agit comme hôte USB ADB. Le téléphone a seulement besoin du **Débogage USB** activé (Option développeur standard). Pas de Débogage sans fil, pas de codes d'appairage, pas de dépendance WiFi pour la configuration.

## Responsabilités des modules

### protocol (Librairie Android)

Partagée par les deux applications. Contient UsbAdbConnection, AdbProtocol, VideoConfig et NioReader. Zéro dépendance au-delà des coroutines Kotlin.

| Composant | Fichier | Rôle |
|-----------|------|---------|
| Codec de trame | `FrameCodec.kt` | Encodage/décodage binaire des trames, tampon d'en-tête réutilisable, writeAll NIO |
| Canaux | `Channel.kt` | IDs de canal (control, video, audio, data, input) |
| Types de message | `MessageType.kt` | Constantes byte incluant `VD_STACK_EMPTY`, `UPDATING_CAR` |
| Messages | `Messages.kt` | Classes de données sérialisables (le handshake inclut `appVersionCode`, `vdServerJarPath`, `targetFps`) |
| Connexion | `Connection.kt` | Connexion TCP avec heartbeat/watchdog optionnel, file d'écriture sans verrou, NioReader |
| VideoConfig | `VideoConfig.kt` | `TARGET_FPS`, `FRAME_INTERVAL_MS` — constantes de timing partagées |
| NioReader | `NioReader.kt` | Lecteur non-bloquant basé sur Selector, timeout de select configurable |
| Découverte | `Discovery.kt` | Enregistrement/découverte de service mDNS, constantes de port (9637/9638/9639) |
| UsbAdbConnection | `adb/UsbAdbConnection.java` | Protocole ADB sur USB (CNXN, AUTH, OPEN, WRTE), callback logSink |
| AdbProtocol | `adb/AdbProtocol.java` | Constantes de message ADB et sérialisation |

### app-client (Application Téléphone)

Gère le déploiement du serveur VD, la mise à jour automatique de la voiture, le relais à 3 connexions et FileLog.

| Composant | Fichier | Rôle |
|-----------|------|---------|
| ConnectionService | `service/ConnectionService.kt` | Acceptation 3 ports (9637/9638/9639), déploiement JAR VD, mise à jour auto voiture, callback réseau intelligent |
| VirtualDisplayClient | `display/VirtualDisplayClient.kt` | Acceptation NIO sur localhost:19637, relais vidéo (videoConnection), transfert tactile, pile vide (controlConnection) |
| NotificationService | `service/NotificationService.kt` | Capture et transfère les notifications du téléphone avec progression |
| InputInjectionService | `service/InputInjectionService.kt` | Solution de repli pour l'injection tactile (écran physique) |
| FileLog | `FileLog.kt` | Journalisation fichier vers `/sdcard/DiLinkAuto/client.log`, rotation, contourne le filtrage logcat |
| MainActivity | `MainActivity.kt` | UI — démarrer/arrêter, statut des permissions, bouton Installer sur la voiture |

### app-server (Application Voiture)

Modèle de connexion parallèle avec pistes WiFi (3 connexions) et USB.

| Composant | Fichier | Rôle |
|-----------|------|---------|
| AppIconCache | `AppIconCache.kt` | Cache d'icônes côté voiture — décode les PNG sources 192x192 une fois, `prepareAll()` redimensionne toutes les icônes sur thread d'arrière-plan, `getPrepared()` est une recherche O(1) ConcurrentHashMap sans I/O pendant le défilement |
| CarConnectionService | `service/CarConnectionService.kt` | Machine d'état parallèle, connexions WiFi 3 voies + piste USB, gestion UPDATING_CAR |
| VideoDecoder | `decoder/VideoDecoder.kt` | Décodage H.264, file de 30 trames, démarrage anticipé sur surface hors écran, callback logSink |
| CarLaunchScreen | `ui/screen/CarLaunchScreen.kt` | Écran de lancement/connexion plein écran (sans nav), marque, instructions, IP manuelle |
| MirrorScreen | `ui/screen/MirrorScreen.kt` | TextureView + transfert tactile, redémarrage décodeur quand la surface est disponible |
| HomeContent | `ui/screen/HomeScreen.kt` | Grille d'apps (icônes 64dp, cellules 160dp) ou statut de connexion, affiché en mode streaming |
| LauncherScreen | `ui/screen/LauncherScreen.kt` | Écran intégré legacy avec SideNavBar, CarStatusBar, AppGrid |
| NotificationScreen | `ui/screen/NotificationScreen.kt` | Liste de notifications avec barres de progression, appui pour lancer |
| PersistentNavBar | `ui/nav/PersistentNavBar.kt` | Barre de navigation 76dp (icônes 40dp, texte 14sp), apps récentes (élaguées), mode streaming uniquement |
| RecentAppsState | `ui/nav/RecentAppsState.kt` | Suit les apps récentes, élague les indisponibles |
| MainActivity | `MainActivity.kt` | Plein écran immersif, transfert d'intent USB, routage d'écran deux modes (lancement vs streaming) |

### vd-server (Processus privilégié shell)

Module librairie Android (`com.android.library`), compilé via `bundleLibRuntimeToJarDebug` puis D8 en JAR par la tâche `buildVdServer` dans `app-client/build.gradle.kts`. Dépend de `:protocol` et `kotlinx-coroutines-core`. Déployé par le téléphone vers `/sdcard/DiLinkAuto/`.

| Composant | Fichier | Rôle |
|-----------|------|---------|
| VirtualDisplayServer | `VirtualDisplayServer.kt` | Crée VD, file d'écriture NIO + lecteur Selector, encodeur H.264, FPS configurable, contre-pression |
| FakeContext | `FakeContext.kt` | Simule `com.android.shell` pour l'accès à DisplayManager |
| SurfaceScaler | `SurfaceScaler.kt` | Pipeline de réduction GPU EGL/GLES, saute le travail GL au repos (compte sur la répétition de trame précédente de l'encodeur) |

## Flux de connexion

```
1. Téléphone et voiture sur le même réseau (ou téléphone branché en USB à la voiture)
2. L'app voiture se lance, démarre les pistes WiFi + USB en parallèle

   Piste A (WiFi) :
   a. Découverte IP passerelle + recherche mDNS
   b. Connexion NIO au port de contrôle du téléphone (9637)
   c. Handshake : la voiture envoie viewport + DPI + appVersionCode + targetFps
   d. Le téléphone répond avec infos appareil + vdServerJarPath
   e. Le téléphone vérifie appVersionCode -- si différence, envoie UPDATING_CAR, met à jour via dadb
   f. La voiture connecte vidéo (9638) + entrée (9639) en parallèle après handshake
   g. Le téléphone accepte les deux, session complètement établie

   Piste B (USB) :
   a. Scan des périphériques USB pour l'interface ADB
   b. Connexion USB ADB (CNXN -> AUTH -> connecté), logSink achemine vers carLogSend
   c. Lance l'app téléphone via am start

3. Le téléphone déploie vd-server.jar vers /sdcard/DiLinkAuto/
4. La voiture démarre : CLASSPATH=jar app_process / VirtualDisplayServer W H DPI PORT EW EH FPS
5. Le serveur VD crée VirtualDisplay (DPI téléphone) + GPU SurfaceScaler (redessin périodique)
6. Le serveur VD se connecte en retour AU téléphone sur localhost:19637 (NIO non-bloquant)
7. Le téléphone accepte la connexion du serveur VD via NIO ServerSocketChannel
8. La voiture démarre VideoDecoder sur surface hors écran dès la première trame CONFIG
9. MirrorScreen s'affiche, redémarre le décodeur avec la vraie surface TextureView
10. Flux vidéo : VD -> SurfaceScaler -> encodeur -> file d'écriture NIO -> localhost -> NioReader téléphone -> file d'écriture videoConnection -> WiFi TCP -> NioReader voiture -> VideoDecoder -> TextureView
```

États : IDLE -> CONNECTING -> CONNECTED -> STREAMING

## Décisions de conception clés

| Décision | Justification |
|----------|-----------|
| **3 connexions TCP dédiées** | Contrôle/vidéo/entrée sur sockets séparés. Élimine la contre-pression inter-canaux (la liste d'apps ne peut pas bloquer la vidéo). |
| **Pistes WiFi + USB parallèles** | Les deux fonctionnent simultanément ; checkAndAdvance() évalue l'état quand un prérequis change. |
| **Le téléphone déploie le JAR VD** | Pas besoin de push côté voiture. Le téléphone extrait vd-server.jar vers sdcard via deployAssets(). |
| **Connexion VD inversée** | Le serveur VD se connecte AU téléphone (pas l'inverse), simplifiant la gestion firewall/NAT. |
| **NIO non-bloquant partout** | Toutes les sockets non-bloquantes : connexions WiFi, serveur VD localhost, lectures basées Selector. Aucune I/O bloquante dans le pipeline. |
| **FPS configurable** | La voiture envoie `targetFps` dans le handshake, le serveur VD l'utilise. Tous les timeouts du pipeline dérivent de `FRAME_INTERVAL_MS = 1000/fps`. |
| **Encoder repeat-previous-frame-after** | SurfaceScaler saute le travail GL sur les trames au repos. L'encodeur répète la dernière trame jusqu'à 500ms sur contenu statique, évitant la famine sans surcoût GPU. |
| **Démarrage anticipé du décodeur** | Le décodeur démarre sur SurfaceTexture hors écran quand le premier CONFIG arrive, avant la création du TextureView de MirrorScreen. Empêche la perte d'images clés pendant la composition UI. |
| **Callback réseau intelligent** | `onLost` ignore les pertes réseau non liées (données mobiles). Ne détruit la session que si le réseau de la connexion active est perdu. |
| **Mise à jour auto voiture avec messagerie** | Le téléphone envoie UPDATING_CAR avant l'installation. La voiture affiche le statut, ne reconnecte pas aveuglément. |
| **APK voiture intégré dans l'APK téléphone** | Le système de build empaquette app-server.apk dans app-client, permettant la fonctionnalité Installer sur la voiture. |
| **GPU SurfaceScaler** | Le VD s'affiche au DPI 480 du téléphone (pas de mise à l'échelle de compat). Le GPU réduit au viewport de la voiture. |
| **FakeContext** | ActivityThread + getSystemContext() pour un vrai Context système. Contourne le NPE UserManager via réflexion mDisplayIdToMirror. |
| **Flags VD de confiance** | `OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED` empêchent la migration d'activité. |
| **Largeur viewport paire** | Largeur de la barre de navigation ajustée pour garantir des dimensions paires compatibles H.264. |
| **Heartbeat sur contrôle uniquement** | Les connexions vidéo et entrée n'ont pas de surcoût heartbeat. Le watchdog de la connexion de contrôle détecte les pairs morts. |
| **FileLog** | Contourne le filtrage logcat HyperOS. Journalisation fichier avec rotation sur `/sdcard/DiLinkAuto/`. |
| **Cache d'icônes d'app** | Le cache côté voiture persiste les PNG sources (192x192) sur disque. `prepareAll()` décode et redimensionne toutes les icônes à la taille de la grille sur un thread d'arrière-plan après l'arrivée de APP_LIST. `getPrepared()` est une recherche ConcurrentHashMap sans coût — sans coroutines, sans I/O, sans décodage pendant le défilement. |
| **Rejet/effacement des notifications** | Bouton de rejet par élément et en-tête "Tout effacer" sur l'écran de notifications de la voiture. La voiture envoie les messages de données NOTIFICATION_CLEAR / NOTIFICATION_CLEAR_ALL au téléphone, qui efface les notifications Android correspondantes. |
| **Callbacks logSink** | VideoDecoder et UsbAdbConnection acheminent les logs via le protocole vers le FileLog du téléphone. |
| **Auth ADB pré-hachée** | AUTH_SIGNATURE utilise `NONEwithRSA` + préfixe SHA-1 DigestInfo (pré-haché). Correspond à `RSA_sign(NID_sha1)` d'AOSP. "Toujours autoriser" persiste correctement. |
| **Alimentation écran via SurfaceControl** | `DisplayControl` chargé depuis `services.jar` via `ClassLoaderFactory` (Android 14+). Repli sur `cmd display power-off/on`. Le téléphone restaure l'écran à la déconnexion VD. |
| **Catchup décodeur** | Quatre zones de vitesse graduées : normale (0-6 trames), douce 1.5x (7-12), moyenne 2x (13-20), agressive 3x (21+). Les images clés ne sont jamais sautées. |
| **Dédoublonnage lancement app** | `am start` sans `--activity-clear-task`. Les apps existantes reprennent au lieu de redémarrer. |
| **Contre-pression serveur VD** | Abandonne les non-keyframes à l'encodeur quand la file d'écriture dépasse 6 trames. Empêche la croissance mémoire illimitée. |
| **Déconnexion utilisateur** | Reste IDLE, pas de reconnexion automatique. Persisté dans SharedPreferences. |

## Pile technologique

| Couche | Technologie |
|-------|-----------|
| Langage | Kotlin 1.9.22 (tous les modules) |
| Build | Gradle 8.7, AGP 8.2.2 |
| UI | Jetpack Compose + Material 3 |
| Vidéo | MediaCodec H.264 (encodeur : serveur VD 8Mbps CBR Main, décodeur : voiture) |
| GPU | EGL14 + GLES20 + SurfaceTexture (SurfaceScaler avec redessin périodique) |
| Réseau | NIO ServerSocketChannel / SocketChannel / Selector, Android NSD (mDNS) |
| USB ADB | Protocole personnalisé dans le module protocol/ (partagé), logSink pour diagnostics |
| WiFi ADB | dadb 1.2.10 (mise à jour auto voiture) |
| Async | Kotlin Coroutines + Flow |
| API min | 29 (Android 10) |
| Version app | versionName comparée via semver (partagé dans gradle.properties) ; versionCode toujours envoyé pour compatibilité ascendante |
| Version protocole | PROTOCOL_VERSION = 1 |
