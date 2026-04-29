# Guide de configuration

## Prérequis

- **Téléphone :** Tout appareil Android 10+ avec débogage USB activé
- **Voiture :** BYD DiLink 3.0+ (ou tout autoradio Android 10+ avec port hôte USB)
- **Câble USB :** Du téléphone au port USB de la voiture
- **Développement :** Android Studio ou Gradle, JDK 17

## Configuration du téléphone (unique)

1. **Installez DiLink Auto Client** : `adb install app-client-debug.apk`
2. **Ouvrez l'application** — l'écran d'intégration vous guidera à travers chaque permission :
   - **Accès à tous les fichiers** — déploie le serveur d'affichage virtuel dans le stockage
   - **Optimisation de la batterie** — maintient la diffusion lorsque l'écran est éteint
   - **Service d'accessibilité** — active le contrôle tactile depuis la voiture
   - **Accès aux notifications** — transmet les notifications du téléphone vers l'écran de la voiture
3. Chaque étape ouvre les paramètres système correspondants. Accordez la permission, puis appuyez sur Retour pour continuer.
4. Vous pouvez sauter n'importe quelle étape et la configurer plus tard depuis l'écran principal.

C'est tout. Pas de débogage sans fil, pas de codes d'appairage, pas de configuration WiFi spéciale.

## Configuration de la voiture

Aucune installation manuelle n'est nécessaire sur la voiture. L'APK de la voiture est intégré dans l'APK du téléphone. Lors de la première connexion (ou en cas de différence de version), le téléphone envoie `UPDATING_CAR` à la voiture (qui affiche le statut "Mise à jour..."), puis installe automatiquement via dadb (WiFi ADB).

Vous pouvez également utiliser le bouton "Installer sur la voiture" dans l'application du téléphone pour envoyer manuellement l'APK de la voiture.

Pour une installation manuelle : `adb install app-server-debug.apk` (nécessite un accès ADB à la voiture).

## Utilisation quotidienne

1. **Branchez le téléphone au port USB de la voiture** — l'application voiture se lance automatiquement (ou connectez-vous via WiFi sur le même réseau)
2. La voiture et le téléphone se connectent automatiquement via les pistes parallèles WiFi + USB
3. Le téléphone déploie le serveur VD et la diffusion commence à 60 ips
4. Utilisez l'écran tactile de la voiture pour interagir avec les applications du téléphone

## Compilation

```bash
# Compiler l'APK du téléphone (déclenche automatiquement la compilation et l'intégration du serveur)
./gradlew :app-client:assembleDebug

# Emplacement de l'APK :
# app-client/build/outputs/apk/debug/app-client-debug.apk  (téléphone -- inclut l'APK voiture intégré)
```

Le système de compilation compile automatiquement app-server et l'intègre dans app-client, donc un seul APK doit être installé manuellement.

## Fonctionnement

Lorsque le téléphone est connecté à la voiture :

1. **La voiture démarre des pistes parallèles** — découverte WiFi et détection USB simultanées
2. **Piste A (WiFi) :** IP passerelle + découverte mDNS → connexion NIO au port de contrôle du téléphone (9637)
3. **Piste B (USB) :** scan des périphériques → connexion USB ADB → lancement de l'appli téléphone via `am start`
4. **Handshake :** la voiture envoie viewport + DPI + appVersionCode + targetFps ; le téléphone envoie les infos appareil + vdServerJarPath
5. **Vérification de version :** le téléphone compare appVersionCode — si différent, envoie un message UPDATING_CAR à la voiture, puis met à jour automatiquement via dadb
6. **Configuration 3 connexions :** la voiture ouvre les connexions vidéo (9638) + entrée (9639) après le handshake
7. **Le téléphone déploie le serveur VD** — extrait vd-server.jar vers `/sdcard/DiLinkAuto/`, lance `app_process` en tant que shell UID avec argument FPS
8. **Le serveur VD se connecte en sens inverse** au téléphone sur localhost:19637 (NIO non bloquant)
9. **Le serveur VD crée un VirtualDisplay** au DPI natif du téléphone (480 dpi) avec réduction GPU et redessin périodique
10. **La vidéo est diffusée** via WiFi TCP (connexion vidéo 9638) — H.264, profil Main, 8 Mbps CBR, configurable jusqu'à 60 ips

