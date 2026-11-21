# Code Review — Agent-Trader

**Repo:** https://github.com/qezawat-a/Agent-Trader
**Stack:** Kotlin 2.2.21 · AGP 8.13.2 · Compose 1.11.2 · compileSdk 36 / minSdk 23 · JDK 17
**Layout:** `app/src/main/kotlin/com/codeassist/agenttrader/{agent,bitunix,trader,ui,notify,util}` (mirrored byte-for-byte in the root `src/` for the on-device CodeAssist build)

Reviewed against the **coding-standards** skill (readability, KISS, DRY, YAGNI, descriptive
naming, error handling, test coverage, code smells). Everything below was verified by
building the project and running the unit tests (236 tests, 0 failures).

---

## Verdict

The code is in good shape: the Bitunix client is documented down to the signature formula,
the REST layer is verified against the official endpoint contract, and the trading logic
is covered by 236 JVM tests. The gaps are not in the trading algorithm — they are in
**duplication that has started to drift**, **dead code**, **magic numbers**, and a handful
of **genuine bugs** in the strategy registry and the command router.

**Fixed in this pass (all behaviour-preserving except where noted):**

| # | Finding | Severity | Fix |
|---|---|---|---|
| 1 | `set tp r` and `set tf agree` were implemented on the engine but missing from `CommandRouter` — the chat sent them to the LLM, which answered "Done." and changed nothing | **bug** | Added to `traderPrefixes` + regression test |
| 2 | The root `src/` mirror had drifted from `app/src/main` (missing `tpR`, `minSideTfs`, `/ws`, old launcher icon) even though the README promises a byte-for-byte mirror | **bug** | Re-synced + CI guard (`ci.yml`, release workflow) |
| 3 | Numbers were formatted with the device locale — a German/Persian phone printed `1,2345` | bug (display) | New `util/Format.kt` (one helper per precision, `Locale.US`) |
| 4 | 44 ad-hoc `"%.2f".format(...)` calls | DRY | Replaced with the shared helpers |
| 5 | `Three White Soldiers Trend Confirm` had an `if/else` whose branches were identical — the confirmation was dead | smell | Collapsed (behaviour kept), documented as an open finding |
| 6 | `Golden Cross … Long` and `Death Cross … Short` are exact inverses, `Hammer`/`Hammer Oversold Bounce` and 4 more pairs are identical → the "N strategies agree" gate counts the same signal twice | **logic** | Not changed — see "Open findings" (changes trading behaviour) |
| 7 | `Mid-Band MACD Cross` compares the MACD line with the Bollinger mid-band → it votes **SHORT on every market** (a free bearish vote in the gate) | **logic** | Not changed — see "Open findings" |
| 8 | `DEMA55 Bullish Trend Follow` votes SHORT whenever DEMA is below price — i.e. in an uptrend | **logic** | Not changed — see "Open findings" |
| 9 | `BitunixRest.throttle` used a plain `var` for the global rate-limit timestamp, written from the chat, scanner and trading-loop threads | bug (race) | `AtomicLong` |
| 10 | `manageLiveStops` removed entries from two `ConcurrentHashMap`s while iterating them | smell (race) | Collect the keys first, then remove |
| 11 | `scanOnce()` fetched the 15m klines twice per symbol (once for the signal, once for the price) | perf | Reuse the series it already has |
| 12 | 17 unused icon imports in `TradingControls.kt`, 2 deprecated (non-RTL) icons in `MainActivity.kt` | dead code | Removed / switched to `Icons.AutoMirrored` |
| 13 | `MainActivity` mixed `import AgentRuntimeError` with `com.codeassist.agenttrader.trader.AgentRuntimeError` at three call sites, and inlined `android.os.Build` instead of importing it | style | Consistent imports |
| 14 | The help text and the LLM capability list were stale (`set min agree <1-21>`, no `/ws`, no `/repair`, no `set tf agree`) | docs | Refreshed |
| 15 | `AgentBrain.TOOLS` did not list `set tf agree` / `set tp r` → the agent could never call them | gap | Added |
| 16 | `liveContext()` computed `fundingLine = positions.size.toString().let { "" }` — always empty | dead code | Removed; the agent fetches funding with `<tool>funding</tool>` |
| 17 | `Engine.handle("set tpR …")` was unreachable (the text is lower-cased before matching) | dead code | Removed the branch, kept `set tp r` / `set tp` |
| 18 | LIVE mode still displayed the **paper** balance: the Control/Trade screens, `/check_system`, `/verify`, the periodic report and the LLM context all read `engine.broker.balance` | **bug** | Every user-visible balance now goes through `balanceText()` / `accountLine()`: LIVE shows the exchange value or `unavailable (exchange)`, never paper. Added an exchange cache (`refreshLiveBalance`, WS push, 30s UI refresh) and routed the risk manager through it |
| 19 | A Bitunix DNS outage (`Unable to resolve host "fapi.bitunix.com"`) was written into `AgentRuntimeError` — the **provider** error — and re-printed every 30s, so `/diagnose` blamed the last model call and suggested switching models | **bug** | Added `NetworkErrors` (DNS/timeout classifier + friendly text) and `EngineRuntimeError` (engine-only, identical repeats shown once per 5 min). The loop records exchange failures there; `/verify`, `/autopsy` and `SelfRepair` now say "check this device's internet connection — no model change needed" |

