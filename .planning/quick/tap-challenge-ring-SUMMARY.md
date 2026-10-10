# Quick task: tap a drone box, challenge it, draw the answer on that box: Summary

**Date:** 2026-10-10 · **Phase:** 5 pulled forward (OBS-02, SIG-01..04, PHY-01/02, OBS-10) · **Branch:** `chloe-tap-challenge-ring` (worktree `FieldReport-wt-iff`; git refused `chloe/tap-challenge-ring` because a branch `chloe` exists)

## What works

- **The tap runs the whole challenge.** Tap a drone box: it locks for 10 s and the ring shows `drone · challenge N s`. The challenge goes to the signal source (the USB gadget by default), the phone re-checks the answer (`ReChecks`: nonce never seen, round trip and `responseMs` within 2.5 s, RSSI above -70 dBm), and the result is drawn on the tapped box: green `FRIEND · RQ-07 · −58 dBm · 42 ms`, red `FOE · <reason>`, or red `UNKNOWN · <reason>`. The verdict stays up for 8 s, then the box returns to `drone · ?` and can be tapped again. A second tap during a challenge is refused. Detection is untouched, so main's fast drone and person detection still runs.
- **Fails closed (OBS-02).** The default signal choice is now **USB**. With no gadget and demo mode off, a tap gives `UNKNOWN · no gadget` at once and never a scripted answer. `Auto` (gadget if attached, else fake) and `Fake` are explicit opt-ins.
- **Fake answers are always visible as fake.** A verdict from `FakeSignalSource` ends its ring tag with `· FAKE` (`FRIEND · RQ-07 · −58 dBm · 180 ms · FAKE`). The gadget panel row says FAKE, the event log line ends `(fake)`, and dashboard events carry `"source":"FAKE"`. The status strip shows a red **FAKE SIGNAL** badge whenever the next tap would go to the fake (`Fake` selected, or `Auto` with no gadget).
- **Answers stay on the box they belong to (SIG-03).** The challenge line carries a per-challenge `seq`, and the reader is expected to echo `seq` and `boxId` in its answer (docs/GADGET_LINK.md). The firmware skeleton is updated to do this. On the phone, `signal/AnswerRouter`:
  - drops an answer whose `seq` or `boxId` echo differs from the open challenge;
  - after a challenge ends without its answer (window passed, Reset), refuses the next challenge with `UNKNOWN · gadget busy` until that answer arrives (it is then swallowed) or 11.5 s after its write. This covers the gap where the gadget's own 10 s `UNKNOWN · no response` lands just after the phone's 10 s window;
  - for firmware without the echo, drops any answer whose `responseMs` is longer than the time since this challenge was written, plus 300 ms.

  Late answers are logged and their nonces are spent in the phone's ledger, but they are never drawn.
- **Malformed gadget lines are skipped and the link stays up.** A wrong-typed field (`"responseMs":"fast"`, `true`, an object), a negative `responseMs`, an out-of-range `rssiDbm` or a non-numeric `seq`/`boxId` is logged as `gadget line skipped` and the reader carries on. The USB reader also catches any exception per line.
- **One ring per drone.** The locked box and boxes holding a verdict are pinned in the tracker past the 5 s drone window, so the drone's next detection re-matches the same track id instead of drawing a second `drone · ?` ring beside a ghost.
- **Reset (OBS-10)** cancels the challenge in flight. Its answer never lands, and the state comes back clean.

Tests: 216 unit tests, 0 failures (`./gradlew :app:testDebugUnitTest --offline`). New this round: `AnswerRouterTest` (10), plus codec, tracker, overlay, JSON, settings, switching-source and coordinator cases. `:app:assembleDebug --offline` is green (APK about 297 MB).

## How to test on the phone without the gadget (scripted answers)

