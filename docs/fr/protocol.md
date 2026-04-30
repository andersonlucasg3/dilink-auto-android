# Spécification du protocole

## Aperçu

DiLink-Auto utilise un protocole binaire personnalisé sur **3 connexions TCP dédiées** entre le téléphone et la voiture :

| Connexion | Port | Direction | Contenu |
|------------|------|-----------|---------|
| **Contrôle** | 9637 | Bidirectionnel | Handshake, heartbeat, commandes d'app, DATA (liste d'apps, notifications, logs voiture, média) |
| **Vidéo** | 9638 | Téléphone → Voiture | H.264 CONFIG + FRAME uniquement |
| **Entrée** | 9639 | Voiture → Téléphone | Événements tactiles uniquement |

Chaque connexion a sa propre socket, NioReader et file d'écriture — l'isolation complète des I/O empêche les blocages vidéo dus au trafic non-vidéo (par ex., charges utiles volumineuses de liste d'applications).

La connexion de contrôle est établie en premier (le handshake a lieu ici). Après le handshake, la voiture ouvre les connexions vidéo et entrée en parallèle. Le téléphone accepte les trois et les associe comme une seule session. Le heartbeat/watchdog s'exécute uniquement sur la connexion de contrôle ; les connexions vidéo et entrée n'ont pas de surcoût heartbeat. Toute connexion mourante cascade vers la destruction complète de la session.

Un protocole interne séparé fonctionne entre l'application téléphone et le serveur VD sur `localhost:19637` (documenté dans [client.md](./client.md)). Le serveur VD se connecte en retour au téléphone (le téléphone écoute, le serveur VD se connecte). Le serveur VD utilise NIO entièrement non-bloquant pour les lectures et écritures sur la socket localhost.

## Format binaire

```
+---------------+------------+--------------+-----------------+
| Longueur trame | ID canal   | Type message | Charge utile     |
| (4 octets)     | (1 octet)  | (1 octet)    | (N octets)       |
| big-endian     |            |              |                  |
+---------------+------------+--------------+-----------------+
```

- **Longueur trame** : `uint32` big-endian. Valeur = `2 + taille_charge_utile`.
- **Charge utile max** : 128 Mo.
- **Surcoût d'en-tête** : 6 octets par trame.

## Canaux

| ID | Nom | Connexion | Direction | Rôle |
|----|------|------------|-----------|---------|
| 0 | CONTROL | Contrôle (9637) | Bidirectionnel | Handshake, heartbeat, commandes d'app, signaux serveur VD |
| 1 | VIDEO | Vidéo (9638) | Téléphone → Voiture | Trames vidéo encodées H.264 (relayées du serveur VD) |
| 2 | AUDIO | (réservé) | Téléphone → Voiture | Réservé (non implémenté) |
| 3 | DATA | Contrôle (9637) | Bidirectionnel | Notifications, liste d'apps, métadonnées média, logs voiture |
| 4 | INPUT | Entrée (9639) | Voiture → Téléphone | Événements tactiles (CMD_INPUT_TOUCH pour injection MotionEvent brute) |

## Canal Contrôle (0x00)

### HANDSHAKE_REQUEST (0x01) -- Voiture -> Téléphone

```
+----------------------+------+
| protocolVersion       | int32 |
| longueur deviceName   | int16 |
| deviceName            | UTF-8 |
| screenWidth           | int32 |  largeur écran voiture en pixels (après barre nav)
| screenHeight          | int32 |  hauteur écran voiture en pixels
| supportedFeatures     | int32 |  masque de bits
| displayMode           | byte  |  0=MIRROR, 1=VIRTUAL (défaut)
| screenDpi             | int32 |  densité écran voiture (ex. 240)
| appVersionCode        | int32 |  code version app voiture (legacy, pour compatibilité ascendante)
| targetFps             | int32 |  FPS demandé par la voiture (ex. 60)
| longueur appVersionName | int16 |  longueur de la chaîne versionName
| appVersionName        | UTF-8 |  nom de version app voiture (ex. "0.16.0")
+----------------------+------+
```

### HANDSHAKE_RESPONSE (0x02) -- Téléphone -> Voiture

```
+----------------------+------+
| protocolVersion       | int32 |
| accepted              | byte  |  1=accepté, 0=rejeté
| longueur deviceName   | int16 |
| deviceName            | UTF-8 |
| displayWidth          | int32 |  largeur VD (correspond à la demande voiture)
| displayHeight         | int32 |  hauteur VD (correspond à la demande voiture)
| virtualDisplayId      | int32 |  -1 (défini par le serveur VD, pas le téléphone)
| adbPort               | int32 |  5555 (port TCP ADB du téléphone)
| vdServerJarPath       | UTF-8 |  chemin du JAR VD déployé sur le téléphone (ex. /sdcard/DiLinkAuto/vd-server.jar)
+----------------------+------+
```

### HEARTBEAT (0x03) / HEARTBEAT_ACK (0x04) -- Connexion contrôle uniquement

Charge utile vide. Envoyé toutes les 3 secondes sur la connexion de contrôle. Si aucune trame n'est reçue dans les 10 secondes, la connexion est considérée comme morte (timeout watchdog). Les connexions vidéo et entrée n'ont pas de heartbeat.

### DISCONNECT (0x05) -- Bidirectionnel

Charge utile vide. Arrêt gracieux.

### APP_STOPPED (0x14) -- Téléphone -> Voiture

Charge utile vide. Envoyé quand une application sur le virtual display est arrêtée.

### VD_SERVER_READY (0x20) -- Voiture -> Téléphone

Charge utile vide. La voiture confirme que le processus serveur VD est en cours d'exécution sur le téléphone.

### LAUNCH_APP (0x10) -- Voiture -> Téléphone

```
+----------------------+------+
| packageName           | UTF-8 |  octets bruts, pas de préfixe de longueur
+----------------------+------+
```

Le téléphone transmet au serveur VD qui exécute `am start --display <id> -n <component>` (pas de `--activity-clear-task` — les apps existantes reprennent).

### GO_HOME (0x11) / GO_BACK (0x12) -- Voiture -> Téléphone

Charge utile vide. Le téléphone transmet au serveur VD. GO_BACK envoie `input -d <id> keyevent 4`, puis vérifie la pile vide. GO_HOME est un no-op (la voiture gère la navigation du lanceur).

### APP_STARTED (0x13) -- Téléphone -> Voiture

Même format que LAUNCH_APP. Confirme que l'application a été lancée.

### VD_STACK_EMPTY (0x15) -- Téléphone -> Voiture

Charge utile vide. Envoyé après GO_BACK quand le serveur VD détecte qu'il ne reste plus de tâches d'application sur le virtual display (via `dumpsys activity activities`). La voiture utilise cela pour passer de la vue miroir à l'écran d'accueil.

### UPDATING_CAR (0x30) -- Téléphone -> Voiture

Charge utile vide. Envoyé avant que le téléphone ne commence la mise à jour automatique de l'application voiture. La voiture affiche "Mise à jour de l'application voiture..." et arrête la reconnexion. Après la mise à jour, l'application voiture redémarre à neuf.

## Canal Vidéo (0x01)

### CONFIG (0x01) -- Téléphone -> Voiture

Unités NAL SPS/PPS H.264 avec codes de démarrage. Envoyé une fois au démarrage de l'encodeur.

### FRAME (0x02) -- Téléphone -> Voiture

Unités NAL H.264 représentant une trame vidéo.

**Paramètres d'encodage** (définis par le serveur VD, configurables via handshake) :
- Codec : H.264/AVC
- Profil : High
- Résolution : dimensions du viewport voiture (ex., 1806x990)
- Bitrate : 8 Mbps CBR
- Fréquence d'images : configurable via `targetFps` dans le handshake (défaut 30, la voiture demande 60)
- Intervalle IDR : 1 seconde
- SurfaceScaler : redessin périodique toutes les `1000/fps` ms assure la sortie encodeur sur contenu statique

## Canal Data (0x03)

### NOTIFICATION_POST (0x01) / NOTIFICATION_REMOVE (0x02) -- Téléphone -> Voiture

Données de notification avec id, packageName, appName, title, text, timestamp, progressIndeterminate (byte), progress (int32), progressMax (int32). La voiture dédoublonne par ID (les mises à jour remplacent l'existant). Toucher une notification lance l'application propriétaire sur le VD.

### APP_LIST (0x03) -- Téléphone -> Voiture

Liste des applications installées avec packageName, appName, catégorie (NAV/MUSIC/COMM/OTHER), iconPng (PNG 96x96).

### CAR_LOG (0x30) -- Voiture -> Téléphone

Ligne de texte UTF-8. La voiture achemine tous les logs (y compris VideoDecoder et UsbAdbConnection) via ce canal. Le téléphone écrit dans FileLog (`/sdcard/DiLinkAuto/client.log`) avec le tag `CarLog`.

### MEDIA_METADATA (0x10) / MEDIA_PLAYBACK_STATE (0x11) -- Téléphone -> Voiture

Infos de piste et état de lecture. Pas encore activement peuplé.

### MEDIA_ACTION (0x12) -- Voiture -> Téléphone

Contrôle média (lecture/pause/suivant/précédent). Pas encore connecté à MediaSession.

### NAVIGATION_STATE (0x20) -- Téléphone -> Voiture

Données d'état de navigation. Réservé pour l'intégration du widget de navigation.

## Canal Entrée (0x04)

### TOUCH_DOWN (0x01) -- Voiture -> Téléphone

Événement d'appui à un seul pointeur. La charge utile est un TouchEvent (25 octets) :

```
+----------------------+------+
| action                | byte  |  InputMsg.TOUCH_DOWN (0x01)
| pointerId             | int32 |  ID pointeur multi-touch
| x                     | float |  normalisé 0.0-1.0
| y                     | float |  normalisé 0.0-1.0
| pressure              | float |
| timestamp             | int64 |
+----------------------+------+
```

### TOUCH_MOVE (0x02) -- Voiture -> Téléphone

Événement de déplacement à un seul pointeur. Même format de charge TouchEvent que TOUCH_DOWN.

### TOUCH_UP (0x03) -- Voiture -> Téléphone

Événement de relâchement à un seul pointeur. Même format de charge TouchEvent que TOUCH_DOWN.

### TOUCH_MOVE_BATCH (0x04) -- Voiture -> Téléphone

```
+----------------------+------+
| count                 | byte  |  nombre de pointeurs
| N × pointeur :        |       |
|   pointerId           | int32 |
|   x                   | float |  normalisé 0.0-1.0
|   y                   | float |  normalisé 0.0-1.0
|   pressure            | float |
|   timestamp           | int64 |
+----------------------+------+
```

Événements MOVE groupés — tous les pointeurs actifs dans un seul message. Réduit les appels système pour les gestes multi-touch.

### KEY_EVENT (0x10) -- Voiture -> Téléphone

Événement de touche (par ex., touches média, touches de navigation). Réservé pour usage futur.

## Constantes

```
APP_VERSION_COMPARISON = versionName via semver (avec appVersionCode en solution de repli pour véhicules plus anciens)
PROTOCOL_VERSION      = 1
CONTROL_PORT          = 9637 (téléphone <-> voiture, handshake + heartbeat + commandes + données)
VIDEO_PORT            = 9638 (téléphone -> voiture, trames H.264 uniquement)
INPUT_PORT            = 9639 (voiture -> téléphone, événements tactiles uniquement)
VD_SERVER_PORT        = 19637 (téléphone <-> serveur VD, localhost uniquement, NIO non-bloquant)
TARGET_FPS            = 60 (configurable via handshake, défaut 30)
FRAME_INTERVAL_MS     = 1000 / TARGET_FPS (16ms à 60fps, attente max pour les boucles du chemin vidéo)
HEARTBEAT_INTERVAL    = 3000 ms (connexion contrôle uniquement)
HEARTBEAT_TIMEOUT     = 10000 ms (connexion contrôle uniquement)
MAX_PAYLOAD_SIZE      = 134 217 728 octets (128 Mo)
SERVICE_TYPE (mDNS)   = "_dilinkauto._tcp."
DISPLAY_MODE_MIRROR   = 0
DISPLAY_MODE_VIRTUAL  = 1
```

## Ordre des octets

Tous les entiers et flottants multi-octets sont en **big-endian**.
