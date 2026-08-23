# Crypto Reversal Scanner — Mobile APK Build

## Build an APK using only your Android phone

1. Create a GitHub account if you don't already have one.
2. Create a new **private repository** on GitHub.
3. Upload all files from this project to the repository.
4. Commit them to the `main` branch.
5. Open the repository → **Actions**.
6. Select **Build Android APK**.
7. Tap **Run workflow**.
8. Wait for the green checkmark.
9. Open the completed workflow run.
10. Scroll to **Artifacts**.
11. Download `CryptoReversalScanner-debug-apk`.
12. Extract the ZIP and install `app-debug.apk` on your Android phone.

The workflow uses GitHub's Ubuntu runner, Java 17 and Gradle, so you do not need a computer or Android Studio.

## Important

This is a DEBUG APK. Android may ask you to allow installation from the browser/file manager.

The app does not place real Binance orders. It scans public Binance Vision data and tracks generated signals locally.

## If the workflow fails

Open the failed workflow run and look at the red step. The build log will show the exact error.
