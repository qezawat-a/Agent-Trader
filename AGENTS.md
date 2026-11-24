# AGENTS.md

Single-module Android app (`:app`, Kotlin 2.2.21 + Compose) that runs an LLM chat agent over a real Bitunix USDT-M futures engine. PAPER is the default; LIVE needs keys plus an explicit toggle.

## Build, test, lint

- No Gradle wrapper. CI uses **Gradle 8.14.3 + JDK 17**; AGP is 8.13.2.
- The `gradle` on PATH in this workspace is 9.9.2, the JDK is 21 and no Android SDK is installed, so local Android builds fail as-is. Install the CI toolchain and an SDK (or set `ANDROID_HOME` / `local.properties`) before running Gradle; do not assume the 9.x on PATH works.
- Commands:
  - `gradle :app:testDebugUnitTest`
  - single class: `gradle :app:testDebugUnitTest --tests "com.codeassist.agenttrader.trader.RiskManagerTest"`
  - `gradle :app:lintDebug`
  - `gradle :app:assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`
- CI (`.github/workflows/ci.yml`) fails on source-mirror drift, unit tests and lint. Tags `v*` trigger the release workflow (`KEYSTORE_*` secrets).
- Unit tests are plain JVM (`kotlin.test` + `org.json`), no Android framework. Keep engine/agent logic pure so it stays testable.

## The mirror rule (most common mistake)

- `app/src/main` is canonical (what Gradle builds). `src/main` is its byte-for-byte mirror for the on-device CodeAssist build (root `module.toml`). Edit `app/`, never `src/` directly.
- `app/src/test` mirrors to `src/test`.
- After edits:

```bash
rsync -a --delete app/src/main/ src/main/
rsync -a --delete app/src/test/ src/test/
diff -rq app/src/main src/main && diff -rq app/src/test src/test
```

- CI fails if either pair differs. Editing only `app/` is the classic drift bug.

## Build config and dependencies

- Add a dependency to `app/build.gradle.kts` **and** to the dependency lists in both `module.toml` and `app/module.toml`; the CodeAssist builds read them.
- `app/debug.keystore` is committed deliberately: one stable debug signature means CI APKs update over an installed build without wiping app data. Do not delete or regenerate it; never commit real release keys.
- Release signing comes only from `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Architecture and command flow

- `MainActivity` wires `TraderEngine(BitunixRest(apiKey, secret))`, `AgentSession` and `Notifier`. There is no DI or navigation framework; screens are Compose.
- `trader/Engine.kt` (~2,900 lines) is the whole paper + live engine. `bitunix/BitunixRest.kt` is signed REST; `BitunixWs.kt` is the public/private feed; `agent/` is the LLM brain, routing and tool authority.
- Trader-looking chat lines are routed by `CommandRouter` directly to `Engine.handle`, bypassing the LLM. A new command needs `CommandRouter` (exact set vs argument prefix) and `Engine.handle`; if the model may call it, add it to `ToolPolicy` (`AgentBrain.TOOLS` is derived from it).
- `Engine.handle` is a 300-line `startsWith` chain: put more specific commands before their prefixes and add a routing test.
- `ToolPolicy` enforces money/risk/lifecycle authority in code; prompts are not a safety boundary.
- Never hold a lock or monitor across an HTTP/WS call. Engine flags are `@Volatile`; shared collections are concurrent/synchronized. This exact bug already froze chat and balance updates.

## Trading invariants

- LIVE state comes from the exchange. Never show the paper balance in LIVE, and never report an SL move until the exchange read-back confirms it (`applyLiveSl`).
- Position size is the configured share of the **free** balance; do not silently shrink it by free slots. `set sl 0` means no stop; never invent a stop or a fraction of liquidation.
- Leverage must fit the symbol's live `get_position_tiers` table; fail closed otherwise. Stops only ever tighten.
- Strategy votes are pinned by `StrategyRegistryFingerprintTest` and `StrategySideCorrectnessTest`; changing a strategy requires updating those expectations deliberately.

## Bitunix API work

- Use the global `bitunix-official-docs` skill for endpoint/parameter tables; do not guess. `REVIEW-bitunix-api-params.md` records the last audit.
- `.codeassist/skills/bitunix-dev/SKILL.md` is a vendored summary, but its `references/` directory is not committed. `.codeassist/skills/bitunix/SKILL.md` describes an unrelated Node.js/Telegram project and does not apply here.
- On this device, `/mnt/sdcard/Android/bitunix-endpoint-parameters` is the older 2026-11-18 reference (50 endpoints); the global `bitunix-official-docs` is newer and includes the four endpoints the old one missed.
- `BitunixDocParamsTest` pins request shapes; extend it with any new parameter or endpoint. `BitunixSignTest` pins signing.
- Keep deliberate doc deviations: `marginCoin` on trade endpoints, REST kline `3m`, the 20-order `cancel_orders` cap, and `tradeSide` only in hedge mode.
- Position TP/SL (`tpsl/position/*`, one per position, closes the whole position) and partial TP/SL (`tpsl/place_order` + `tpQty`/`slQty`) are different endpoints.
- Stops use `MARK_PRICE` (`placeMarketOrder` and the position TP/SL defaults); the README line saying stops use `LAST_PRICE` is stale.
- Exchange quirks: `mark_price`/`index_price` are not served; kline ignores `startTime`; depth only accepts `1|5|15|50|max`; `tpsl/get_pending_orders` mixes position-level and partial orders.
- Repeated `Risk manager: SL on X NOT confirmed on the exchange` comes from the read-back in `applyLiveSl`/`findPositionSlPrice`, not proof the placement was rejected. Inspect the real pending-TP/SL payload before changing retry logic.

## Docs and artifacts

- `README.md` is the main behavior reference (Trading logic, Risk manager, Bitunix integration, Safety). `REVIEW*.md` are historical and sometimes contradictory; later "Status — resolved" sections supersede earlier findings. Their test counts lag (currently 435 `@Test`).
- `agent-trader-chat-understanding.patch` is a stale proposal that no longer applies; do not apply it blindly.
- Settings live in private `SharedPreferences` (`agent_config`), not repo files. Never log API keys or secrets.

## Before finishing

1. Sync both mirrors.
2. `gradle :app:testDebugUnitTest`
3. `gradle :app:lintDebug`
4. `diff -rq app/src/main src/main && diff -rq app/src/test src/test`
