# Crypto Reversal Scanner — Mobile APK Build

## Build the APK from your Android phone

You do NOT need Android Studio or a computer.

### 1. Create a GitHub repository

On GitHub, create a new repository, for example:

`CryptoReversalScanner`

You can keep it private.

### 2. Upload this project

Upload **all files and folders** from this project into the repository.

The important workflow file is:

`.github/workflows/build-apk.yml`

### 3. Start the build

On GitHub:

**Actions → Build Android APK → Run workflow**

Wait for the green checkmark.

### 4. Download APK

Open the completed workflow run.

At the bottom under **Artifacts**, tap:

`CryptoReversalScanner-debug-apk`

GitHub downloads a ZIP containing:

`app-debug.apk`

Extract the ZIP and install the APK on your Android phone.

### 5. Android permission

Android may ask you to allow installation from your browser/file manager. Allow it only for the app you are using to install the APK.

## What the app does

- Binance Vision public market data
- 15m / 1H / 4H scanner
- Pump/dump detection
- RSI
- Volume expansion
- Liquidity sweeps
- LONG / SHORT reversal score
- Entry
- Stop loss
- TP1 / TP2 / TP3
- Signal history
- Open trade tracking
- WIN / LOSS tracking
- Win rate

The app does not place Binance orders.

## Important tracking behavior

The current MVP considers TP1 a WIN and SL a LOSS. It is intended as a scanner/tracker rather than a broker execution system.

For a production version, the next upgrade should track:

- Signal generated
- Actual entry confirmed by user
- TP1 hit
- TP2 hit
- TP3 hit
- SL hit
- Maximum favorable excursion
- Maximum adverse excursion
- R multiple
- Actual account risk
- Daily/weekly/monthly statistics
