# Application serveur voiture (app-server)

## Aperçu

L'application serveur voiture s'exécute sur le système d'infodivertissement BYD DiLink. Elle utilise un **modèle de connexion parallèle** avec des pistes WiFi (3 connexions dédiées) et USB fonctionnant simultanément :

1. Piste A (WiFi) : IP passerelle + découverte mDNS, connexion contrôle (9637), handshake avec le téléphone
2. Après handshake : connexions vidéo (9638) + entrée (9639) ouvertes en parallèle
3. Piste B (USB) : scan des périphériques, connexion USB ADB (avec diagnostics logSink), lancement de l'app téléphone
4. `checkAndAdvance()` évalue l'état quand un prérequis change
5. Reçoit la vidéo H.264 et l'affiche sur l'écran de la voiture (démarrage anticipé du décodeur sur surface hors écran)
6. Capture les événements tactiles et les envoie au téléphone via la connexion entrée pour l'injection d'entrée VD

États : IDLE → CONNECTING → CONNECTED → STREAMING

L'APK voiture est intégré dans l'APK téléphone. Le téléphone met à jour automatiquement l'application voiture via dadb lorsqu'une différence de version est détectée pendant le handshake. La voiture reçoit le message `UPDATING_CAR` et affiche le statut au lieu de reconnecter aveuglément.

### UI à deux modes

L'application voiture sépare le flux de connexion de l'expérience de streaming en deux modes distincts, correspondant à l'approche de l'application téléphone :

- **Mode lancement** (`CarLaunchScreen`) : Plein écran, centré sur la connexion. Affiche la marque, des instructions de connexion étape par étape, le statut de connexion et la saisie manuelle d'IP. Pas de barre de navigation — tout l'écran est dédié à l'établissement de la connexion. Affiché au démarrage de l'app voiture et jusqu'à ce que les icônes d'applications soient reçues du téléphone via la connexion contrôle.

- **Mode streaming** (`CarShell` avec `PersistentNavBar`) : La disposition familière avec barre de navigation gauche (notifications, accueil, retour, apps récentes) et zone de contenu (grille d'apps, vue miroir, notifications). Affiché une fois que `appList` est non vide et que l'état est CONNECTED ou STREAMING.

Le déclencheur de transition : quand le téléphone envoie `APP_LIST` via la connexion contrôle et que l'état de connexion atteint CONNECTED/STREAMING, l'UI passe du mode lancement au mode streaming.

## Composants

### CarConnectionService

Service foreground gérant le cycle de vie complet de la connexion avec une machine d'état parallèle à prérequis et 3 connexions dédiées.

**Architecture 3 connexions :**
- `controlConnection` : handshake, heartbeat, commandes d'app, canal DATA (liste d'apps, notifications, logs voiture)
- `videoConnection` : trames vidéo H.264 uniquement (téléphone → voiture)
- `inputConnection` : événements tactiles uniquement (voiture → téléphone)
- Heartbeat/watchdog sur la connexion contrôle uniquement ; vidéo et entrée sans surcoût heartbeat
- Toute connexion mourante cascade → destruction complète de la session

**Architecture pistes parallèles :**
- `connectionScope` : Job parent pour toutes les coroutines de découverte, annulé à la déconnexion
- Les pistes A et B fonctionnent simultanément
- `checkAndAdvance()` évalue l'état global quand un prérequis change
- Déconnexion utilisateur : reste IDLE, pas de reconnexion automatique (persisté dans SharedPreferences)

**Piste A — WiFi :**
- Découverte : IP passerelle (hotspot/LAN, réessaye toutes les 3s) → mDNS → IP manuelle
- Connexion contrôle : connexion NIO non-bloquante SocketChannel vers TCP:9637 du téléphone
- Handshake : envoie dimensions viewport + DPI + appVersionCode + targetFps (60) → reçoit infos téléphone + vdServerJarPath
- À la réponse handshake : ouvre les connexions vidéo (9638) + entrée (9639) en parallèle, définit `wifiReady = true` après établissement des 3
- Vidéo : reçoit les trames H.264 via la connexion vidéo, les distribue à VideoDecoder
- Tactile : exécuteur single-thread dédié, `sendTouchEvent()` / `sendTouchBatch()` via la connexion entrée
- Heartbeat : intervalle 3s, timeout watchdog 10s (connexion contrôle uniquement)
- Backoff : délai exponentiel sur les échecs de reconnexion