Install over USB, then launch the live camera with the fake signal source explicitly selected:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.chloeyeo.friendorfoe/.MainActivity --ez demo false --es signal fake --es fake_mode honest
```

1. The status strip shows **FAKE SIGNAL**. Point the camera at a drone (or a drone picture) until an amber `drone · ?` box appears.
2. Tap the box. The ring counts down `challenge 10 s…`, and about 0.2 s later turns green `FRIEND · RQ-07 · −58 dBm · <rtt> ms · FAKE`. The event log shows `challenge · box N`, `challenge sent (fake, HONEST)`, and the FRIEND line ending `(fake)`.
3. Change the scripted answer per tap with Settings → Gadget, or relaunch with `--es fake_mode X`:
   - `replay` gives FOE replayed nonce
   - `relay` gives FOE position mismatch
   - `late` gives FOE late response (after 3.5 s)
   - `impostor` gives FOE bad tag
   - `weak` gives FOE signal mismatch
   - `silent` gives UNKNOWN no response after 10 s
4. Fail-closed check: relaunch with `--es signal usb` and no gadget attached. A tap gives `UNKNOWN · no gadget` immediately, and the FAKE SIGNAL badge is gone.
5. Reset during a countdown: the ring disappears and no verdict lands afterwards.

Demo mode (`--ez demo true`, the default on a fresh install) is still the scripted screens without a camera. Use `--ez demo false` for the live camera plus tap. Settings saved on the phone persist, so `signal fake` stays selected until it is changed back.

## What needs the real gadget (not verified, no adb was used in this task)

- **USB enumeration and permission:** the board's VID:PID, whether it is in usb-serial-for-android's default prober (an unknown device logs its VID:PID), the permission prompt, and a manifest device filter to skip it.
- **DTR reset on open:** if opening the port reboots the board, the `{"cmd":"status"}` sent on connect may be lost.
- **Baud:** 115200 unless the firmware says otherwise.
- **Firmware echo:** the reader must put the challenge's `seq` and `boxId` into every answer, including its 10 s timeout answer (see the TODO in `firmware/reader/reader.ino`). Without the echo the phone falls back to the busy guard and the age check, which still keep a stale answer off the next box.
- **Timing and RSSI:** real round trips against the 2.5 s budget, and real RSSI against the -70 dBm floor.
- **Unplug and re-plug mid-challenge:** expected `UNKNOWN · gadget unplugged` / `gadget disconnected`.
- **One USB-C port:** use adb over Wi-Fi while the gadget is plugged in.
- **Not done yet:** accepted drone IDs (stored, not enforced), and sending the observer position or heading with the challenge (open question 6 in GADGET_LINK.md).

## Review fixes on this branch (2026-10-10)

| Issue | Outcome |
|---|---|
| AUTO default routes a no-gadget tap to the fake, shown as an unmarked green FRIEND (BLOCKER) | Fixed: the default is USB, and fake verdicts are tagged FAKE on the ring, strip, panel, log and dashboard |
| A stale gadget answer goes to the next tap's inbox (MAJOR, USB source) | Fixed: `seq`/`boxId` echo, busy guard, age check (`AnswerRouter`) |
| A wrong-typed JSON field kills the reader and drops the link (MAJOR) | Fixed: typed number parsing, negative `responseMs` rejected, per-line catch |
| AUTO default contradicts OBS-02 / IFF-03 (MAJOR, Prefs) | Fixed: same fix as the first row |
| Answers not tied to the challenge; Reset leaves the job running (MAJOR) | Fixed: as the second row, plus Reset and window expiry cancel the challenge job |
| A locked or held box is dropped from the tracker after 5 s, giving two rings (MAJOR) | Fixed: pinned ids in `Tracker.update` |

## Commits (on top of the earlier six)

- `e47f8bc` [shared] state: carry the signal source on verdicts, panel rows and events
- `d9b0cb8` signal: tie each gadget answer to its challenge and skip malformed lines
- `a4cee35` signal: default to the USB gadget and tag every fake verdict FAKE
- `9493f15` session: keep the locked drone's track and cancel challenges on reset and expiry

## Adapted to Ihan's reader firmware (2026-10-10, later)

Ihan's firmware (origin/han `5b26d87`, serial interface v2) replaces the draft JSON protocol; `docs/GADGET_LINK.md`
now describes his protocol. `AnswerRouter` (seq echo, busy guard, age check) is gone: every line carries the
phone's unique `request_id` (`<launch prefix>-<seq>-<boxId>`), matched in `signal/ReaderLink`.

- `signal/GadgetLineCodec`: `QUERY <id>` / `STATUS <id>` out; ACK, STATE, TX_CHALLENGE, RX_RESPONSE, RESULT,
  STATUS, DIAG, HELP and legacy text in. `signal/ReaderVerdicts`: reader verdict -> ring (table in GADGET_LINK.md).
- `signal/ReaderLink`: STATUS handshake (1 s retry, 5 s give-up), reboot detection by `boot_id`, wait for STATE
  IDLE before a QUERY (3 s reader cooldown), ACK 2 s, RESULT 5 s after ACK, one STATUS recovery at 3.5 s, nonce
  from TX_CHALLENGE (300 ms grace), stale RESULTs logged only.
- `UsbGadgetSignalSource`: transport only; 303a:1001 opened with DTR and RTS off; `UsbAttachActivity` + device
  filter for the permission. Line limit 1024 bytes.
- `SignalEvent.Answer.nonce` is nullable, `linkRoundTripMs` added ([shared]); RSSI is never reported, so the
  RSSI gate is skipped (keep Strict physical off).
- Tests: 240, 0 failures, including fixtures from Ihan's captures (`app/src/test/resources/reader/`) and an
  end-to-end coordinator test through a fake serial port.
