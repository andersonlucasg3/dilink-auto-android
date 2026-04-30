# DiLink-Auto

Utilisez les applications de votre téléphone sur l'écran intégré de votre voiture. Open-source, aucun service Google requis.

Une alternative open-source à Android Auto pour **tout téléphone Android 10+** associé aux systèmes d'infodivertissement **BYD DiLink 3.0+**. Initialement motivé par l'écart Xiaomi HyperOS / ROM chinoise, mais fonctionne universellement.

[![Sponsor](https://img.shields.io/badge/Sponsor-%E2%9D%A4-pink?logo=github)](https://github.com/sponsors/andersonlucasg3)
[![Pix](https://img.shields.io/badge/Pix-Brazil-00C2A0)](https://nubank.com.br/cobrar/5gf35/69ed4939-b2c0-4071-b75d-3b430ab70a5d)

## Documentation / Documentação / Документация / Documentatio / Hujjatlar

GitHub n'affiche **pas** automatiquement la documentation dans la langue de l'utilisateur. Sélectionnez votre langue ci-dessous :

| Language | README | Setup | Architecture | Client | Server | Protocol | Progress |
|----------|--------|-------|-------------|--------|--------|----------|----------|
| English | [README](./README.md) | [Setup](./setup.md) | [Arch](./architecture.md) | [Client](./client.md) | [Server](./server.md) | [Proto](./protocol.md) | [Progress](./progress.md) |
| Português (BR) | [README](./pt-BR/README.md) | [Setup](./pt-BR/setup.md) | [Arch](./pt-BR/architecture.md) | [Client](./pt-BR/client.md) | [Server](./pt-BR/server.md) | [Proto](./pt-BR/protocol.md) | [Progress](./pt-BR/progress.md) |
| Français | [README](./fr/README.md) | [Setup](./fr/setup.md) | [Arch](./fr/architecture.md) | [Client](./fr/client.md) | [Server](./fr/server.md) | [Proto](./fr/protocol.md) | [Progress](./fr/progress.md) |
| Русский | [README](./ru/README.md) | [Setup](./ru/setup.md) | [Arch](./ru/architecture.md) | [Client](./ru/client.md) | [Server](./ru/server.md) | [Proto](./ru/protocol.md) | [Progress](./ru/progress.md) |
| Беларуская | [README](./be/README.md) | [Setup](./be/setup.md) | [Arch](./be/architecture.md) | [Client](./be/client.md) | [Server](./be/server.md) | [Proto](./be/protocol.md) | [Progress](./be/progress.md) |
| Қазақша | [README](./kk/README.md) | [Setup](./kk/setup.md) | [Arch](./kk/architecture.md) | [Client](./kk/client.md) | [Server](./kk/server.md) | [Proto](./kk/protocol.md) | [Progress](./kk/progress.md) |
| Українська | [README](./uk/README.md) | [Setup](./uk/setup.md) | [Arch](./uk/architecture.md) | [Client](./uk/client.md) | [Server](./uk/server.md) | [Proto](./uk/protocol.md) | [Progress](./uk/progress.md) |
| Oʻzbekcha | [README](./uz/README.md) | [Setup](./uz/setup.md) | [Arch](./uz/architecture.md) | [Client](./uz/client.md) | [Server](./uz/server.md) | [Proto](./uz/protocol.md) | [Progress](./uz/progress.md) |

## Ce Que Cela Fait

DiLink-Auto reflète les applications de votre téléphone sur l'écran de votre voiture avec une interaction tactile complète. Lancez la navigation, la musique, la messagerie — n'importe quelle application de votre téléphone — directement depuis l'écran de la voiture. Les notifications apparaissent dans la barre de navigation de la voiture avec des indicateurs de progression. Vidéo H.264 jusqu'à 60 ips, CBR 8 Mbps, avec l'écran du téléphone éteint pour économiser la batterie.

**Motivation originale :** combler le fossé lorsque votre téléphone ne peut pas exécuter Android Auto (ROM chinoise, pas de Google Play Services) mais que votre voiture ne prend en charge qu'Android Auto (pas de CarWith, CarPlay ou Carlife). Mais DiLink-Auto fonctionne avec n'importe quel téléphone Android — avec ou sans Google Services.

| Appareil | Problème |
|----------|----------|
| Xiaomi 17 Pro Max (HyperOS 3, ROM chinoise) | Pas d'Android Auto — pas de Google Play Services |
| BYD Destroyer 05 / King (marché brésilien) | Uniquement Android Auto sur l'unité centrale |
| Tout téléphone Android 10+ | Fonctionne indépendamment de la ROM ou des Play Services |

## Prérequis

**Téléphone :**
- Tout téléphone Android 10+
- Débogage USB activé (Options développeur)
- Autorisation d'accès à tous les fichiers (demandée au premier lancement)

**Voiture :**
- BYD DiLink 3.0 ou plus récent
- Un port USB-A libre

**Le point d'accès du téléphone doit être activé** — la voiture se connecte au point d'accès WiFi de votre téléphone. Aucun code d'appairage, aucun compte Google requis.

**Aucune connexion Internet n'est requise.** DiLink-Auto diffuse tout localement via le point d'accès WiFi de votre téléphone — la voiture et le téléphone communiquent directement. Une connexion Internet n'est nécessaire que pour les applications exécutées sur votre téléphone (ex. : navigation, musique en streaming), pas pour DiLink-Auto lui-même.

## Comment Ça Marche

1. **Activez le point d'accès** — Activez le point d'accès WiFi de votre téléphone. La voiture s'y connecte.
2. **Branchez** — Connectez votre téléphone au port USB de la voiture
3. **Auto-installation** — Le téléphone installe l'application voiture via WiFi ADB (première fois seulement, en une seule touche)
4. **Connexion automatique** — 3 flux TCP WiFi dédiés : vidéo (port 9638), entrée tactile (port 9639) et contrôle (port 9637)
5. **Utilisez vos applications** — Lancez n'importe quelle application depuis l'écran de lancement de la voiture. Elle s'exécute sur le téléphone, s'affiche sur la voiture et répond au toucher

Le téléphone exécute vos applications sur un écran virtuel, encode l'écran en vidéo H.264 et la diffuse vers la voiture. Les touches sur l'écran de la voiture sont renvoyées au téléphone et injectées en tant qu'événements tactiles réels. L'écran physique du téléphone reste éteint (économie de batterie) et peut être utilisé indépendamment.

## Installation

<a href="https://github.com/andersonlucasg3/dilink-auto-android/releases/latest"><img src="https://img.shields.io/github/v/release/andersonlucasg3/dilink-auto-android?label=Download%20Latest%20Release" alt="Download Latest Release"></a>

Téléchargez la dernière version ou compilez depuis la source :

1. **Compiler :** `./gradlew :app-client:assembleDebug`
2. **Installer** l'APK situé dans `app-client/build/outputs/apk/debug/app-client-debug.apk` sur votre téléphone uniquement
3. **Activer le débogage USB** sur votre téléphone (Paramètres → Options développeur)
4. **Ouvrir DiLink-Auto** sur le téléphone et accorder l'accès à tous les fichiers lorsque demandé
5. **Activer le point d'accès, puis brancher sur le port USB de la voiture** — l'application voiture s'auto-installe au premier lancement via WiFi ADB

L'APK voiture et le JAR du serveur VD sont intégrés dans l'APK du téléphone — vous n'installez jamais rien sur la voiture vous-même.

## État Actuel

**Fonctionnel :**
- Diffusion vidéo H.264 à 60 ips (CBR 8 Mbps, profil Main, configurable via négociation)
- Entrée tactile complète (multi-touch, pincer pour zoomer)
- Lanceur d'applications avec recherche, tri alphabétique, icônes 64 dp, grille adaptative
- Notifications sur l'écran de la voiture avec barres de progression, touchez pour ouvrir
- Auto-mise à jour via GitHub Releases (release) ou pré-versions (debug)
- **Prise en charge Shizuku** : connexion sans ADB, auto-mise à jour silencieuse via pm install
- Mise à jour automatique : le téléphone détecte une application voiture obsolète (comparaison des noms de version) et la met à jour via WiFi ADB
- Écran du téléphone éteint pendant la diffusion (économie de batterie)
- Intégration guidée pour toutes les autorisations requises
- Internationalisation : anglais, portugais, russe, biélorusse, français, kazakh, ukrainien, ouzbek
- Restauration de l'affichage après déconnexion USB (v0.14.0+)
- Testé sur BYD DiLink 3.0 (1920x990) + Xiaomi 17 Pro Max (Android 16) + POCO F5 + Galaxy S24

**À venir :** streaming audio, contrôles multimédia, widgets de navigation

**Limitations connues :**
- Le processus du serveur VD redémarre lors de la déconnexion USB (se reconnecte automatiquement).
- Le point d'accès doit être activé manuellement (limitation d'Android 16).
- Artefacts visuels occasionnels — course au redémarrage du décodeur, récupération à la prochaine image clé (~1 s).
- Latence de diffusion ~100-200 ms sous charge. CBR 8 Mbps.
- L'écran peut rester éteint après une déconnexion USB brutale (corrigé dans la v0.14.0).
- **Certaines applis ne remplissent pas l'écran (letterbox/portrait uniquement).** DiLink-Auto reflète un écran virtuel en paysage sur l'écran de la voiture. Les applis qui ne supportent pas l'orientation paysage apparaîtront avec des bandes ou étroites — ceci est entièrement contrôlé par chaque appli, pas par DiLink-Auto. Rien ne peut être fait du côté de la duplication d'écran.

## Documentation

| Document | Public | Description |
|----------|--------|-------------|
| [Guide d'installation](./setup.md) | Utilisateurs | Installation détaillée et dépannage |
| [Architecture](./architecture.md) | Développeurs | Conception des modules, flux de connexion, décisions de conception |
| [Spécification du protocole](./protocol.md) | Développeurs | Format de transmission, types de messages, attribution des ports |
| [Application client (téléphone)](./client.md) | Développeurs | ConnectionService, déploiement du JAR VD, auto-mise à jour |
| [Application serveur (voiture)](./server.md) | Développeurs | Machine d'état, USB ADB, VideoDecoder, interface voiture |
| [Suivi de progression](./progress.md) | Contributeurs | État des fonctionnalités, jalons, feuille de route |

## Structure du Projet

L'APK téléphone (`app-client`) intègre à la fois l'APK voiture (`app-server`) et le JAR du serveur VD. Lorsque vous installez l'application téléphone, tout le nécessaire est inclus.

```
DiLink-Auto/
├── protocol/       Bibliothèque partagée (tramage, messages, découverte, USB ADB)
├── app-client/     APK téléphone — relais, déploiement VD, auto-mise à jour voiture, FileLog
├── app-server/     APK voiture — UI, machine d'état de connexion, décodeur vidéo
├── vd-server/      Serveur VirtualDisplay (compilé en JAR, déployé par le téléphone)
├── docs/           Documentation
└── gradle/         Système de build
```

## Soutien

Ce projet est développé de manière indépendante et repose sur le soutien de la communauté. Chaque contribution aide à couvrir le temps de développement, les appareils de test et à maintenir le projet en vie.

## Contribuer

Les PR sont les bienvenues. Consultez [Architecture](./architecture.md) et [Protocole](./protocol.md) pour le contexte technique. Compilez avec `./gradlew :app-client:assembleDebug` (JDK 17+, Android SDK 34).

### Modèle de Branches (Git-Flow + Types de Tickets)

Les branches sont créées automatiquement par l'agent de tickets en fonction du **modèle de ticket** utilisé :

| Modèle | Étiquette | Pattern de branche | Objectif |
|---------|-----------|-------------------|----------|
| Hotfix | `hotfix` | `hotfix/vX.Y.Z` | Correctifs critiques depuis main |
| Correction de bug | `bug` | `fix/N-agent` | Corrections de bugs |
| Nouvelle fonctionnalité | `feature` | `feature/N-agent` | Nouvelles fonctionnalités |
| Investigation | `investigation` | `investigate/N-agent` | Investigation du codebase |
| Documentation | `documentation` | `docs/N-agent` | Mises à jour de la documentation |
| Release | `release` | `release/vX.Y.Z` | Préparation de release |
| Tâche agent (générique) | — | `issue/N-agent` | Fourre-tout |

Toutes les branches fusionnent vers `develop` via PR, sauf `release/*` qui cible `main`.

### Workflows CI

| Workflow | Déclencheur | Action |
|----------|-------------|--------|
| `build.yml` | Push/PR vers `main` | Validation : build de l'APK release |
| `build-develop.yml` | Push/PR vers `develop`, `release/*` | Validation : build de l'APK debug |
| `build-pre-release.yml` | Tag `vX.Y.Z-dev-NN` | Build de l'APK debug + pré-release GitHub |
| `build-release.yml` | Tag `vX.Y.Z` | Build de l'APK release signé + GitHub Release |
| `sync-main-to-develop.yml` | Push vers `main` | Fusion `main` → `develop` (rétro-synchronisation git-flow) |
| `issue-agent.yml` | Ticket ouvert / commentaire | Agent autonome : branche, build, PR |

Toute la CI s'exécute sur des **runners WSL auto-hébergés**.

**Processus de release :** Créez un ticket Release à partir du modèle. L'agent crée `release/vX.Y.Z`, prépare les modifications et tague `vX.Y.Z-dev-NN`. Le push de la branche de release déclenche `build-pre-release.yml`, qui trouve le tag `-dev` sur le commit via `git tag --points-at HEAD` et publie une pré-release. Une fois prêt, `release/vX.Y.Z` est fusionné vers `main` avec un tag `vX.Y.Z` sur le commit de fusion. Le push vers `main` déclenche `build-release.yml` (build de l'APK signé + création de la GitHub Release) et `sync-main-to-develop.yml` (fusion automatique de `main` vers `develop`).

**Mises à jour des pré-releases :** Les utilisateurs sur le canal Pre-release reçoivent les builds `-dev`. Les utilisateurs sur le canal Release reçoivent uniquement les builds stables. Le canal est configurable dans les Paramètres.

## Licence

MIT — voir [LICENCE](../LICENSE)