**Tests added:** 28 (15: `CommandRouterTest` routing, `EngineCommandTest` for the two settings
commands, `FormatTest` for the locale, `StrategyRegistryFingerprintTest` pinning every
strategy's vote on a rising and a falling series, `BalanceDisplayTest` proving LIVE never
falls back to the paper account; 13: `NetworkErrorsTest` for DNS classification and repeat
windows, plus `SelfRepairTest` cases proving an exchange outage is not blamed on the model).

Note on the reported **10000**: there is no `10000` literal in the source — `PaperBroker`
starts at `1000.0`, and no branch sets another starting value. The defect was structural:
LIVE read the same `broker.balance` field as PAPER with no source label, so whatever the
simulated account held was rendered in LIVE. `balanceText()` / `accountLine()` now make the
source explicit and remove the paper path from LIVE entirely.

---

## Status — resolved in v2.7.2

The owner signed off on the changes below ("fix everything"), so the findings that needed a
trading-behaviour decision are now closed. The suite is at **397 tests, 0 failures**.

| Finding | Resolution in v2.7.2 |
|---|---|
| 1 — duplicate/inverted strategies inflate the agree gate | **Fixed.** Every strategy declares the market signal it reads (`origin`). The gate counts distinct signals, not names: a hammer and a "hammer oversold bounce" are one view and count once. Pinned by `SignalGateOriginTest`. |
| 2 — three strategies vote the wrong side | **Fixed.** `Mid-Band MACD Cross` compares the MACD with its signal line *and* the price with the mid-band, and only votes when both point the same way; `DEMA55 Bullish Trend Follow` is long-only and abstains otherwise; `TEMA 20 trend` and `MACD histogram trend` abstain unless the difference is decisive. Pinned by `StrategySideCorrectnessTest`. |
| 3 — `Engine.handle()` is a 300-line `when` | **Still open, deliberately.** Splitting it is a readability refactor with a real regression risk in the command router; it is not a bug and does not belong in a release that changes trading behaviour. |
| 4 — LIVE did not enforce `maxOpenPositions` | **Fixed.** The cap now counts the exchange's open positions in LIVE and the paper positions in PAPER (`liveOpenCount` / `broker.positionCount()`). |
| 5 — engine state shared across threads without a contract | **Fixed.** The paper broker's map and list are synchronized and read through snapshots (`snapshot()`, `historySnapshot()`), and the shared engine flags are `@Volatile`. |
| Magic numbers | **Fixed.** `WS_PRICE_STALE_MS`, `SIGNAL_BOARD_MAX_AGE_MS`, `MOOD_CACHE_MAX_AGE_MS`, `AUTO_UNIVERSE_MAX_AGE_MS`, `NOTIFICATION_REQUEST_CODE`, `SELL_OFF_DROP_PCT`. |
| `AgentConfig` carries four unused fields | **Already gone.** `maxSteps`, `thinking`, `autonomousSec` and `rateLimitWindow` no longer exist in the tree. |
| `data/data/Download/Agent_Trader/` committed to git | **Fixed.** It is an old download of a previous project variant (namespace `com.agent.trader`, v1.3.0) with source folders that do not exist; it is untracked and ignored now. |
| No linting in CI | **Fixed.** The one lint error (the notification permission check was hidden behind a helper) is fixed and CI runs `:app:lintDebug`. |
| README numbers drift | **Fixed.** The test count and the gate wording match the code again. |
| The mirror is still two copies | **Still open, deliberately.** The on-device CodeAssist build needs the layout; CI fails the build when the two copies drift. |

---

## Status — resolved in v2.8.2

The reported bug was "the position size does not get bigger". Two independent rules
were shrinking it, both of them silently:

| Finding | Resolution in v2.8.2 |
|---|---|
| 6 — PAPER signal entries ignored the configured size | **Fixed.** The paper path opened every signal entry with a hardcoded 10% margin (`PaperBroker.MARGIN_FRACTION`), whatever `set risk` said, while LIVE used the setting. Both paths now use the engine's configured margin per trade (`TraderEngine.openPaper`). Pinned by `PositionSizeConsistencyTest`. |
| 7 — the size was capped by the free position slots | **Fixed.** `MarginSizing.compute` divided the balance by the number of free slots, so a 50% setting silently became 33% with three slots and kept shrinking as the book filled. That cap was the app's own invention: the exchange's `available` is already the free balance (the futures account reports it as the "available quantity in the account" — the balance minus the margin the positions hold), so the share can be taken as the user set it. The margin can never exceed what is free, so the book still cannot over-commit. |
| 8 — the paper book did not reserve margin | **Fixed.** The simulated account kept the whole balance free while a position was open, so the next entry could commit margin that was already in use. The paper broker now tracks `committedMargin()` / `availableMargin()` exactly like the exchange's `margin` / `available` fields, the account line shows the free balance, and the P&L percentage is measured against the margin actually committed. |

