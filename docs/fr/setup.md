# Guide d'installation

## Prérequis

- **Téléphone :** Tout appareil Android 10+ avec Débogage USB activé
- **Voiture :** BYD DiLink 3.0+ (ou toute unité principale Android 10+ avec port hôte USB)
- **Câble USB :** Câble du téléphone vers le port USB de la voiture
- **Développement :** Android Studio ou Gradle, JDK 17

## Configuration du téléphone (Unique)

1. **Installez DiLink Auto Client** : `adb install app-client-debug.apk`
2. **Ouvrez l'application** — l'écran d'intégration vous guidera à travers chaque autorisation :
   - **Accès à tous les fichiers** — déploie le serveur d'affichage virtuel sur le stockage
   - **Optimisation de la batterie** — maintient le streaming actif lorsque l'écran est éteint
   - **Service d'accessibilité** — active le contrôle de l'écran tactile de la voiture
   - **Accès aux notifications** — transmet les notifications du téléphone à l'écran de la voiture
3. Chaque étape ouvre les paramètres système correspondants. Accordez l'autorisation, puis appuyez sur Retour pour continuer.
4. Vous pouvez sauter n'importe quelle étape et la configurer plus tard depuis l'écran principal.

C'est tout. Pas de Débogage sans fil, pas de codes d'appairage, pas de configuration WiFi spéciale.

## Configuration de la voiture

Aucune installation manuelle sur la voiture n'est nécessaire. L'APK de la voiture est intégré dans l'APK du téléphone. Lors de la première connexion (ou en cas d'incompatibilité de version), le téléphone envoie `UPDATING_CAR` à la voiture (qui affiche le statut « Mise à jour... »), puis effectue l'installation automatique via dadb (WiFi ADB).

Vous pouvez également utiliser le bouton « Installer sur la voiture » dans l'application du téléphone pour envoyer manuellement l'APK de la voiture.

En cas d'installation manuelle : `adb install app-server-debug.apk` (nécessite un accès ADB à la voiture).

## Utilisation quotidienne

1. **Branchez le téléphone au port USB de la voiture** — l'application de la voiture se lance automatiquement (ou connectez-vous via WiFi sur le même réseau)
2. La voiture et le téléphone se connectent automatiquement via les pistes parallèles WiFi + USB
3. Le téléphone déploie le serveur VD et le streaming commence à 60fps
4. Utilisez l'écran tactile de la voiture pour interagir avec les applications du téléphone

## Compilation

```bash
# Compiler l'APK du téléphone (déclenche la compilation du serveur + intégration automatiquement)
./gradlew :app-client:assembleDebug

# Emplacement de l'APK :
# app-client/build/outputs/apk/debug/app-client-debug.apk  (téléphone -- inclut l'APK de la voiture intégré)
```

Le système de compilation compile automatiquement app-server et l'intègre dans app-client, de sorte qu'un seul APK doit être installé manuellement.

## Fonctionnement

Lorsque le téléphone est connecté à la voiture :

1. **La voiture démarre les pistes parallèles** — la découverte WiFi et la détection USB s'exécutent simultanément
2. **Piste A (WiFi) :** IP de la passerelle + découverte mDNS → connexion NIO au port de contrôle du téléphone (9637)
3. **Piste B (USB) :** analyse des périphériques → connexion USB ADB → lancement de l'application du téléphone via `am start`
4. **Handshake :** la voiture envoie viewport + DPI + appVersionCode + targetFps ; le téléphone envoie les informations de l'appareil + vdServerJarPath
5. **Vérification de version :** le téléphone compare appVersionCode — en cas d'incompatibilité, envoie le message UPDATING_CAR à la voiture, puis met à jour automatiquement via dadb
6. **Configuration à 3 connexions :** la voiture ouvre les connexions vidéo (9638) + entrée (9639) après le handshake
7. **Le téléphone déploie le serveur VD** — extrait vd-server.jar vers `/sdcard/DiLinkAuto/`, démarre `app_process` en tant qu'UID shell avec l'argument FPS
8. **Le serveur VD se connecte en sens inverse** au téléphone sur localhost:19637 (NIO non bloquant)
9. **Le serveur VD crée un VirtualDisplay** au DPI natif du téléphone (480dpi) avec réduction d'échelle GPU et redessin périodique
10. **Streaming vidéo** via WiFi TCP (connexion vidéo 9638) — H.264, profil Main, 8Mbps CBR, configurable jusqu'à 60fps

