# Agent Trader

**An agentic AI futures-trading app for Android.**
A chat brain (any OpenAI-compatible LLM) that talks like a human, feels the market, and can
actually pull the trigger on a real Bitunix USDT-M futures account — paper trading by default,
live trading behind an explicit, armed-only switch.

[![Release](https://img.shields.io/github/v/release/qezawat-a/Agent-Trader?style=flat-square&label=release)](https://github.com/qezawat-a/Agent-Trader/releases/latest)
[![CI](https://github.com/qezawat-a/Agent-Trader/actions/workflows/release.yml/badge.svg)](https://github.com/qezawat-a/Agent-Trader/actions/workflows/release.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-23%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Bitunix](https://img.shields.io/badge/Bitunix-USD%E2%80%93M%20futures-F7931E?style=flat-square)](https://www.bitunix.com/api-docs/futures/)

---

## What it is

Agent Trader is a self-contained Android app (Kotlin + Jetpack Compose) that puts an LLM in
front of a real futures engine. You talk to it; it answers with numbers, notices when you are
stressed, and — when you ask — it inspects the account, runs the engine's tools and comes back
with what actually happened.

The trading side is not a toy: 21 strategies, a 15m + 1h consensus gate, a TP/SL plan built
around liquidation distance, signed REST calls verified against the live Bitunix API, and a
private WebSocket feed that streams balance, position, order and TP/SL updates into the chat,
and a keyless public WebSocket market feed (tickers + price) that keeps engine prices live.

## The four screens

| Screen | What it does |
|---|---|
| **Chat** | The brain. Ask anything or type a slash command — "why is there no signal?", "what is the confidence?" — and it answers with the real rows, not a guess. Live position/order/TP-SL events from the WebSocket feed arrive in the same thread. |
| **Trade** | One-tap entries: symbol chips, size slider, LONG/SHORT, per-position close, live price refresh. |
| **Signals** | The live signal board: every symbol's 15m and 1h verdict, how many of the 21 strategies agree, the confidence, and — when a symbol does not qualify — the exact gate that blocked it, with the per-strategy votes one tap away. |
| **Control** | Every switch and slider in one place: engine, trading, scanner, periodic report, leverage (1–200x, clamped per symbol), margin per trade (1–100%), report interval — plus scan / signals / funding / tiers / close-all / cancel-all / diagnose / repair. |
| **Settings** | LLM provider, key and model, the persona ("soul"), Bitunix API keys, margin and position mode, PAPER/LIVE switch. *Auto pick model* fetches what the key can see and chooses a default; every setting persists across restarts. |

Bottom navigation, a back arrow in the top bar, and the Android back button all work — the
start menu is never more than one tap away.

## What makes it different

1. **It acts, it doesn't just answer.** The model can call the engine with
   `<tool>status</tool>`, `<tool>funding</tool>`, `<tool>scan once</tool>` and friends. The
   app runs the command, feeds the real output back, and the model answers with facts.
2. **It has a sense of the market's stress.** Volatility, distance from the recent high,
   funding crowding and your own unrealized P&L are combined into a stress score
   (CALM → WATCH → STRESSED → PANIC) with plain-language drivers.
3. **It reads you.** Anxious, angry, exhausted, discouraged, euphoric, confused or focused
   — it acknowledges it once, briefly and honestly, then gets precise. No fake cheerleading.
4. **It repairs itself.** `/diagnose` ranks what is broken; `/repair` applies only the safe
   fixes (re-pick a working model, restart the trading loop, reconnect the feed, clamp
   leverage) and reports exactly what it changed.
5. **It was built against the real API.** Every endpoint was re-checked against the official
   Bitunix docs and the live exchange — which is how the app knows that `tickers` silently
   ignores symbol filters and that `mark_price` is not served at all.

## Install

Grab the APKs from the [Releases](https://github.com/qezawat-a/Agent-Trader/releases) page:

| Asset | What it is |
|---|---|
| `Agent-Trader-debug.apk` | Installable debug build (recommended for sideloading) |
| `Agent-Trader-release.apk` | Signed release build |
| `SHA256SUMS.txt` | Checksums — verify with `sha256sum -c SHA256SUMS.txt` |

## Quick start

1. **Settings → provider** — paste your LLM base URL and API key, then *Test provider*
   (it auto-picks a model the first time) or *Auto pick model* any time.
2. `/models` — the app probes the provider and picks a working model automatically
   (or `/set_model <name>`).
3. **Control → Engine on, Trading on** — paper mode. Watch the loop scan every 30s.
4. Optional: save your Bitunix API key + secret in Settings, then try
   `status`, `positions`, `funding`, `tiers`.
5. Only when you are confident: the **LIVE** switch. It refuses to arm without keys, and no
   live order is ever sent without it.

## Commands

**Agent (slash) commands**

| Command | Purpose |
|---|---|
| `/help` | Full command list |
| `/models` | List and auto-pick a working model |
| `/set_model <name>` | Choose the model |
| `/connect_provider <url> <key>` | Point the app at a provider |
| `/check_ai` | Provider reachability + model round-trip |
| `/price <symbol>` | Live Bitunix last price |
| `/soul`, `/add_soul <text>` | Show / set the persona |
| `/diagnose`, `/repair` | Self-diagnosis and safe auto-repair |
| `/autopsy`, `/verify` | Last failure analysis, engine health |
| `/code_review`, `/bug_fix`, `/simplify` | LLM-assisted engineering help |
| `/add_skill`, `/skill`, `/add_mcp`, `/mcp` | Skills and MCP servers |
| `/think`, `/memory`, `/clear` | Plan, notes, reset |

**Trader commands** (type them in chat, or use the Control/Trade screens)

`engine on|off` · `start trading` · `stop trading` · `scan on|off|once` · `status` ·
`balance` · `positions` · `positions history` · `orders` · `history orders` ·
`cancel orders` · `close position <SYMBOL>` · `close all` · `pnl` · `trades_history` ·
`funding` · `tiers` · `signals` · `settings trade` · `tpsl` · `mid management` ·
`report on|off|interval <min>` ·
`set leverage <1-200>` · `set risk <1-100>` ·
`set universe <BTCUSDT,ETHUSDT,…>` · `set timeframes <15m,1h,…>` (1m–1w, max 5) ·
`set scan top <3-50>` · `set min agree <1-21>` · `set min tf <1-100>` · `set min conf <1-100>` · `manual entry <SYMBOL> <long|short> <pct>`

## How the brain works

```
user message
   ├─ mood detection (anxious / angry / exhausted / ...)
   ├─ live context: mode, balance, positions, market mood
   └─ system prompt = persona + context + capabilities + tool protocol
            ↓
        LLM reply ─── contains <tool>…</tool>? ──► engine runs the command
            ↑                                                 │
            └──────────── second pass with the results ───────┘
```

The second pass is what makes it agentic: the model does not guess the balance or the open
positions, it asks the engine.

## Bitunix integration

Verified against the live API and the official docs (2026-11):

- **Signed REST** — `digest = SHA256(nonce + timestamp + apiKey + sortedQueryParams + body)`,
  `sign = SHA256(digest + secretKey)`; 8 req/s throttle; retries on 10001 / 10005 / 10006 /
  40007; keys never logged.
- **Orders** — `place_order` always sends the required `marginCoin`; `side` names the
  *position* (BUY opens long); `tradeSide` only in hedge mode; TP/SL attached with
  `MARK_PRICE` triggers.
- **Market data** — kline (closed candles only, `endTime`/`startTime` paging, LAST/MARK
  price), tickers, trading pairs (precision, min volume, max leverage), funding rate and
  batch funding rates, depth.
- **Account & risk** — account, available balance, per-symbol trading settings (margin mode
  and leverage are synced to the account before a live order), position tiers (risk bands),
  isolated-margin adjustment, leverage/margin/position mode.
- **Positions & orders** — pending/history positions, pending/history orders, order detail,
  flash close, close-all, cancel one / cancel all, batch orders, modify order.
- **TP/SL** — position-level and partial TP/SL (place, modify, cancel, pending, history).
- **WebSocket** — `wss://fapi.bitunix.com/public/` for market channels and
  `wss://fapi.bitunix.com/private/` for account channels, app-level ping (Unix seconds)
  every 3s, 250ms send pump (4 msg/s under the 5 msg/s cap), reconnect with backoff and
  resubscribe.
  In LIVE mode the app subscribes to `balance`, `position`, `order` and `tpsl` in a single frame.
  The public market feed runs in every mode (no keys needed): `tickers` (24h stats, last
  price) and `price` (market/index price + funding rate) for the whole universe, cached
  silently — public channels never spam the chat, and the engine prefers WS prices over
  REST quotes. All documented channel names are wired and ready:
  `tickers` / `ticker` / `price` / `trade` / `depth_books|book1|book5|book15` on the public
  socket, and klines as `market_kline_*` (e.g. `1m→market_kline_1min`, `1h→market_kline_60min`,
  `1d→market_kline_1day`, `1w→market_kline_1week`, `1M→market_kline_1month`,
  `mark_kline_*` for MARK_PRICE).

### Quirks worth knowing (found by testing, not by reading)

| Quirk | Consequence |
|---|---|
| `tickers` ignores the `symbols` filter | Results are filtered client-side, otherwise `/price BTCUSDT` returns another symbol's price |
| `mark_price` / `index_price` are not served (404) | Mark and index price come from the single-symbol funding endpoint |
| History positions return `{positionList:[…]}` | The array reader falls back to `positionList` / `list` |
| `realizedPNL` excludes fees and funding | Net result is `realizedPNL − fee + funding` |
| Quantities below the exchange minimum | Skipped, never silently upsized |
| Kline returns closed candles only | The forming candle is excluded by design |
| Leverage has no single account-wide max | The authority is each symbol's `maxLeverage` (up to 200x) |

## Trading logic

- 21 strategies: trend, reversion, breakout, reversal and risk filters.
- Nothing is hard-capped: the scanner universe, the timeframes (the official docs
  list exactly 1m/5m/15m/30m/1h/2h/4h/6h/8h/12h/1d/3d/1w/1M — max 5, case-sensitive:
  `1M` is one MONTH), the scan depth and all three
  signal gates are yours to change — in chat (`set …`), on the Control screen, or by
  asking the agent (it can read the config with `settings trade` and edit it for you).
  Everything persists across restarts.
- A **signal** is the verdict of 21 strategies over your configured timeframes
  (default 15m + 1h): each timeframe needs at least **3 strategies
  agreeing at ≥60%**, both timeframes must point the same way, and their combined
  confidence must be **≥70%** (all three gates are configurable). Anything less is
  reported as *no trade*, with the failing
  gate named (both in chat and on the Signals screen).
- The periodic report includes the top signals, not just balance and P&L.
- TP/SL plan: stop at 50% of the way to liquidation, partial TP ladder 1R–4R at 25% each,
  breakeven at 10% ROI, trailing stop at 15% ROI.
- Leverage clamped to each symbol's own maximum from `trading_pairs` (the exchange lists up to
  200x for BTCUSDT/ETHUSDT, 20–125x for most other contracts); quantities floored to the
  symbol's base precision and skipped when below the minimum volume.

## Development

The repo builds with standard Gradle (no wrapper checked in; CI installs Gradle 8.14.3):

```bash
gradle :app:testDebugUnitTest   # 75 JVM unit tests
gradle :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
gradle :app:assembleRelease     # signed when KEYSTORE_* env/secrets are set
```

Versions: AGP 8.13.2 · Kotlin 2.2.21 · Compose 1.11.2 · Material3 1.4.0 · compileSdk 36 ·
minSdk 23 · JDK 17.

Release signing uses repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`. Pushing a tag `v*` (or running the *Release APK* workflow) builds the APKs,
runs the unit tests and publishes a GitHub release with checksums.

## Project layout

```
app/src/main/kotlin/com/codeassist/agenttrader/
├── agent/      AgentBrain (prompt, mood, tools) · SelfRepair · ModelClient · CommandRouter
├── bitunix/    BitunixRest (signed REST) · BitunixWs (public + private feed)
├── trader/     Engine (paper/live) · Signals (21 strategies) · MarketMood
├── ui/         Control + Trade surfaces
└── MainActivity.kt
```

The project is carried twice on purpose: the canonical module lives in `app/` (what Gradle and
CI build), and the root `src/` mirrors it byte-for-byte for on-device CodeAssist builds
(`module.toml` points there). Keep both in sync when editing.

## Safety

- Paper mode is the default; LIVE requires real Bitunix keys **and** an explicit toggle.
- The LIVE switch refuses to arm without credentials.
- No live order is sent without the LIVE toggle, and the brain is instructed never to open or
  close a position that the user did not explicitly ask for.
- Quantities below the exchange minimum are skipped, never upsized.
- API keys are never printed, logged or written into the chat.

## Persistence

Your setup survives restarts: provider URL/key, model, soul, Bitunix keys, PAPER/LIVE,
leverage, margin per trade, engine/scanner/report switches and the report interval are
stored in the app's private SharedPreferences. The only thing that is deliberately NOT
restored is the trading loop — after a restart you tap *Trading* to start trading again.

## Roadmap

- Place the 1R–4R ladder as real partial TP/SL orders after a live fill.
- Public market channels on the WebSocket feed (kline, ticker, depth) to cut REST polling.
- Strategy backtesting harness over the paged kline history.
- Optional on-device code editing: the brain proposes a patch, the app applies it and runs
  the build + tests before accepting it.

## Disclaimer

Trading futures is risky. This is an experiment, not financial advice, and it comes with no
warranty of any kind. Verify every endpoint and parameter yourself before sending real orders.
