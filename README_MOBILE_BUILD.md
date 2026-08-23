# Crypto Reversal Scanner — Mobile APK Build

## Build an APK from your phone with GitHub Actions

You do NOT need a computer.

### 1. Create a GitHub repository

On your phone, open GitHub and create a new empty repository, for example:

`CryptoReversalScanner`

### 2. Upload this entire project

Upload all files and folders from this project into the repository.

Make sure this folder exists in GitHub:

`.github/workflows/build-apk.yml`

### 3. Important: Gradle wrapper

The workflow expects `./gradlew`.

If your GitHub upload does not contain `gradlew`, open the repository in GitHub Codespaces once and run:

```bash
gradle wrapper --gradle-version 8.7
```

Then commit:

- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`

### 4. Start the APK build

In GitHub:

Actions
→ Build Android APK
→ Run workflow

The workflow will compile the debug APK automatically.

### 5. Download the APK

After the workflow finishes:

Actions
→ latest successful run
→ Artifacts
→ CryptoReversalScanner-debug-apk

Download the ZIP, extract it, and install:

`app-debug.apk`

Android may ask you to allow installation from that source.

## No Binance API key is required

The app uses Binance public market data through:

https://data-api.binance.vision

It does not place real orders.

## Current app features

- 15m / 1H / 4H scanning
- rapid pump/dump detection
- RSI exhaustion
- volume expansion
- liquidity sweep detection
- LONG/SHORT scoring
- entry
- SL
- TP1/TP2/TP3
- signal history
- open trade tracking
- automatic TP1/SL result tracking
- win/loss statistics

## Important trading note

The current tracker treats TP1 as a WIN and SL as a LOSS. It is a signal-performance tracker, not a broker execution system.
