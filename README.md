# Crypto Reversal Scanner — Android MVP

This Android Studio project is an MVP of the Python reversal scanner.

## What it does

- Uses Binance public market-data endpoint:
  `https://data-api.binance.vision`
- Scans USDT markets by 24h quote volume.
- Uses 15m, 1h and 4h candles.
- Detects:
  - fast pump/dump
  - RSI exhaustion
  - volume expansion
  - local high/low liquidity sweep
  - rejection wick
- Creates LONG/SHORT trade plans.
- Saves every opened signal locally in SQLite.
- Tracks open signals against live prices.
- Marks:
  - WIN when TP1 is reached
  - LOSS when SL is reached
- Calculates:
  - win rate
  - wins
  - losses
  - open trades
  - average R result
- Keeps history after the app is closed.

## Important

This version is a signal/tracking app. It does NOT place Binance orders.

The scanner intentionally requires confirmation conditions. Entry is the current scanner price; users should treat it as a planned entry, not a guaranteed fill.

## Build

Open the folder in Android Studio and run on an Android phone.

If Gradle cannot download dependencies, connect Android Studio to the internet once and let it sync.

## Next version

Recommended upgrades:
- background scanning every 1–5 minutes
- push notifications
- TP1/TP2/TP3 partial tracking
- manual "entered trade" button
- actual fill price
- risk calculator based on USDT account size
- Google Sheets export
- charts
- settings for score/volume/timeframes
