# Pitch — 3 minutes, no slides (plus the 2-minute Q&A card)

Skeleton from README section 2; polish at H14–20 with the measured numbers. Speaker: one person; a second
person drives the drone-side gadget. Tracks: **T1-04 Radio-silent IFF** (main) and **SC1 CLIKA BYOD Offline**.

## Script (≈ 3:00)

**0:00 — The problem (30 s).** Tanks are blind upward and loud; crews cannot see drones coming, and classic IFF
fails four ways: a beacon reveals you, a recorded answer replays, a relayed answer lies about where it is, and an
RF answer cannot be pinned to one drone in the sky.

**0:30 — What we built (20 s).** An Android app on a phone — detection on-device, offline, with ClikaRT — and two
small gadgets. Point the phone at the sky, tap a drone, get a green or red ring *on that object* with a reason.
*(Show the phone mirrored on the laptop; airplane mode visible; `--offline` in the serve window.)*

**0:50 — Live: detect (20 s).** Two boxes appear: `drone · ?` in amber and `person`. Nothing has been transmitted.

**1:10 — Live: FRIEND (30 s).** Tap the drone. The phone tells the gadget over USB; the gadget sends one
authenticated challenge with a fresh nonce; the drone answers once, directionally; the gadget verifies the tag,
the nonce and the position; the phone re-checks timing, signal strength and nonce freshness.
Ring turns green: **`FRIEND · RQ-07 · −58 dBm · 42 ms`**.

**1:40 — Live: attacks (50 s).** Switch the drone gadget to **Replay** → red `FOE · replayed nonce`.
**Relay / Late** → red `FOE · position mismatch` or `FOE · late response`. **Silent** → red `UNKNOWN · no response`:
silence is never friend.

**2:30 — Why it holds (20 s).** Three layers of evidence: crypto proves *who* (HMAC tag with a fresh nonce),
geometry proves *where* (the position is bound into the tag and checked against the box the camera sees),
physics proves *when and how near* (round trip, signal strength).

**2:50 — Close (10 s).** The drone transmitted nothing until asked, and its answer only works for the one observer
who asked, at the place it is actually flying, within the time it should have taken. Everything you saw was built
during the hackathon on top of ClikaRT, public RT-DETR weights and the Arduino libraries.

## Q&A card (ten likely questions — TODO: one-line answers with numbers at H14)

1. **What existed before the hackathon?** ClikaRT 0.6.4 and its Kotlin binding, RT-DETR v2 / OWLv2 weights, the
   Android template, CameraX, usb-serial-for-android, the Arduino core + ArduinoJson/Crypto, FastAPI, scrcpy.
   Everything in the repo — detector integration, tracker, FOF-1 library + vectors + selftest, firmware, USB link,
   UI, dashboard, eval — was built during.
2. **Why symmetric HMAC and not signatures?** Fits 20 hours and tiny MCUs; per-day and per-drone derived keys bound
   a key loss; Ed25519 per-drone signatures are the documented production step.
3. **What if the enemy captures a drone?** It holds only its own key for one day; it cannot impersonate another
   drone or any observer.
4. **Replay — exactly how is it stopped?** A fresh 128-bit nonce in every challenge is bound into the answer's tag;
   both the gadget and the phone keep a ledger; an old answer is `FOE · replayed nonce`.
5. **Relay / man-in-the-middle?** The claimed position is in the tag and gated against range/bearing; relaying adds
   latency, which both the gadget (`responseMs`) and the phone (USB round trip) budget at 2.5 s.
6. **Radio-silent — but the drone does transmit?** Only after an authentic challenge, once, short-range and
   directional; it never beacons, and an unauthenticated challenge gets nothing.
7. **How is the answer attributed to the drone in the picture?** The tapped box is locked for the window; the
   verdict lands on that box; the position claim must agree with where the camera sees it.
8. **Does it work with no GPS?** The demo uses configured positions and the near-field rule (< 30 m); production
   needs INS or observer-relative positioning — it is the known gap.
9. **Numbers?** TODO: model load time, ms/frame and fps on the phone, APK size, precision/recall from the eval,
   challenge-to-ring latency.
10. **Offline, really?** Airplane mode on the phone, the laptop on the hotspot LAN only, ClikaRT with an offline
    license bundle, models pushed over USB; the judges can turn the hotspot off and the ring still appears.
