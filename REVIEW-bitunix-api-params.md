# Bitunix API parameter audit — 2026-11-20

Audit of every REST path, request parameter, required flag and rate limit in
`bitunix/BitunixRest.kt`, and of the WebSocket channel/frame contract in
`bitunix/BitunixWs.kt`, against the official Bitunix documentation.

**Reference used**

- `bitunix-endpoint-parameters` skill — generated 2026-11-18 from 50 official
  doc pages (50 endpoints/WS channels/articles).
- Four pages the extraction missed were fetched directly from bitunix.com on
  2026-11-20 (see "The reference is missing four endpoints" below).

**Scope**: 33 REST endpoints, 12 WebSocket channels, 1 WS login/ping contract,
the error-code table and every documented rate limit.

**Result**: 8 discrepancies found and fixed in code. The remaining gaps are
deliberate, documented project deviations.

---

## Fixed

| # | Endpoint | Doc says | Code did | Now |
|---|---|---|---|---|
| 1 | `POST trade/modify_order` | `qty` required ("Amount (base coin)") | `qty` optional → a modify without it | `require(qty != null)` |
| 2 | `POST account/adjust_position_margin` | "Either side or positionId required" | neither enforced | `require(side || positionId)` |
| 3 | `POST trade/place_order` | `effect` "Required if the orderType is limit" | a LIMIT order could go out with no `effect` | `effect` defaults to the documented `GTC` for LIMIT |
| 4 | `POST trade/batch_order` (per item) | same as above | same gap | same fix |
| 5 | `GET account/trading_settings` | Rate limit 20 req/sec/uid | rate map said 10 | 20 |
| 6 | `GET account/position_mode` | Rate limit 20 req/sec/uid | missing from the rate map | 20 |
| 7 | `POST trade/cancel_orders` | the page states **no** maximum for `orderList` | comment attributed the 20-order cap to the docs | comment corrected — the cap stays as the exchange's live-verified limit |
| 8 | — | — | compiler warning "Condition is always 'true'" after guard 1 | `qty` smart-cast used directly |

Changes are in both mirrored trees (`app/src/…` is the Gradle module,
`src/src/…` is the IDE tree; the two were byte-identical before and after).

---

## Verified unchanged

- **Paths**: every path the client calls matches the official path verbatim,
  including the non-obvious ones — `/market/get_funding_rate_history`
  (not `/market/funding_rate_history`), `/cp/asset/query`,
  `/tpsl/position/place_order`, `/tpsl/position/modify_order`.
- **Parameters**: every documented parameter is sent where it applies, and no
  parameter the docs dropped is still on the wire (notably `startTime` on
  Get Kline, removed by the 2026-10-27 change log).
- **Enums**: `side` BUY/SELL · `tradeSide` OPEN/CLOSE · `orderType`
  LIMIT/MARKET · `effect` IOC/FOK/GTC/POST_ONLY · `tpStopType`/`slStopType`
  MARK_PRICE/LAST_PRICE · `tpOrderType`/`slOrderType` LIMIT/MARKET ·
  `marginMode` ISOLATION/CROSS · `positionMode` ONE_WAY/HEDGE ·
  `type` LAST_PRICE/MARK_PRICE · depth gears 1/5/15/50/max ·
  kline intervals 1m…1M · order status FILLED/CANCELED/PART_FILLED_CANCELED/
  EXPIRED.
- **Required flags**: `symbol`+interval on Get Kline · `symbol`+marginCoin on
  Get Single Account · `marginCoin`+symbol+leverage on Change Leverage ·
  `marginMode`+symbol+marginCoin on Change Margin Mode · `positionMode` on
  Change Position Mode · `symbol`+positionId on the position TP/SL endpoints ·
  "at least one of tpQty/slQty" and "at least one of tpPrice/slPrice" on the
  partial TP/SL endpoints · orderId **or** clientId on order detail, modify
  and cancel.
- **Rate limits**: close_all_position 1/s · batch_order 2/s ·
  adjust_position_margin and flash_close_position 5/s · the trade and TP/SL
  write endpoints 20/s · the read endpoints 10/s · market endpoints 10/s
  (the client keeps a stricter global 8/s cap).
- **Error codes**: the retryable set (10001, 10005, 10006, 10009, 40007) is a
  subset of the documented table, and business errors (20003, 10002, 30017)
  are correctly not retried.
- **WebSocket**: public `wss://fapi.bitunix.com/public/` and private
  `wss://fapi.bitunix.com/private/` hosts · channels price, ticker, tickers,
  trade, depth_books/book1/book5/book15, market_kline_*/mark_kline_* (all 28
  documented channel names) · private balance, order, position, tpsl · the
  subscribe/unsubscribe frame shape · login args (apiKey, timestamp, nonce,
  sign) with the timestamp in **seconds** · the ping frame carrying a
  Unix-seconds timestamp · 5 messages/second per connection · 300 channel
  subscriptions per connection.

## Deliberate deviations (kept)

| Deviation | Why |
|---|---|
| `marginCoin` on the trade endpoints (place_order, cancel_orders, cancel_all_orders, flash_close_position, and the query endpoints) | Not in the docs; live-verified as required by the exchange, and unknown keys are ignored by the API. Flagged in the file header. |
| REST kline interval `3m` | Not in the REST doc list; live-verified. The WS list *does* document `market_kline_3min`. |
| `cancel_orders` capped at 20 orders | The current doc page states no maximum; the cap is the exchange's live-verified limit. |
| `place_order` sends `tradeSide` only in hedge mode | The doc marks it required but its own note says "Only required in hedge-mode"; one-way mode omits it. |

---

## The reference is missing four endpoints

The skill's extraction covered 50 doc pages and skipped four endpoints that
exist in the official sidebar. All four are already implemented **correctly**
in the client; the gap was in the reference, not the code.

**The reference has been patched** (2026-11-20): the four endpoints below were
added to `bitunix-endpoint-parameters/SKILL.md` as endpoints 51-54, with the
source count updated to 54 and a note recording the manual addition.

| Endpoint | Method & path | Parameters | Rate limit |
|---|---|---|---|
| Get position mode | `GET /api/v1/futures/account/position_mode` | none | 20 req/sec/uid |
| Get trading settings | `GET /api/v1/futures/account/trading_settings` | `symbols` (comma-separated, max 50, optional) | 20 req/sec/uid |
| Transfer asset, main → sub | `POST /api/v1/cp/asset/transfer-to-sub-account` | `amount`, `assetType` (FUTURES/SPOT) | 10 req/sec/uid |
| Transfer asset, sub → main | `POST /api/v1/cp/asset/transfer-to-main-account` | `amount`, `assetType` (FUTURES/SPOT) | 10 req/sec/uid |

Doc pages: `account/get_position_mode.html`, `account/get_trading_settings.html`,
`copyTrading/asset/transfer_asset_from_main_account_to_sub_account.html`,
`copyTrading/asset/transfer_asset_from_subaccount_to_main_account.html`.

---

## Tests

`BitunixDocParamsTest` grew from 21 to 25 tests. The new ones assert the
documented required flags (guard 1–4) and the two corrected rate limits.

```
/data/workspace/gradle/gradle-8.14.3/bin/gradle :app:testDebugUnitTest --offline
→ BUILD SUCCESSFUL — 383 tests, 0 failures, 0 skipped
```

Note: the `gradle` on PATH here is 4.4.1 and cannot read this project's
Kotlin-DSL settings (`include(":app")` is ignored, "No sub-projects"). Use the
8.14.3 distribution above, or restore the Gradle wrapper scripts, which are
missing from the repository.
