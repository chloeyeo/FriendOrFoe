# FOF-1 — radio-silent friend-or-foe (owner: stream 2, Ihan)

Status: **draft v0.1 (skeleton)** — the layouts below are frozen by `eval/gen_vectors.py` (the executable
reference) and `firmware/iff/src/fof1.h`; prose, threat table and tuning are stream 2's to finish. Change the
layouts only together with the generator, the header and this file, in one `[shared]` PR.

## 1. Goal and roles

A tank crew's phone sees a drone in the camera and wants one answer: friend or foe, on that object, within
seconds, with the drone transmitting nothing until it is asked. Two gadgets run the protocol:

- **reader** (observer side, USB to the phone): builds one authenticated challenge, sends it on the radio, verifies
  the answer, reports `verdict + reason + RSSI + response time + nonce` to the phone (docs/GADGET_LINK.md).
- **drone** (responder side): answers only an authentic challenge, directionally and at short range; carries the
  demo attack modes (Honest / Replay / Relay / Late / Silent / Impostor).

The phone re-checks the physics (round trip, RSSI, nonce freshness, attribution to the tapped box) and draws.

## 2. Keys

| Key | Derivation | Who holds it |
|---|---|---|
| master | 32 random bytes, pre-placed in both gadgets' NVS/EEPROM | both gadgets (never the phone, never the repo) |
| dayKey | `HMAC-SHA256(master, "FOF-day" ‖ "YYYYMMDD")`, UTC date of the challenge's `t` | derived on demand |
| droneKey | `HMAC-SHA256(dayKey, "FOF-drone" ‖ droneId:u16)` | derived on demand |

Both sides derive the day from the **challenge's `t`**, so clock drift never desynchronises the keys; the responder
simply refuses a challenge whose `t` is more than 24 h from its own clock (yesterday/today/tomorrow). A captured
drone leaks only its own key for one day. Test vectors use the TEST master key `00 01 … 1f`.

## 3. Messages (fixed binary layouts, big-endian; every MAC is over bytes, never JSON)

**Challenge — 31 bytes, observer → responder, one burst**

| offset | field | type | note |
|---|---|---|---|
| 0 | v | u8 | 1 |
| 1 | nonce | 16 B | hardware RNG, fresh per challenge |
| 17 | t | u32 | unix seconds |
| 21 | challengerId | u16 | this observer |
| 23 | cmac | 8 B | `HMAC-SHA256(dayKey, "C" ‖ nonce ‖ t ‖ challengerId)[0:8]` |

**Response — 37 bytes, responder → observer, directional, short range**

| offset | field | type | note |
|---|---|---|---|
| 0 | v | u8 | 1 |
| 1 | droneId | u16 | displayed as `RQ-07` |
| 3 | nonce[0:8] | 8 B | first half of the challenge nonce |
| 11 | lat | i32 | degrees × 1e4 (south negative) |
| 15 | lon | i32 | degrees × 1e4 (west negative) |
| 19 | alt | i16 | metres / 10 |
| 21 | tag | 16 B | `HMAC-SHA256(droneKey, "R" ‖ nonce(16) ‖ t ‖ challengerId ‖ droneId ‖ lat ‖ lon ‖ alt)[0:16]` |

The tag binds the **full** nonce, the time, the challenger and the claimed position, so an answer only works for
the one observer who asked, for that one challenge, at the place the drone claims to be.

## 4. Verify order on the reader gadget

1. **Nonce ledger** — the nonce was issued by us, is unused, and is younger than 10 s → else `FOE · replayed nonce`.
2. **Tag** — recomputes under `droneKey(dayKey(day(t)), droneId)` (constant-time compare) → else `FOE · bad tag`.
3. **Position gate** — distance observer→claimed ≤ 30 m passes (near field, no bearing needed); > 3 km fails;
   between, the bearing must be within ±20° of the observer's heading → else `FOE · position mismatch`.
4. **Timing** — `responseMs` ≤ 2500 → else `FOE · late response`.
5. Nothing in the window → `UNKNOWN · no response`. Silence is never FRIEND.

The responder's only gate: the challenge cmac verifies (an enemy cannot make our drone light up, and cannot
harvest an answer by asking first).

## 5. Re-checks on the phone (`app/.../signal/ReChecks.kt`, unit-tested)

1. nonce never seen before by this phone → `replayed nonce`
2. phone-measured USB round trip **and** the gadget's `responseMs` within the budget (2500 ms) → `late response`
3. RSSI at or above the floor (−70 dBm; skipped with a note when not reported unless strict) → `signal mismatch`
4. the answer is attributed to the box that was tapped (box lock for the 10 s window) — `IffCoordinator`

The stricter verdict wins (FRIEND < UNKNOWN < FOE); the gadget's own FOE reason is kept, the phone's finding
is logged beside it.

## 6. Test vectors

`python eval/gen_vectors.py` → `eval/vectors/*.json` + `firmware/selftest/vectors.h`: friend, friend_south
(negative latitude), friend_midnight_utc (t = 2026-10-10T00:00:00Z, day 20261010), replayed_nonce, bad_tag
(impostor key), position_mismatch (5.2 km), late_response (4000 ms), no_response. The `selftest` sketch replays
them on the board and prints PASS/FAIL per vector; it is green when `fof1.cpp` is done.

## 7. Threat table (skeleton — TODO(stream 2): fill the "how" column with the demo evidence)

| Threat | What the attacker does | FOF-1 answer | Verdict shown |
|---|---|---|---|
| Beacon exposure | listens for a drone that transmits on its own | the drone transmits nothing until an authentic challenge arrives | — |
| Harvest ("ask tiger first") | sends a challenge to collect a valid answer for later | challenges are authenticated with the day key; unauthenticated ones are ignored | — |
| Replay | re-sends an old recorded answer | 16-byte fresh nonce bound into the tag; ledger on gadget and phone | `FOE · replayed nonce` |
| Relay / MITM | forwards the exchange to a far-away friendly drone | position bound into the tag + range/cone gate; round-trip and `responseMs` budgets | `FOE · position mismatch`, `FOE · late response` |
| Attribution | several drones answer, which one is the box? | directional short-range answer, box lock, optical persistence (phone) | — |
| Impostor / key capture | answers with a guessed or captured key | HMAC tag; per-drone, per-day derived keys bound the loss | `FOE · bad tag` |
| Clock drift | the gadgets' clocks disagree | day derived from the challenge `t`; ±24 h tolerance on the responder | — |
| GPS-denied | no fix indoors / jammed | configured positions for the demo; near-field rule < 30 m; TODO production: INS / observer-relative | — |
| Jamming / silence | the drone cannot answer | `UNKNOWN`, never FRIEND; the crew decides with the camera | `UNKNOWN · no response` |

## 8. Production path (documented, not built)

Ed25519 per-drone signatures when the drone must hold no shared secret; key distribution for many observers;
altitude in the gate; on-off-keyed optical answer as a second channel.
