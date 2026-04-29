# Application cliente téléphone (app-client)

## Aperçu

Le client téléphone gère le déploiement du serveur VD, la mise à jour automatique de la voiture et le relais à 3 connexions. L'application téléphone :

1. Écoute les connexions TCP de la voiture sur le port 9637 (contrôle, NIO ServerSocketChannel)
2. Répond au handshake avec les infos appareil, vdServerJarPath, et lit `targetFps`
3. Compare appVersionName du handshake (avec appVersionCode en solution de repli) — envoie `UPDATING_CAR` et met à jour l'app voiture via dadb si différence de version
4. Accepte les connexions vidéo (9638) et entrée (9639) de la voiture après le handshake
5. Déploie vd-server.jar vers `/sdcard/DiLinkAuto/` et démarre le serveur VD (avec argument FPS)
6. Accepte la connexion inverse du serveur VD sur localhost:19637 (NIO ServerSocketChannel)
7. Relaie la vidéo H.264 du serveur VD à la voiture via la connexion vidéo
8. Relaie les événements tactiles de la voiture (connexion entrée) au serveur VD, distribués sur `Dispatchers.IO`

**Pas de capture d'écran. Pas de MediaProjection.** Toute la vidéo provient du processus serveur VD.

## Composants

### ClientApp

Classe Application. Crée les canaux de notification (`dilinkauto_service`, `dilinkauto_update`), initialise `UpdateManager` et `ShizukuManager` à la création.

### UpdateManager

Mécanisme d'auto-mise à jour qui vérifie les nouvelles versions sur GitHub Releases.
- `checkForUpdate(force)` : Interroge `https://api.github.com/repos/andersonlucasg3/dilink-auto-android/releases/latest`, compare la version semver du nom de tag avec la versionName installée. Respecte un délai de 6 heures sauf si forcé.
- `downloadUpdate()` : Télécharge l'APK via `HttpsURLConnection` avec rapport de progression. Vérifie via `PackageManager.getPackageArchiveInfo()`.
- `installUpdate(context)` : Utilise Shizuku `pm install -r` pour une installation silencieuse quand Shizuku est disponible ; sinon, ouvre l'installateur de paquet système via URI `FileProvider`.
- États : Idle, Checking, Available, Downloading, ReadyToInstall, Installing, Installed, UpToDate, Error. Exposé via `StateFlow`.

### MainActivity

Point d'entrée avec deux écrans :

- **OnboardingScreen** (premier lancement) : Assistant en 7 étapes — Bienvenue, Accès Tous les Fichiers, Optimisation Batterie, Service Accessibilité, Accès Notifications, Configuration Voiture, Terminé. Chaque étape de permission explique ce qui ne fonctionne pas sans elle. Avance automatiquement quand la permission est accordée. L'utilisateur peut sauter n'importe quelle étape.
- **ClientScreen** (lancements suivants) : carte de statut, bouton démarrer/arrêter, Installer sur la voiture, carte d'auto-mise à jour, bouton Partager les Logs, et statut des permissions restantes pour ce qui a été sauté pendant l'intégration.

### ConnectionService

Service foreground qui gère le cycle de vie de la connexion téléphone-voiture avec 3 connexions dédiées. Démarre automatiquement quand l'application téléphone est ouverte (par ex., via USB ADB de la voiture).

- **Connexion contrôle** (port 9637) : Serveur TCP NIO sur `0.0.0.0`, gère handshake, heartbeat, commandes d'app, canal DATA
- **Connexion vidéo** (port 9638) : Acceptée après handshake, transmise à VirtualDisplayClient pour le relais vidéo
- **Connexion entrée** (port 9639) : Acceptée après handshake, écouteur de trame INPUT distribué sur `Dispatchers.IO` pour éviter NetworkOnMainThreadException sur les écritures tactiles localhost
- `deployAssets()` : Extrait vd-server.jar vers sdcard, app-server.apk vers filesDir
- Détecte les différences de version → envoie `UPDATING_CAR` → met à jour l'app voiture via dadb (WiFi ADB, dadb 1.2.10)
- Callback réseau intelligent : filtré par `TRANSPORT_WIFI` — réagit uniquement aux changements WiFi, ignore les fluctuations 3G/4G
- Relaie les trames vidéo (H.264 CONFIG + FRAME) du VD à la voiture via la connexion vidéo
- Achemine les événements tactiles de la voiture (connexion entrée) vers le serveur VD
- Envoie la liste d'apps avec icônes PNG 96x96 à la voiture via la connexion contrôle
- Gère les commandes LAUNCH_APP, GO_BACK, GO_HOME et les transmet au serveur VD
- Met en file d'attente les lancements d'app si le serveur VD n'est pas encore connecté
- Enregistre le service mDNS pour la découverte automatique par la voiture
- `FileLog.rotate()` au démarrage du service — archive le log de la session précédente
- Bouton "Installer sur la voiture" : installation manuelle + automatique sur différence de version au handshake