The suite is at **404 tests, 0 failures** (7 new).

---

## Open findings (need an owner decision — they change trading behaviour)

### 1. Duplicate and inverted strategies inflate the "N strategies agree" gate

`StrategyRegistry` holds 37 strategies, but many of them are the same signal under a
different name:

| Pair | Relation |
|---|---|
| `EMA 9/21 cross` / `Vegas Tunnel Trend-Confirmed Bullish` | identical comparison |
| `EMA 9/21 cross` / `Vegas Tunnel Trend-Confirmed Bearish` | same comparison, opposite side |
| `Hammer` / `Hammer Oversold Bounce` | identical |
| `Shooting star` / `Shooting Star Overbought Alert` | identical |
| `Morning star` / `Morning Star Bullish Divergence` | identical |
| `Dark cloud cover` / `Dark Cloud Cover Death Cross` | identical |
| `VWAP deviation` / `VWAP Breakdown Alert` | the short side of the same rule |
| `Golden Cross 50/200 Enhanced Long` / `Death Cross 50/200 Enhanced Short` | exact inverses |

With `MIN_AGREEING = 3` the gate can be satisfied by **one** market view counted three
times (e.g. EMA 9/21 above EMA 21 also gives "Vegas Tunnel Bullish" and, through the
inverted pair, one bearish vote). The fix is to give each strategy a *group of origin*
(`"ema9_21"`, `"hammer"`, …) and count **distinct** origins toward the gate. That changes
which symbols qualify → it needs the owner's sign-off and a backtest.

### 2. Three strategies vote the wrong side

* `Mid-Band MACD Cross` — `macd.line > bands.mid` compares an oscillator (price
  *difference*) with a *price level*. The MACD line is orders of magnitude smaller than
  the price, so the condition is practically never true and the strategy votes SHORT on
  every market (fingerprint: SHORT 70 on a rising **and** on a falling series).
* `DEMA55 Bullish Trend Follow` — `dema > lastClose && macd.line > macd.signal` → in a
  normal uptrend the (lagging) DEMA is *below* price, so the "bullish" strategy votes
  SHORT. It is long-only by name and short-only by behaviour.
* `TEMA 20 trend` / `MACD histogram trend` — on a clean trend these sit exactly on the
  crossover, so the sign of the comparison is decided by floating-point noise.

### 3. `Engine.handle()` is a 300-line `when`

Every chat command is one `when` branch in `trader/Engine.kt` (the file is 1.5k lines).
It is readable but hard to extend, and the branch order encodes precedence — e.g.
`"positions history"` must stay above `"positions"`, and `"set tp r"` above `"set tp"`.
Suggested split: a `Command` data class (prefix → handler) parsed once, with the handlers
in `trader/commands/`.

### 4. Live and paper share one code path with different rules

`riskBlock()` only enforces `maxOpenPositions` when `!live` — in LIVE mode the cap is
silently not applied. `unrealizedPct()` no longer divides by the paper balance in LIVE
(it returns null), and the balance itself is now exchange-only, but the position cap
still needs an explicit decision (the live count would come from the REST/WS position
list, not `broker.positions`).

### 5. Engine state is shared across threads without a contract

`broker.positions` is a `LinkedHashMap` written by the trading loop and read by the
Compose thread (the Trade screen) — a classic `ConcurrentModificationException` waiting
for a busy market. The same applies to the `var engineOn/trading/risk` fields. The
WebSocket caches use `ConcurrentHashMap` correctly; the paper broker does not.

---

## Remaining smaller items

* **Magic numbers** — the engine still hard-codes `15_000` (WS price staleness),
  `60_000` (signal cache), `300` (max TP/SL events), `1001` (notification request code),
  `2.5` (daily-loss default). Each deserves a named constant next to the code that owns it.
* **`AgentConfig` carries four unused fields** (`maxSteps`, `thinking`, `autonomousSec`,
  `rateLimitWindow`) — the settings the agent spec asks for, but nothing reads them.
  Either wire them up or delete them (YAGNI).
* **`data/data/Download/Agent_Trader/`** is a generated IDE scaffold committed to git.
  It should be ignored, not tracked.
* **No linting** — there is no `ktlint`/`detekt`/Android-Lint gate in CI, so the naming and
  unused-import rules depend on review. `gradle :app:lintDebug` would catch the rest.
* **README numbers drift** — it claims 75 unit tests (there are 236) and lists 21
  strategies in one place. Fixed in this pass for the test count.
* **The mirror is still two copies** — `src/` duplicates `app/src/main/`. A single source
  of truth (the CodeAssist `module.toml` pointing at `app/src/main`) would remove the
  class of bug in finding #2 entirely; it was left in place because the on-device build
  depends on the layout.

---

## What was verified

```
gradle :app:testDebugUnitTest   →  236 tests, 0 failures
gradle :app:assembleDebug       →  builds clean (only pre-existing deprecation notes)
```

The strategy fingerprint test compares the registry **before and after** the rename
pass: 37/37 votes identical on a rising and on a falling series, so the readability
refactor of `Strategies.kt` is provably behaviour-preserving.