## Dépannage

### La boîte de dialogue d'authentification ADB apparaît à chaque fois (CORRIGÉ dans v0.13.1)
Corrigé — le problème était un double hachage du AUTH_TOKEN. ADB envoie un jeton brut de 20 octets qui doit être traité comme un condensé SHA-1 pré-haché. L'ancien code utilisait `SHA1withRSA` qui le hachait à nouveau. Il utilise maintenant `NONEwithRSA` avec le préfixe SHA-1 DigestInfo (pré-haché), correspondant à `RSA_sign(NID_sha1)` d'AOSP. Le téléphone accepte AUTH_SIGNATURE lors de la reconnexion et "Toujours autoriser" persiste correctement.

Si la boîte de dialogue apparaît encore lors de la première connexion après la mise à jour, cochez "Toujours autoriser" — elle ne devrait plus apparaître. Si elle persiste, vérifiez dans les options développeur "Désactiver le délai d'autorisation ADB" (Android 11+).

### L'application téléphone ne se lance pas
- Assurez-vous que le débogage USB est activé sur le téléphone
- Vérifiez que le port USB de la voiture prend en charge le mode hôte (tous les ports ne le font pas)
- Essayez un autre câble USB (certains câbles ne servent qu'à la charge)

### Permission Accès à tous les fichiers refusée
- L'application téléphone a besoin de MANAGE_EXTERNAL_STORAGE pour déployer vd-server.jar dans `/sdcard/DiLinkAuto/`
- Allez dans Paramètres → Apps → DiLink Auto → Autorisations → Accès à tous les fichiers → ACTIVÉ

### La vidéo ne s'affiche pas / écran noir
- Assurez-vous que le téléphone et la voiture sont sur le même réseau
- Vérifiez que les deux applications sont en cours d'exécution (le téléphone affiche "Diffusion", la voiture affiche la vidéo)
- Le serveur VD peut avoir besoin d'un moment pour démarrer — attendez 5 à 10 secondes après la connexion
- Le SurfaceScaler avec redessin périodique devrait produire des trames même sur du contenu statique
- Vérifiez `/sdcard/DiLinkAuto/client.log` pour des informations de diagnostic

### Pertes de connexion
- Auparavant causées par des pertes de réseau non liées (basculement des données mobiles) déclenchant une déconnexion proactive
- Corrigé dans v0.12.5 : le callback réseau intelligent ignore les pertes sur les réseaux qui ne portent pas la connexion
- Si persistant, vérifiez `/sdcard/DiLinkAuto/client.log` pour les entrées "Network lost"

### L'application voiture ne se met pas à jour
- Le téléphone met automatiquement à jour l'application voiture lorsqu'une différence de version est détectée lors du handshake
- La voiture affiche le statut "Mise à jour de l'application voiture..." pendant la mise à jour
- Vous pouvez également déclencher manuellement une mise à jour avec le bouton "Installer sur la voiture" dans l'application téléphone
- Assurez-vous que dadb peut atteindre la voiture via WiFi ADB

### Logs
- Logs du téléphone : `/sdcard/DiLinkAuto/client.log` (session actuelle)
- Sessions précédentes : `/sdcard/DiLinkAuto/client-YYYYMMDD-HHmmss.log`
- Logs du serveur VD : `/data/local/tmp/vd-server.log` (sur le téléphone, lisibles via ADB)
- Logs de la voiture : acheminés vers client.log du téléphone via le canal DATA du protocole (tag : `CarLog`)
- Extraire les logs : `adb shell "cat /sdcard/DiLinkAuto/client.log"`

## Conseils pour HyperOS (Xiaomi)

Pour un fonctionnement fiable sur HyperOS :
1. Paramètres → Apps → DiLink Auto → Démarrage automatique → Activer
2. Paramètres → Batterie → DiLink Auto → Aucune restriction
3. Verrouillez l'application dans les Apps récentes (appui long sur la carte → Verrouiller)

## Conseils pour Samsung One UI

Les appareils Samsung sous One UI 5+ (Android 13+) disposent de fonctions de sécurité et d'économie d'énergie supplémentaires qui peuvent empêcher DiLink-Auto de fonctionner correctement. Cela s'applique aux séries Galaxy A, M, S, Z et Tab.

### Désactiver Auto Blocker (critique pour USB ADB)

**Auto Blocker** bloque les commandes USB et peut empêcher la voiture de se connecter à votre téléphone via USB ADB. C'est le problème le plus courant lié à Samsung.

1. **Paramètres → Sécurité et confidentialité → Auto Blocker → Désactivé**
2. Si vous préférez garder Auto Blocker activé, désactivez au moins l'option **"Bloquer les commandes par câble USB"**

### Autoriser l'accès à tous les fichiers

Le gestionnaire de permissions Samsung peut révoquer automatiquement les permissions des applications que vous n'avez pas ouvertes récemment :

1. **Paramètres → Applications → DiLink Auto → Autorisations → Fichiers et médias → Autoriser la gestion de tous les fichiers**
2. Activez **"Autoriser la gestion de tous les fichiers"**
3. Vérifiez qu'il reste ACTIVÉ après avoir fermé les paramètres (Samsung peut afficher une confirmation)

### Désactiver l'optimisation de la batterie

La gestion de la batterie de Samsung est plus agressive que celle d'Android standard :

1. **Paramètres → Applications → DiLink Auto → Batterie → Illimitée**
2. **Paramètres → Batterie → Limites d'utilisation en arrière-plan → Applications jamais en veille → Ajouter DiLink Auto**
3. **Paramètres → Batterie → Limites d'utilisation en arrière-plan → Applications en veille profonde → Supprimer DiLink Auto** si listée

### Verrouiller l'application dans les Récentes

One UI de Samsung peut tuer les applications en arrière-plan pour libérer de la mémoire :

1. Ouvrez les Applications récentes (glissez vers le haut depuis le bas avec navigation à 3 boutons ou par gestes)
2. Appuyez sur l'icône DiLink Auto en haut de sa carte
3. Sélectionnez **"Garder ouvert"**

### Désactiver l'optimisation automatique de Samsung Device Care

Device Care de Samsung peut arrêter automatiquement les services en arrière-plan :

1. **Paramètres → Batterie et entretien de l'appareil → Automatisation → Optimisation quotidienne automatique → Désactivé**
2. **Paramètres → Batterie et entretien de l'appareil → Automatisation → Redémarrage automatique → Désactivé**

### Si vous voyez un popup d'autorisation "DeX"

Certains appareils Samsung affichent un popup concernant "Samsung DeX" ou les autorisations "d'affichage externe" lorsqu'une application tente de créer un affichage virtuel. Même si les séries Galaxy A/M ne prennent pas en charge DeX, la boîte de dialogue peut apparaître. Appuyez simplement sur **"Autoriser"** ou **"Démarrer maintenant"**. Si la boîte de dialogue réapparaît, allez dans **Paramètres → Appareils connectés → Samsung DeX** et désactivez "Démarrage automatique lorsque HDMI est connecté".

### Considérations de sécurité Knox

Samsung Knox peut afficher une notification de sécurité lorsque DiLink-Auto accède à :
- La surface d'affichage virtuel (pour l'encodage vidéo)
- Le pont de débogage USB (pour la connexion ADB de la voiture)
- Le stockage de tous les fichiers (pour le déploiement du serveur VD)

Ces comportements sont normaux. Appuyez sur "Autoriser" ou "OK" sur toute invite liée à Knox. Si les invites persistent, vous pouvez temporairement abaisser la protection Knox à "Moyen" sous **Paramètres → Sécurité et confidentialité → Samsung Knox**.
