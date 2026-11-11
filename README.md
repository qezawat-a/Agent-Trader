# Agent Trader

An agentic AI futures-trading app for Android (Kotlin + Jetpack Compose). It pairs an
on-device chat agent (any OpenAI-compatible LLM) with a Bitunix USDT-M futures engine:
21 trading strategies, multi-timeframe consensus, paper trading by default, and an
explicitly-gated live order path.

## Install

Grab the APK from the [Releases](https://github.com/qezawat-a/Agent-Trader/releases) page:

- `Agent-Trader-debug.apk` — installable debug build (recommended)
- `Agent-Trader-release.apk` — signed release build
- `SHA256SUMS.txt` — checksums

## Features

- **Agentic chat** — talk to an LLM (OpenAI, OpenRouter, DeepSeek, Gemini-compat, ...)
  with multi-turn memory, a configurable persona ("soul"), and slash commands:
  `/help /models /set_model /connect_provider /check_ai /price /clear /code_review
  /bug_fix /simplify /verify /diagnose /autopsy /think /add_skill /skill /add_mcp /mcp ...`
- **Bitunix futures engine** (verified against the live API, 2026-11)
  - Signed REST: `digest = SHA256(nonce + timestamp + apiKey + sortedQueryParams + body)`,
    `sign = SHA256(digest + secretKey)`; 8 req/s throttle; retries on 10001/10005/10006/40007
  - `place_order` always sends the required `marginCoin`; `side` names the POSITION
    (BUY opens long); `tradeSide` only in hedge mode; TP/SL attached with `MARK_PRICE`
  - Market data: kline (`15m`/`1h`, closed candles only, `endTime` paging,
    LAST/MARK price), tickers, trading pairs (precision/min volume),
    funding rate + batch funding rates
  - Account/positions/orders history, flash-close, close-all, per-symbol
    trading settings (margin mode + leverage synced before live orders)
  - Chat commands: `status balance positions orders trades_history pnl funding
    scan once report ...`
  - WebSocket on `wss://fstream.bitunix.com/ws` — app-level ping every 3s, 250ms send
    pump (4 msg/s), reconnect with backoff, resubscribe on reconnect
  - **Live private feed**: in LIVE mode the app logs in over WebSocket and subscribes
    to `balance`, `position`, `order` and `tpsl` in one frame — position/order/TP-SL
    updates stream into the chat
- **Trading logic** — 21 strategies (trend, reversion, breakout, reversal, risk filters),
  15m+1h consensus gate, TP/SL plan: stop at 50% toward liquidation, partial TP ladder
  (1R–4R @ 25%), breakeven at 10% ROI, trailing stop at 15% ROI
- **Safety** — paper mode by default; live mode requires real keys + explicit toggle;
  live orders are clamped to each symbol's max leverage; quantities below the
  exchange minimum are skipped, never upsized
- **Tests** — 44 JVM unit tests cover the Bitunix signature (vectors computed
  independently), provider URL normalization, qty/price formatting, funding
  reports, net P&L and the live-feed event format; CI runs them on every release build

## Quick start (in the app)

1. **Settings → provider**: paste your LLM base URL + API key → *Test provider*
2. `/models` → picks a working model automatically (or `/set_model <name>`)
3. Chat: *engine on → start trading* (paper mode) and watch the loop run every 30s
4. Optional: save Bitunix API keys, then `status`, `positions`, `trades_history`
5. Only when confident: Settings → LIVE switch (real orders!)

## Build

The repo builds with standard Gradle (no wrapper; CI installs Gradle 8.14.3):

```bash
gradle :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
gradle :app:assembleRelease  # signed when KEYSTORE_* env/secrets are set
```

Versions: AGP 8.13.2 · Kotlin 2.2.21 · Compose 1.11.2 · Material3 1.4.0 · compileSdk 36 · minSdk 23

Release signing uses repo secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`. Pushing a tag `v*` (or running the *Release APK* workflow) builds and
publishes a GitHub release with the APKs.

## CodeAssist layout note

The repo carries the project twice, intentionally: the canonical module lives in
`app/` (Gradle/CI), and `src/` at the root mirrors it byte-for-byte for on-device
CodeAssist builds (`module.toml` at the root points there). Keep both in sync when
editing; `app/` is what CI builds.

## Disclaimer

Trading futures is risky. This is an experiment, not financial advice. The paper
engine is the default and no live order is ever sent without the LIVE toggle.
