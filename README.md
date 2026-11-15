# QobuzDLX — application Android

Coquille WebView qui affiche l'interface de votre instance QobuzDLX.Web. Il n'y a **aucune
duplication d'interface** : l'application suit automatiquement chaque évolution du site.

Le projet ne contient que ce qu'un WebView nu ne sait pas faire — mémoriser l'adresse du
serveur, survivre aux rotations et aux mises en arrière-plan, et garder le cookie de
déverrouillage d'un lancement à l'autre.

## Prérequis

Le serveur doit tourner en version **1.5.0 ou supérieure** : l'application interroge `/healthz`
pour valider l'adresse saisie, et s'appuie sur le déverrouillage par cookie introduit dans cette
version.

## Premier build

Le **wrapper Gradle n'est pas versionné** (`gradle/wrapper/gradle-wrapper.jar` est un binaire).
Deux façons de l'obtenir :

- ouvrir ce dossier dans Android Studio, qui propose de le générer au premier chargement ;
- ou, si Gradle est installé : `gradle wrapper --gradle-version 8.9`.

Ensuite :

```bash
./gradlew :app:assembleDebug
```

L'APK sort dans `app/build/outputs/apk/debug/`.

## Signature release

À faire **avant la première installation sur le téléphone**. Un APK signé en release ne peut
pas remplacer un APK signé en debug (« App not installed ») : il faudrait désinstaller, ce qui
efface les cookies du WebView et redemande le mot de passe d'accès.

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
configuration de signature n'est pas créée et l'APK sort non signé : c'est volontaire, pour que
le projet reste compilable sur une machine sans keystore.

## Publier une version

Les versions sont compilées, signées et publiées par GitHub Actions
([release.yml](.github/workflows/release.yml)) : Android Studio n'est plus nécessaire pour livrer.

### Une fois pour toutes : les secrets

Le keystore de la section précédente est confié au dépôt sous forme de quatre secrets
(**Settings → Secrets and variables → Actions → New repository secret**) :

| Secret | Valeur |
| --- | --- |
| `QDLR_KEYSTORE_BASE64` | le fichier `.jks`, encodé en base64 (voir ci-dessous) |
| `QDLR_STORE_PASSWORD` | mot de passe du keystore |
| `QDLR_KEY_ALIAS` | `qdlr` |
| `QDLR_KEY_PASSWORD` | mot de passe de la clé — le même que le précédent pour un keystore créé par `keytool` |

Encodage du keystore, sous Windows (PowerShell, le résultat part dans le presse-papiers) :

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\qdlr-release.jks")) | Set-Clipboard
```

Sous Linux (`base64 -i` sous macOS) :

```bash
base64 -w0 ~/qdlr-release.jks
```

Il faut **le même keystore** que celui de l'APK déjà installée sur le téléphone : signée par une
autre clé, la nouvelle version serait refusée à l'installation.

Quiconque a les droits d'écriture sur le dépôt peut modifier le workflow et lire ces secrets :
ne les accordez à personne d'autre. Le dépôt peut rester public sans risque : une pull request
venue d'un fork ne reçoit pas les secrets, et le workflow ne se déclenche que sur un tag ou un
lancement manuel, deux actions réservées aux comptes ayant l'écriture.

### À chaque version

1. Incrémenter `versionCode` et `versionName` dans `app/build.gradle.kts`.
2. Committer, puis poser et pousser le tag correspondant :

   ```bash
   git tag v1.2 && git push origin main v1.2
   ```

3. Quelques minutes plus tard, la Release `v1.2` apparaît avec `QobuzDL.Remote-v1.2.apk` en
   pièce jointe, à ouvrir directement depuis le téléphone.

Le tag doit valoir exactement `v` + `versionName` : sinon le workflow s'arrête avant de compiler,
plutôt que de publier une « v1.2 » qui s'annoncerait autrement dans les paramètres Android.

### Build d'essai

**Actions → Release APK → Run workflow** sur n'importe quelle branche : même compilation et même
signature, mais aucune Release n'est publiée. L'APK se télécharge directement depuis la page du
run (artefact conservé 14 jours).

## Trafic en clair

`network_security_config.xml` autorise le HTTP. Le raisonnement — et pourquoi une liste blanche
statique ne marcherait pas ici — est détaillé en commentaire dans le fichier. Le garde-fou réel
est à la saisie : une adresse `http://` vers un hôte qui n'est ni sur un réseau privé ni dans un
tunnel déclenche un avertissement explicite avant d'être enregistrée.

## Changer de serveur

Appui long sur l'icône de l'application dans le lanceur → **Changer de serveur**. Le même écran
est proposé par le bouton de l'écran « Serveur injoignable ».

L'adresse précédente est pré-remplie, et le cookie de déverrouillage de chaque serveur reste
valable : revenir à une instance déjà utilisée ne redemande pas le mot de passe.

## Quand l'adresse n'est pas trouvée

Sans port explicite, l'application sonde `https://hôte`, `http://hôte`, `https://hôte:8080` et
`http://hôte:8080` — les quatre en parallèle. **Un autre port ne peut pas être deviné** : si le
`docker run` publie par exemple `-p 5000:8080`, il faut saisir `192.168.1.42:5000`.

Le message d'échec liste les adresses réellement tentées. Le détail de chaque échec (délai
dépassé, connexion refusée, certificat, code HTTP) part dans logcat :

```bash
adb logcat -s QobuzDLX
```

## Structure

| Fichier | Rôle |
| --- | --- |
| `ServerUrl.kt` | Résolution de l'adresse saisie, sonde `/healthz`, détection des réseaux privés |
| `ServerPrefs.kt` | Persistance de l'adresse retenue |
| `SetupActivity.kt` | Écran de configuration, validation avant enregistrement |
| `MainActivity.kt` | Hôte du WebView : insets, cookies, navigation arrière, écran d'erreur |
| `.github/workflows/release.yml` | Compilation, signature et publication de l'APK sur tag `v*` |