**Piste B — USB :**
- Enregistre `BroadcastReceiver` pour `USB_DEVICE_ATTACHED` / `USB_DEVICE_DETACHED`
- `MainActivity` transmet les intents USB au service
- Scan des périphériques USB pour l'interface ADB
- Connexion USB ADB via UsbAdbConnection (dans le module protocol/), avec `logSink` acheminant tous les logs d'auth ADB vers le FileLog du téléphone
- Lance l'application téléphone : `am start -n com.dilinkauto.client/.MainActivity`

**Flux de mise à jour :**
- Si le téléphone envoie `UPDATING_CAR`, la voiture définit `updatingFromPhone = true`, affiche "Mise à jour de l'application voiture..."
- Saute les tentatives de connexion vidéo/entrée et la boucle de reconnexion pendant la mise à jour
- Après `pm install -r`, l'application voiture redémarre à neuf

**Flux d'état :**
- `_state`, `_phoneName`, `_appList`, `_notifications`, `_mediaMetadata`, `_playbackState` : état principal exposé à l'UI
- `_videoReady` : true quand la première trame vidéo non-config arrive
- `_statusMessage` : statut lisible pour l'affichage UI
- `_vdStackEmpty` (SharedFlow) : émis quand le téléphone signale que le VD n'a pas d'activités (déclenche la navigation vers l'accueil)

**Lancement du serveur VD :**
- `deployVdServer()` : `shellNoWait` avec CLASSPATH du vdServerJarPath du handshake
- Args : `W H DPI PORT EW EH FPS` — FPS transmis depuis le targetFps du handshake

**Démarrage anticipé du décodeur :**
- À la première trame vidéo CONFIG, démarre VideoDecoder sur SurfaceTexture hors écran (avant MirrorScreen)
- `onSurfaceTextureAvailable` de MirrorScreen arrête et redémarre le décodeur avec la vraie surface TextureView
- Empêche la perte d'images clés pendant le délai de composition UI

**Routage des logs voiture :**
- `carLogSend()` achemine tous les logs côté voiture via le canal DATA `CAR_LOG` vers le téléphone
- `videoDecoder.logSink` et `adb.setLogSink()` connectent les logs de VideoDecoder et UsbAdbConnection via le même chemin
- Tous les logs visibles dans `/sdcard/DiLinkAuto/client.log` du téléphone

### VideoDecoder

Décodeur H.264 utilisant MediaCodec avec sortie Surface (rendu direct GPU).

- File de trames : 15 trames — absorbe la course au démarrage et la gigue réseau
- `onFrameReceived()` : met en file les trames même avant l'appel de `start()`
- `start()` : alimente d'abord le CONFIG en cache, puis draine la file
- Suppression du plus ancien quand la file est pleine : préfère supprimer les P-frames, évince les P-frames en file pour les keyframes/CONFIG
- `KEY_LOW_LATENCY = 1`, `KEY_PRIORITY = 0` pour un délai de décodage minimal
- CONFIG (SPS/PPS) mis en cache et rejoué au redémarrage du décodeur
- Propriété `isRunning` pour la coordination du démarrage anticipé
- Callback `logSink` achemine tous les logs du décodeur vers le téléphone via carLogSend
- Mode catchup : quatre zones de vitesse graduées basées sur la profondeur de file — normale (0-6 trames), douce 1.5x (7-12 trames, saute 1 sur 3 non-keyframes), moyenne 2x (13-20 trames, saute 1 sur 2), agressive 3x (21+ trames, saute 2 sur 3). Les keyframes ne sont jamais sautées.
- Vide le codec et réalimente le CONFIG après 10+ échecs consécutifs de dequeueInputBuffer

### ServerApp

Classe Application. Crée le canal de notification `dilinkauto_car_service` avec `IMPORTANCE_LOW`.

### RemoteAdbController

Client ADB direct utilisant la bibliothèque dadb. Fournit tap, swipe, retour, accueil et lancement d'app via commandes shell sur le virtual display. Utilisé comme chemin d'entrée alternatif.

### CarLaunchScreen

Composable plein écran centré sur la connexion, affiché avant l'établissement de la connexion téléphone — pas de barre nav, pas de grille d'apps.

- Image de marque DiLink Auto (icône, titre, slogan)
- Carte de statut de connexion avec point indicateur coloré (vert=streaming, orange=connecté/en connexion, gris=inactif) et texte de statut en direct
- Instructions "Comment connecter" : 4 étapes numérotées (activer hotspot, brancher USB, ouvrir app téléphone, attendre connexion auto)
- Saisie manuelle d'IP pour connexion directe
- Remplacé par la disposition mode streaming quand `appList` devient non vide et que l'état atteint CONNECTED/STREAMING

### PersistentNavBar

Barre de navigation gauche 76dp — **affichée uniquement en mode streaming** — avec :
- Affichage horloge (HH:mm, mise à jour chaque seconde)
- Bouton d'éjection (déconnecte et persiste la préférence utilisateur)
- Indicateur de statut réseau
- Bouton notifications avec badge de compteur non lu
- Bouton accueil
- Bouton retour
- Icônes d'apps récentes (max 5, élaguées quand les apps deviennent indisponibles)
- Icônes 40dp, texte 12-14sp

Largeur calculée pour garantir un viewport pair pour l'encodeur H.264.

### NotificationScreen

- Liste de notifications triée par horodatage (plus récentes en premier)
- Dédoublonnage par ID : les mises à jour remplacent l'existant (gère les notifications de progression)
- Barres de progression : déterminées (remplies) et indéterminées (tournantes)
- Appui pour lancer : toucher une notification lance l'application propriétaire sur le VD et bascule en vue miroir

### Grille d'applications (HomeContent)

Affichée comme zone de contenu principale quand le mode streaming est actif et que l'écran actuel est HOME :
- Champ de recherche avec `imePadding()` — le clavier ne pousse pas l'activité, seule la barre de recherche bouge
- `windowSoftInputMode="adjustNothing"` dans le manifest
- Icônes d'app 64dp dans cellules de grille adaptatives 160dp
- Texte du nom d'app : bodyLarge
- Tri alphabétique
- Saisie manuelle d'IP
- Statut de connexion

### LauncherScreen (Legacy)

Disposition de lanceur intégré complet avec `CarStatusBar`, `SideNavBar` (80dp) et `AppGrid`. Non utilisée dans le routage actuel de `CarShell` — l'UI active utilise les composables `PersistentNavBar` + `HomeContent`/`MirrorContent`/`NotificationContent` en ligne.

### RecentAppsState

Suit les applications récemment lancées (max 5), persistées dans SharedPreferences. `pruneUnavailable()` supprime les apps qui ne sont plus présentes lors de la mise à jour de la liste d'applications.

### NavBarComponents

Composables de widgets de barre de navigation individuels : `ClockDisplay` (mise à jour chaque seconde), `NetworkInfo` (état connecté/déconnecté), `RecentAppIcon` (40dp, avec surbrillance d'état actif), `NavActionButton` (icônes 40dp, étiquettes 12sp).

### CarTheme

Schéma de couleurs sombres Material3 (`CarDark`) avec couleurs de tuiles d'app par catégorie : Navigation (vert), Musique (rose), Communication (bleu), Autre (gris).

### Infos écran voiture

Testé sur BYD DiLink 3.0 :
- Écran : 1920x990 @ 240dpi
- Viewport : ~1806x990 (après barre nav 76dp)
- VD : ~3282x1800 @ 480dpi (réduit par GPU à ~1806x990 pour l'encodage)

## Dépendances

- Jetpack Compose + Material 3
- Module Protocol (partagé avec l'application téléphone, inclut UsbAdbConnection + AdbProtocol + VideoConfig)
- dadb 1.2.10 (solution de repli TCP ADB)
