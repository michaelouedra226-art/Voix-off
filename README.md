# Voxa Studio — Compilation Android & GitHub Actions

Application Android (Kotlin + Jetpack Compose + Material 3) de production de voix off avec Firebase AI Logic (`Firebase.ai` + App Check).

## Prérequis techniques
- **JDK** : 21 (Eclipse Adoptium / Temurin)
- **Gradle** : 9.3.1 (via le wrapper `./gradlew` inclus)
- **Android Gradle Plugin** : 9.1.1 (`compileSdk = 36`, `minSdk = 24`, `targetSdk = 36`)

## Compilation automatique sur GitHub Actions
Le workflow `.github/workflows/android-apk.yml` se déclenche automatiquement sur chaque `push` / `pull_request` vers `main` ou `master`, ainsi que manuellement depuis l'onglet **Actions → Build Android APK → Run workflow**.

### Configuration Firebase requise
Lorsque vous poussez le projet vers GitHub depuis AI Studio, le fichier `app/google-services.json` n'est pas toujours inclus dans le dépôt. Pour que GitHub Actions compile l'APK avec Firebase AI Logic et App Check :
- **Option la plus simple (sans ligne de commande)** : dans AI Studio, ouvrez `app/google-services.json`, copiez tout son contenu JSON, puis créez le secret GitHub Actions **`GOOGLE_SERVICES_JSON`** dans **Settings → Secrets and variables → Actions** avec ce texte JSON collé tel quel.
- **Ou** utilisez le secret **`GOOGLE_SERVICES_JSON_BASE64`** (`base64 -w 0 app/google-services.json`), ou committez `app/google-services.json` (contenant le client `com.aistudio.voxastudio.qrzmkn`).

### Artefacts produits
1. **`voxa-studio-debug-apk`** (`app/build/outputs/apk/debug/app-debug.apk`) : compilé systématiquement après passage des tests `:app:testDebugUnitTest`, signé avec `debug.keystore` décodé depuis `debug.keystore.base64` (conservant l'empreinte SHA-1 enregistrée pour Firebase App Check).
2. **`voxa-studio-release-apk`** (`app/build/outputs/apk/release/app-release.apk`) : compilé automatiquement si les secrets de signature Release sont configurés dans **Settings → Secrets and variables → Actions** du dépôt GitHub :
   - `RELEASE_KEYSTORE_BASE64` : contenu encodé en Base64 de votre keystore `.jks` (contenant l'alias `upload`)
   - `STORE_PASSWORD` : mot de passe du keystore
   - `KEY_PASSWORD` : mot de passe de la clé `upload`

Pour encoder votre keystore Release en Base64 :
```bash
base64 -w 0 my-upload-key.jks
```

## Commandes locales
Avant de compiler localement, décodez le keystore debug s'il n'est pas déjà présent :
```bash
base64 --decode debug.keystore.base64 > debug.keystore
```

Exécuter les tests et compiler l'APK Debug :
```bash
sh ./gradlew :app:testDebugUnitTest
sh ./gradlew :app:assembleDebug
```
L'APK généré se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.
