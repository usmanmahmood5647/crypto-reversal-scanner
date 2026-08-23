# Crypto Reversal Scanner — Mobile APK Build

You do NOT need a computer to build this APK.

## Phone-only method

### 1. Create a GitHub account
Open GitHub in your phone browser and sign in.

### 2. Create a repository
Create a new repository named:

`CryptoReversalScanner`

You can keep it private.

### 3. Upload this ZIP
Extract this project ZIP on your phone first, then upload the project files/folders to the GitHub repository.

IMPORTANT:
The `.github/workflows/build-apk.yml` file must exist in the repository at exactly:

`.github/workflows/build-apk.yml`

### 4. Start the build
In your GitHub repository:

Actions
→ Build Android APK
→ Run workflow
→ Run workflow

The workflow will automatically:
- install Java
- install Android SDK
- install Gradle
- compile the app
- create the APK

### 5. Download APK
When the workflow finishes:

Actions
→ Build Android APK
→ latest successful run
→ Artifacts
→ CryptoReversalScanner-debug-apk

Download and extract the artifact. It contains:

`app-debug.apk`

Install that APK on your Android phone.

## Important Android setting

If Android asks for permission to install an APK from your browser/file manager, enable:

Settings → Install unknown apps

for the app you are using to open the APK.

## Updating the app

Whenever the code is changed and pushed to the `main` branch, GitHub Actions automatically builds a new APK.

You can also manually run it from:

Actions → Build Android APK → Run workflow

## No Binance API key

The app uses Binance Vision public market data:

https://data-api.binance.vision

No Binance API key is required for the scanner.

## Safety

This app does not place Binance orders.

It generates and tracks trade signals locally.
