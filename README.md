# FloatingKit — generic floating-window kit extracted from LiveFootball_II

Source: `D:\claude\LiveFootball_II` → most important part = `FloatingScoreService` (1912 lines) + its 4 layouts.
Now converted to production: plain `Service` + `SYSTEM_ALERT_WINDOW`.

## What was kept (core trick)
- `FloatingOverlayService` = generic port of `FloatingScoreService`:
  - `Service` + `TYPE_APPLICATION_OVERLAY` + foreground notification → floats over other apps, Play-compliant (no accessibility hack).
  - Drag-to-move with tap-vs-drag disambiguation (`dx²+dy² > 64`), position memory (`singleWindowPos`, `savedPositions`, `freedPosition`).
  - 4 modes: **SINGLE (cycle) / MULTI / STACK / TICKER** — same as original.
  - `FloatingThemes` — same 8 ARGB themes (classic/dark/green/midnight/crimson/ocean/purple/amber).
  - `pulseCard()` flash animation + `applyScale()` 0.5x–2.0x + lock-screen filter.
  - `DraggableContainer` copied verbatim (ticker scrolls horizontally, vertical drag moves window).
- Layouts: `floating_card.xml` ← `floating_score.xml`, `floating_stack.xml` ← `floating_score_stack.xml`, `floating_stack_row.xml`, `floating_ticker.xml`, `item_ticker.xml` ← `item_ticker_match.xml`.

## What was removed / changed in conversion
- Removed: `AccessibilityService`, `TYPE_ACCESSIBILITY_OVERLAY`, `BIND_ACCESSIBILITY_SERVICE`, `accessibility_service_config.xml`, `onAccessibilityEvent` foreground tracking, per-app video/game/camera blacklist (needs UsageStats — stubbed out), `KEY_HIDE_*` prefs.
- Added: `SYSTEM_ALERT_WINDOW` permission, `Settings.canDrawOverlays()` check, `ACTION_MANAGE_OVERLAY_PERMISSION` flow, `startForegroundService` + `specialUse` foreground notification (`overlay_channel_*` strings).
- Removed football: `Api.fetchLive()`, `LiveMatch`/`MatchDetail`, FCM, Socket.io, goal vibrate/scorer-fetch. Replaced with:
  - `FloatingItem(id, title, status, left, right, detail, badge)` — multi-provider free feed.
  - `StockApi` routes by symbol (all $0, no keys — verified):
    | Market | Symbols | Provider |
    |---|---|---|
    | US | `AAPL, MSFT, SPY` | Yahoo chart (Finnhub key = realtime upgrade path) |
    | UK | `HSBA.L, VOD.L, BP.L` | Yahoo |
    | EU | `AIR.PA, SAP.DE, ASML.AS` | Yahoo |
    | KSA | `2222.SR, 1120.SR` | Yahoo (verified Aramco live) |
    | UAE/QA/KW/BH/OM | `EMAAR.AE, FAB.AE, QNB.QA, NBK.KW` | Yahoo |
    | Egypt | `COMI.CA, HRHO.CA` | Yahoo (verified EGX live) |
    | Forex | `EURUSD, EUR/USD, USD/SAR, USDEGP=X` → `EURUSD=X` | Yahoo |
    | Crypto | `BTC, ETH, BTC-USD` → Binance `BTCUSDT` | Binance 24hr ticker (realtime) |
  - Parallel fetch (coroutine per symbol, 12 max), query2 host fallback, `lastGood` cache + offline placeholders.
  - `DemoFloatingService.getItems()` → `StockApi.getFloatingItems(ctx)`, `refreshMs()=60s`. Watchlist in prefs (`watch_symbols`, default `AAPL,MSFT,HSBA.L,2222.SR,COMI.CA,EURUSD=X,BTC-USD`).
  - `badge != null` → pulse animation: set when |day move| ≥ 3% or tick ≥ 1%.

## Backend cache (`backend/`, $0 Northflank)
- `server.js` (express+axios): Finnhub US 60s + Yahoo intl 90s staggered (3/batch) + Binance crypto 30s; in-memory cache with per-kind TTL; `GET /quotes?symbols=` (max 12, `Cache-Control: max-age=30`, stale fallback) + `GET /health` (same shape as football backend).
- `StockApi` tries `BuildConfig.SERVER_BASE/quotes` first, falls back to direct Yahoo/Binance when base is empty or unreachable — so the app works today with `SERVER_BASE=""` and scales later.
- Run: `cd backend && npm install && cp .env.example .env && npm run dev` → `http://localhost:3000/health`. Set `FINNHUB_KEY` for US realtime, else Yahoo fallback. Deploy: Northflank combined service, build context `backend/`, public port 8080, env `FINNHUB_KEY` (+ optional `HOT_SYMBOLS`), then set `SERVER_BASE` in `app/build.gradle.kts` to the public URL.

## Project layout
```
StockTicker/
  settings.gradle.kts, build.gradle.kts, gradle.properties
  backend/server.js + Dockerfile + .env.example (Finnhub+Yahoo+Binance cache)
  app/build.gradle.kts (appcompat, material, coroutines; SERVER_BASE BuildConfig field)
  app/src/main/AndroidManifest.xml (DemoFloatingService as foregroundServiceType="specialUse")
  app/src/main/java/com/example/floatingkit/
    FloatingOverlayService.kt  ← core engine
    FloatingModels.kt          ← FloatingItem + FloatingThemes
    StockApi.kt                ← backend-first, Yahoo/Binance direct fallback
    DraggableContainer.kt      ← verbatim
    DemoFloatingService.kt     ← wires StockApi into overlay
    MainActivity.kt            ← permission + start/stop + watchlist/mode/theme/scale
```

## Run
1. Open `D:\claude\StockTicker` in Android Studio.
2. Run `app` on device (minSdk 26).
3. Tap **1. Grant overlay permission** → allow "Display over other apps".
4. Tap **2. Start floating service**. Edit watchlist → **Save + refresh now**, toggle mode/theme/scale — overlay shows live Yahoo prices over any app, refreshes every 60s.

## Reuse in your own app
1. Copy `FloatingOverlayService.kt`, `FloatingModels.kt`, `DraggableContainer.kt` + `floating_*.xml`.
2. Subclass: `class MyService : FloatingOverlayService()` → implement `getItems()` from your API, `onItemTap()` for navigation.
3. Declare service in manifest with `SYSTEM_ALERT_WINDOW` + `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
4. Control via prefs (`floatingkit_prefs`: `floating_enabled/mode/overlay_theme/window_scale`) + `FloatingOverlayService.broadcastSettings(ctx)`.