## Dépannage

### La boîte de dialogue d'authentification ADB apparaît à chaque fois (CORRIGÉ dans la v0.13.1)
Corrigé — le problème était le double hachage du AUTH_TOKEN. ADB envoie un jeton brut de 20 octets qui doit être traité comme un condensé SHA-1 pré-calculé. L'ancien code utilisait `SHA1withRSA` qui le hachait à nouveau. Il utilise maintenant `NONEwithRSA` avec le préfixe SHA-1 DigestInfo (pré-calculé), correspondant à `RSA_sign(NID_sha1)` d'AOSP. Le téléphone accepte AUTH_SIGNATURE lors de la reconnexion et « Toujours autoriser » persiste correctement.

Si la boîte de dialogue apparaît encore lors de la première connexion après la mise à jour, cochez « Toujours autoriser » — elle ne devrait plus apparaître lors des connexions suivantes. Si elle persiste, vérifiez dans les Options pour développeurs du téléphone l'option « Désactiver le délai d'autorisation ADB » (Android 11+).

### L'application du téléphone ne se lance pas
- Assurez-vous que le Débogage USB est activé sur le téléphone
- Vérifiez que le port USB de la voiture prend en charge le mode hôte (tous les ports ne le font pas)
- Essayez un autre câble USB (certains câbles servent uniquement au chargement)

### Autorisation d'accès à tous les fichiers refusée
- L'application du téléphone a besoin de MANAGE_EXTERNAL_STORAGE pour déployer vd-server.jar vers `/sdcard/DiLinkAuto/`
- Allez dans Paramètres -> Applications -> DiLink Auto -> Autorisations -> Accès à tous les fichiers -> ACTIVER

### La vidéo ne diffuse pas / écran noir
- Assurez-vous que le téléphone et la voiture sont sur le même réseau
- Vérifiez que les deux applications sont en cours d'exécution (le téléphone affiche « Streaming », la voiture affiche la vidéo)
- Le serveur VD peut avoir besoin d'un moment pour démarrer — attendez 5 à 10 secondes après la connexion
- Le redessin périodique du SurfaceScaler devrait produire des images même sur du contenu statique
- Vérifiez `/sdcard/DiLinkAuto/client.log` pour des informations de diagnostic

### Coupures de connexion
- Auparavant causé par des déconnexions réseau non liées (basculement des données mobiles) déclenchant une déconnexion proactive
- Corrigé dans la v0.12.5 : le callback réseau intelligent ignore les déconnexions sur les réseaux qui ne transportent pas la connexion
- Si le problème persiste, vérifiez `/sdcard/DiLinkAuto/client.log` pour les entrées « Network lost »

### L'application de la voiture ne se met pas à jour
- Le téléphone met automatiquement à jour l'application de la voiture lorsqu'une incompatibilité de version est détectée lors du handshake
- La voiture affiche le statut « Mise à jour de l'application voiture... » pendant la mise à jour
- Vous pouvez également déclencher manuellement une mise à jour avec le bouton « Installer sur la voiture » dans l'application du téléphone
- Assurez-vous que dadb peut atteindre la voiture via WiFi ADB

### Journaux
- Journaux du téléphone : `/sdcard/DiLinkAuto/client.log` (session en cours)
- Sessions précédentes : `/sdcard/DiLinkAuto/client-YYYYMMDD-HHmmss.log`
- Journaux du serveur VD : `/data/local/tmp/vd-server.log` (sur le téléphone, lisibles via ADB)
- Journaux de la voiture : acheminés vers client.log du téléphone via le canal DATA du protocole (tag : `CarLog`)
- Récupérer les journaux : `adb shell "cat /sdcard/DiLinkAuto/client.log"`

## Conseils pour HyperOS (Xiaomi)

Pour un fonctionnement fiable sur HyperOS :
1. Paramètres -> Applications -> DiLink Auto -> Démarrage automatique -> Activer
2. Paramètres -> Batterie -> DiLink Auto -> Aucune restriction
3. Épinglez l'application dans les Applications récentes (appui long sur la fiche -> Épingler)