### VirtualDisplayClient

Accepte la connexion inverse du processus serveur VD sur `localhost:19637`. Prend deux paramètres Connection : `videoConnection` et `controlConnection`.

- Acceptation NIO ServerSocketChannel (non-bloquante) — le serveur VD se connecte AU téléphone
- Lit : `MSG_VIDEO_CONFIG`, `MSG_VIDEO_FRAME`, `MSG_STACK_EMPTY`, `MSG_DISPLAY_READY`
- Écrit : `CMD_LAUNCH_APP`, `CMD_GO_BACK`, `CMD_GO_HOME`, `CMD_INPUT_TOUCH` (0x32)
- Les trames vidéo sont relayées via `videoConnection.sendVideo()` (isolées du trafic contrôle)
- Signal pile vide (`MSG_STACK_EMPTY`) transmis à la voiture via `controlConnection.sendControl()`
- Les écritures tactiles vers localhost sont synchrones avec `FrameCodec.writeAll()` sous `writeLock`
- À la déconnexion : restaure l'écran physique (`cmd display power-on 0` + `KEYCODE_WAKEUP`) comme filet de sécurité quand le processus serveur VD est tué avant nettoyage

### AdbBridge

Assistant de commandes shell de secours. Fournit `execShell()` et `execFast()` utilisant `Runtime.exec()` pour les opérations du serveur VD et la gestion d'alimentation de l'écran quand la réflexion API directe échoue.

### VirtualDisplayManager

Gère le lancement d'applications sur l'écran physique quand le VD n'est pas utilisé. Fait le pont vers `InputInjectionService` pour l'injection d'entrée basée sur les gestes.

### VideoEncoder

Encodeur H.264 MediaProjection + MediaCodec utilisant un virtual display `AUTO_MIRROR`. Chemin d'encodage alternatif (non utilisé dans le pipeline de streaming principal qui passe par le serveur VD).

### FileLog

Journalisation basée fichier qui contourne le filtrage logcat Android (HyperOS filtre `Log.i/d` pour les apps non-système).

- Écrit vers `/sdcard/DiLinkAuto/client.log`
- `rotate()` : Archive le log actuel en `client-YYYYMMDD-HHmmss.log`, recommence à zéro
- `zipLogs()` : Crée `dilinkauto-logs.zip` à partir de tous les fichiers `.log` pour le partage
- Garde 10 logs max (9 archivés + actuel)
- Thread-safe : ConcurrentLinkedQueue sans verrou drainée par un thread d'écriture
- Appelle aussi `android.util.Log.*` pour la sortie logcat standard

### Relais multi-touch

Les événements tactiles arrivent de la voiture via la connexion entrée en tant que CMD_INPUT_TOUCH (0x32) avec données MotionEvent brutes. Le `handleInputFrame` est distribué sur `Dispatchers.IO` (pas Main) pour permettre les écritures socket localhost. Le téléphone diffuse les événements DOWN/MOVE/UP avec pointerId directement au serveur VD, qui gère la construction complète du MotionEvent avec tous les pointeurs actifs.

## Permissions requises

| Permission | Rôle |
|-----------|---------|
| MANAGE_EXTERNAL_STORAGE | Accès Tous les Fichiers pour le déploiement du JAR VD sur sdcard |
| Service Accessibilité | Injection tactile sur l'écran virtuel via dispatchGesture (pas de surveillance d'événements) |
| Accès Notifications | Transférer les notifications à la voiture (avec progression) |
| API Shizuku | Accès shell élevé pour le déploiement du serveur VD sans ADB et l'auto-mise à jour silencieuse |
| QUERY_ALL_PACKAGES | Grille du lanceur d'applications |
| REQUEST_INSTALL_PACKAGES | Auto-mise à jour de l'app voiture via dadb |

## Dépendances

- Jetpack Compose + Material 3
- kotlinx-coroutines
- dadb 1.2.10 (WiFi ADB pour la mise à jour auto voiture)
- Shizuku api/provider/aidl 13.1.5
- Module Protocol (partagé avec l'application voiture)
