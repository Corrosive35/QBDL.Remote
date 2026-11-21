# QBDL — application Android

Coquille WebView qui affiche l'interface de votre instance
[QBDL.Web](https://github.com/Corrosive35/QBDL.Web). Il n'y a **aucune duplication d'interface** :
l'application suit automatiquement chaque évolution du site.

[![GitHub](https://img.shields.io/github/v/tag/Corrosive35/QBDL.Remote?sort=semver&logo=github&label=GitHub)](https://github.com/Corrosive35/QBDL.Remote/releases)
[![Téléchargements](https://img.shields.io/github/downloads/Corrosive35/QBDL.Remote/total?logo=android)](https://github.com/Corrosive35/QBDL.Remote/releases)
[![QBDL.Web](https://img.shields.io/github/v/tag/Corrosive35/QBDL.Web?sort=semver&logo=github&label=QBDL.Web)](https://github.com/Corrosive35/QBDL.Web)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android)
![Kotlin 2.0](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

---

## Fonctionnalités

Le projet ne contient que ce qu'un WebView nu ne sait pas faire :

- **Adresse mémorisée** — L'adresse du serveur est la seule donnée que l'application enregistre
  elle-même, et seulement après que `/healthz` a confirmé qu'il s'agit d'une instance QBDL.Web
- **Saisie souple** — Une IP, un nom de domaine ou une URL complète. Sans schéma, HTTPS puis HTTP ;
  sans port, le port standard puis 8080 ; toutes les variantes sont sondées en parallèle
- **Connexion conservée** — La connexion se fait dans la page web, avec l'identifiant et le mot de
  passe du compte, que l'application ne voit jamais passer ; le WebView garde ensuite le cookie de
  connexion d'un lancement à l'autre
- **Pas de rechargement** — Une rotation de l'écran ou un basculement clair/sombre du système ne
  recharge pas la page
- **Reprise après l'arrière-plan** — Au retour au premier plan, l'application demande à la page de
  se resynchroniser, la Page Visibility API n'étant pas fiable dans un WebView mis en pause
- **Écran « Serveur injoignable »** — Si la page ne se charge pas, des boutons *Réessayer* et
  *Changer de serveur* plutôt qu'une page blanche
- **Raccourci « Changer de serveur »** — Par appui long sur l'icône de l'application
- **Mise à jour intégrée** — Une fois par jour au plus, l'application consulte la dernière
  Release GitHub ; si elle est plus récente, un bandeau propose de la télécharger et de l'installer
- **Une seule origine** — Tout lien vers un autre site s'ouvre dans le navigateur : le cookie de
  connexion n'est jamais exposé à une page tierce
- **Garde-fous réseau** — Avertissement avant d'enregistrer une adresse `http://` hors réseau privé
  ou tunnel ; un certificat TLS invalide arrête le chargement au lieu d'être accepté ; seules les
  autorités de certification du système sont reconnues
- **Bouton Retour** — Remonte l'historique de la page, puis quitte l'application
- **Thème sombre dès le lancement** — Pas d'éclair blanc au premier affichage ; l'assombrissement
  automatique d'Android est désactivé pour ne pas altérer les couleurs de l'interface
- **Bord à bord** — L'interface s'encastre entre les barres système et remonte au-dessus du clavier
- **Bilingue** — Les écrans propres à l'application (configuration, erreur, raccourci) sont en
  anglais sur un téléphone réglé en anglais, en français sinon
- **Sobre** — Seule permission demandée : Internet. La sauvegarde automatique d'Android est
  désactivée, pour qu'une restauration sur un autre téléphone n'y réinjecte pas la connexion

---

## Installation

1. Sur le téléphone, ouvrir la page [Releases](https://github.com/Corrosive35/QBDL.Remote/releases)
   et télécharger l'APK de la dernière version (`QBDL.Remote-v<version>.apk`).
2. L'ouvrir. La première fois, Android demande d'autoriser l'installation depuis le navigateur ou
   le gestionnaire de fichiers utilisé.
3. L'application apparaît ensuite dans le lanceur sous le nom **QobuzDL**.

Une version plus récente s'installe par-dessus la précédente, de la même façon : l'adresse du
serveur et la connexion sont conservées.

> **Debug ou release :** une APK ne peut être remplacée que par une APK signée avec la même clé.
> Une APK compilée en debug, ou signée avec un autre keystore, ne peut ni remplacer celle des
> Releases ni être remplacée par elle (« App not installed ») : il faudrait désinstaller, ce qui
> efface les cookies du WebView et oblige à se reconnecter.

---

## Utilisation

Au premier lancement, l'application demande l'adresse du serveur — par exemple `192.168.1.42`,
`192.168.1.42:8080`, `mondomaine.fr` ou une URL complète. Elle ne l'enregistre qu'une fois que
`/healthz` a répondu, puis affiche l'interface du serveur, où l'on se connecte avec son
identifiant et son mot de passe.

### Quand l'adresse n'est pas trouvée

Sans port explicite, l'application sonde `https://hôte`, `http://hôte`, `https://hôte:8080` et
`http://hôte:8080` — les quatre en parallèle. **Un autre port ne peut pas être deviné** : si le
`docker run` publie par exemple `-p 5000:8080`, il faut saisir `192.168.1.42:5000`.

Le message d'échec liste les adresses réellement tentées. Le détail de chaque échec (délai
dépassé, connexion refusée, certificat, code HTTP) part dans logcat :

```bash
adb logcat -s QobuzDL
```

### Trafic en clair

`network_security_config.xml` autorise le HTTP. Le raisonnement — et pourquoi une liste blanche
statique ne marcherait pas ici — est détaillé en commentaire dans le fichier. Le garde-fou réel
est à la saisie : une adresse `http://` vers un hôte qui n'est ni sur un réseau privé ni dans un
tunnel déclenche un avertissement explicite avant d'être enregistrée.

### Changer de serveur

Appui long sur l'icône **QobuzDL** dans le lanceur → **Changer de serveur**. Le même écran
est proposé par le bouton de l'écran « Serveur injoignable ».

L'adresse précédente est pré-remplie, et la connexion à chaque serveur est conservée : revenir à
une instance déjà utilisée ne redemande ni l'identifiant ni le mot de passe, tant que cette
connexion n'a pas expiré.

---

## Serveur

Toute l'interface vient de [QBDL.Web](https://github.com/Corrosive35/QBDL.Web), le serveur que
vous hébergez vous-même (Blazor Server, Docker) : installation, comptes et réglages sont décrits
dans son README.

Le serveur doit tourner en version **1.5.0 ou supérieure** : l'application interroge `/healthz`
pour valider l'adresse saisie, et s'appuie sur la connexion par cookie introduite dans cette
version.

Depuis la **1.7.0**, le serveur gère des comptes — chacun se connecte avec son identifiant et son
mot de passe — et s'annonce « QBDL.Web » sur `/healthz`. Il faut alors l'APK **1.1.2 ou
supérieure** pour y saisir une adresse : une APK plus ancienne continue d'afficher un serveur déjà
configuré, mais refuse toute nouvelle saisie. L'APK 1.1.2 reconnaît aussi les serveurs 1.5.0 à
1.6.x.

---

## Compiler depuis les sources

### Premier build

Le **wrapper Gradle n'est pas versionné** (`gradle/wrapper/gradle-wrapper.jar` est un binaire).
Deux façons de l'obtenir :

- ouvrir ce dossier dans Android Studio, qui propose de le générer au premier chargement ;
- ou, si Gradle est installé : `gradle wrapper --gradle-version 8.9`.

Ensuite :

```bash
./gradlew :app:assembleDebug
```

L'APK sort dans `app/build/outputs/apk/debug/`.

### Signature release

À faire **avant la première installation sur le téléphone**. Une APK signée en release ne peut
pas remplacer une APK signée en debug (« App not installed ») : il faudrait désinstaller, ce qui
efface les cookies du WebView et oblige à se reconnecter.

Générer le keystore, une fois pour toutes, et le sauvegarder ailleurs que sur la machine de
build — le perdre interdit toute mise à jour de l'application installée :

```bash
keytool -genkeypair -v \
  -keystore ~/qdlr-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias qdlr
```

Sous Windows, `keytool` est fourni avec Android Studio (PowerShell) :

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore "$HOME\qdlr-release.jks" -keyalg RSA -keysize 4096 -validity 10000 -alias qdlr
```

`keytool` ne demande qu'**un seul mot de passe** : le keystore est au format PKCS12, qui protège
le magasin et la clé par le même secret. Les questions d'identité qui suivent (nom, organisation…)
n'ont aucune incidence et peuvent rester vides ; la dernière attend « oui » (ou « yes »).

Déclarer les clés dans `~/.gradle/gradle.properties` — **jamais** dans le projet :

```properties
QDLR_STORE_FILE=/home/utilisateur/qdlr-release.jks
QDLR_STORE_PASSWORD=…
QDLR_KEY_ALIAS=qdlr
QDLR_KEY_PASSWORD=…
```

Puis :

```bash
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

La dernière commande doit afficher **votre** certificat. Si `QDLR_STORE_FILE` est absente, la
configuration de signature n'est pas créée et l'APK sort non signée : c'est volontaire, pour que
le projet reste compilable sur une machine sans keystore.

Les versions publiées sont compilées, signées et publiées par GitHub Actions : la procédure
(secrets, tag, build d'essai) est décrite en tête de
[`.github/workflows/release.yml`](.github/workflows/release.yml).

---

## Pile technique

| Composant | Technologie |
|-----------|-------------|
| Langage | Kotlin 2.0.21, JVM 17 |
| Interface | Vues XML + viewBinding, Material Components 1.12.0 |
| Affichage | WebView du système, AndroidX WebKit 1.12.1 |
| AndroidX | core-ktx 1.15.0, appcompat 1.7.0, activity-ktx 1.9.3, lifecycle-runtime-ktx 2.8.7 |
| SDK | minSdk 26 (Android 8.0), compileSdk et targetSdk 35 (Android 15) |
| Build | Gradle 8.9, Android Gradle Plugin 8.7.3, JDK 17 |
| Publication | GitHub Actions : compilation, signature et Release sur tag `v*` |

---

## Structure

```
QBDL.Remote/
├── app/
│   ├── build.gradle.kts                  # Version, SDK, signature release
│   └── src/main/
│       ├── AndroidManifest.xml           # Activités, permission Internet, raccourci
│       ├── java/com/qobuzdl/remote/
│       │   ├── MainActivity.kt           # Hôte du WebView : cookies, retour, écran d'erreur
│       │   ├── SetupActivity.kt          # Écran de configuration, validation avant enregistrement
│       │   ├── ServerUrl.kt              # Résolution de l'adresse, sonde /healthz, réseaux privés
│       │   └── ServerPrefs.kt            # Persistance de l'adresse retenue
│       └── res/
│           ├── values/strings.xml        # Textes français (langue par défaut)
│           ├── values-en/strings.xml     # Textes anglais
│           └── xml/
│               ├── network_security_config.xml  # Trafic en clair autorisé, et pourquoi
│               └── shortcuts.xml         # Raccourci « Changer de serveur »
├── gradle/libs.versions.toml             # Versions des dépendances
└── .github/workflows/release.yml         # Compilation, signature et publication sur tag v*
```

---

## Prérequis

- Un serveur [QBDL.Web](https://github.com/Corrosive35/QBDL.Web) **1.5.0 ou supérieur**, joignable
  depuis le téléphone ; à partir de la 1.7.0, l'APK **1.1.2 ou supérieure**
- Un téléphone sous **Android 8.0** ou plus récent
- Pour compiler : JDK 17 et SDK Android 35, ou Android Studio

---

## Avertissement

**Projet personnel et non officiel.** Il n'a aucun lien avec Qobuz : il n'est ni affilié, ni
approuvé, ni soutenu, ni sponsorisé par Qobuz ou par son propriétaire. « Qobuz » est une marque de
son titulaire, employée ici à titre purement descriptif. Toutes les marques citées appartiennent à
leurs titulaires respectifs.

**Ce que contient ce dépôt.** Uniquement une application Android de type WebView, qui affiche
l'interface web d'un serveur que l'utilisateur héberge et administre lui-même, à l'adresse qu'il
saisit. Ce dépôt ne contient :

- aucun code d'accès à un service de streaming ni à son API ;
- aucun identifiant, jeton, clé ou secret d'application ;
- aucun mécanisme de contournement d'une mesure technique de protection (DRM ou autre) ;
- aucun contenu protégé : ni musique, ni pochette, ni logo ou élément graphique d'un tiers.

L'application ne télécharge, ne stocke ni ne distribue elle-même aucun contenu : elle ne demande
que l'accès à Internet et n'écrit aucun fichier sur le téléphone.

**Responsabilité de l'utilisateur.** Chacun est seul responsable de l'usage qu'il fait de cette
application et du serveur auquel il la connecte. Il lui appartient de respecter les conditions
d'utilisation des services tiers concernés, ainsi que la législation sur le droit d'auteur en
vigueur dans son pays. Les auteurs n'encouragent ni ne cautionnent aucun usage qui y
contreviendrait, et déclinent toute responsabilité à cet égard. Le logiciel est fourni « tel
quel », sans garantie d'aucune sorte (voir [LICENSE](LICENSE)).

**Titulaires de droits.** Si vous estimez qu'un élément de ce dépôt porte atteinte à vos droits,
ouvrez une [issue](https://github.com/Corrosive35/QBDL.Remote/issues) : la demande sera examinée,
et l'élément retiré promptement s'il y a lieu.

## Disclaimer

**Unofficial personal project.** It is not affiliated with, endorsed, supported or sponsored by
Qobuz or its owner. "Qobuz" is a trademark of its respective owner and is used here for
descriptive purposes only. All trademarks mentioned belong to their respective owners.

**What this repository contains.** Only a WebView-based Android app that displays the web
interface of a server the user hosts and runs themselves, at an address they enter. This
repository contains:

- no code accessing any streaming service or its API;
- no credentials, tokens, keys or application secrets;
- no mechanism to circumvent any technological protection measure (DRM or otherwise);
- no copyrighted content: no music, artwork, or third-party logos or graphics.

The app itself does not download, store or distribute any content: it only requests Internet
access and writes no files to the device.

**User responsibility.** Users are solely responsible for how they use this app and the server
they connect it to, and must comply with the terms of service of any third-party service
involved and with the copyright laws of their country. The authors do not encourage or condone
any use that would violate them, and disclaim all liability in that regard. The software is
provided "as is", without warranty of any kind (see [LICENSE](LICENSE)).

**Rights holders.** If you believe anything in this repository infringes your rights, please
open an [issue](https://github.com/Corrosive35/QBDL.Remote/issues); the request will be reviewed,
and the material promptly removed where appropriate.
