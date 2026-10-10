# Pitch — 3 minutes, no slides (plus the 2-minute Q&A card)

Tracks: **T1-04 Radio-silent IFF** (main) and **SC1 CLIKA BYOD Offline** (judged separately). Judging: Problem Fit
25 %, Military Deployability 30 %, Technical Execution 25 %, Creativity 20 %; we must say what pre-existed and
what we built. Numbers marked ⟨…⟩ are filled in at H14 from the status strip, the eval table and the event log.

**On stage.** *Speaker* talks and holds nothing. *Operator* holds phone A (airplane mode, reader gadget on USB
OTG, mirrored to the laptop with `laptop/mirror.ps1 -Record`) and taps. *Drone operator* holds the drone-side
gadget next to the drone target and switches its mode on cue. The laptop shows the scrcpy window full screen,
with the dashboard (`http://localhost:5180/dashboard`) and the `clikart-cli … serve --offline` terminal one
alt-tab away.

**Before the clock starts.** Phone A: Settings → demo mode **off**, detector ClikaRT, gadget dot green, OFFLINE
badge green. Reset (event log empty, dashboard counts at 0). Drone gadget on **Honest**.

## Script (3:00)

| Time | Speaker says | On screen / who does what |
|---|---|---|
| **0:00 – 0:25** · Problem | "A tank crew sees a drone. Ours or theirs? Today they either guess, or ask on the radio — and a radio question gives away where they are. Classic IFF fails four ways here: a beacon reveals the drone, a recorded answer can be replayed, a relayed answer lies about where it is, and an answer on the air can't be pinned to *one* object in the sky." | scrcpy view of phone A, still. |
| **0:25 – 0:45** · What we built | "This is an ordinary Android phone running the detector on-device with ClikaRT — airplane mode, no network — and two small gadgets that do the challenge and the cryptography. Point, tap, and you get a green or red ring *on that object*, with the reason." | Operator points the phone at the OFFLINE badge, then the sky. |
| **0:45 – 1:05** · Detect | "Two boxes: an amber `drone · ?` and a `person`. Nothing has been transmitted yet — not by the phone, not by the drone. Detection runs at ⟨ms/frame⟩ ms a frame on this phone's CPU." | `drone · ?` amber + `person`. Status strip: detector, ms/frame, model ready. |
| **1:05 – 1:35** · FRIEND | "I tap the drone. The phone asks its gadget over USB; the gadget sends *one* authenticated challenge with a fresh 128-bit nonce; the drone answers once, short-range; the gadget checks the tag, the nonce and the position; the phone re-checks time, signal strength and that it has never seen that nonce. Green: friend, RQ-07, −58 dBm, 42 milliseconds." | Operator taps; countdown arc; ring turns **green `FRIEND · RQ-07 · −58 dBm · 42 ms`**. |
| **1:35 – 1:55** · Replay | "Now the enemy records that answer and plays it back. Red: replayed nonce — that answer belonged to a question nobody is asking any more." | Drone operator → **Replay**; Operator taps → **`FOE · replayed nonce`**. |
| **1:55 – 2:15** · Relay / Late | "Or they relay a real friend's answer from somewhere else. The position is inside the tag, and the relay costs time: red, position mismatch — or late response." | Drone operator → **Relay**, then **Late**; Operator taps each → **`FOE · position mismatch`**, **`FOE · late response`** (⟨ms⟩ ms on the detail line). |
| **2:15 – 2:30** · Silent | "And if nothing answers? Unknown, in red. Silence is never a friend." | Drone operator → **Silent**; ten-second countdown → **`UNKNOWN · no response`**. |
| **2:30 – 2:45** · Why it holds | "Three layers of evidence: crypto proves *who* answered, geometry proves *where* it is — bound into the tag and to the box the camera sees — and physics proves *when and how near*: round-trip time and signal strength. Every verdict is in the log, and on the judges' dashboard." | Alt-tab to the dashboard: counts 1 friend · 3 foe · 1 unknown, event list. |
| **2:45 – 3:00** · Close | "We built all of this here, on top of ClikaRT, public RT-DETR weights, Android and the Arduino libraries — and the same detector runs on this laptop too, offline. The drone transmitted nothing until asked, and its answer only works for the one observer who asked, at the place it is actually flying, within the time it should have taken." | Alt-tab to the `serve --offline` terminal for one second, back to the phone. |

**If the gadget misbehaves on stage:** say "switching to the scripted signal source", Settings → demo mode on,
and continue the same beats with the fake source; never pretend it is the radio. **If detection stalls:** the
backup video (`recordings/demo-*.mp4`) plays from the laptop.

## Q&A card — the ten likely questions, one line each

1. **Why not an RF reply like any transponder?** A transponder that answers anyone is a beacon you can find and
   record; ours answers only an authenticated challenge, once, short-range, and the answer is bound to that
   observer's fresh nonce, time and position, so it is useless to anyone else.
2. **Couldn't someone record the answer from far away (a telephoto, a scanner) and replay it?** The recording
   carries an old nonce: the gadget and the phone both keep a nonce ledger, so it shows `FOE · replayed nonce`,
   and it would not match a new challenge's tag anyway (`FOE · bad tag`).
3. **What if the enemy captures a friendly drone?** Honest answer: in this prototype the drone-side gadget holds
   the pre-placed shared key, so a capture means re-keying the fleet; the field version loads each drone with
   only its own per-sortie key and checks challenges with the observers' public key (Ed25519), so one capture
   costs one drone for one day.
4. **Isn't this what Remote ID does?** No — Remote ID is an unauthenticated public broadcast for civil airspace:
   anyone can read it, spoof it and home in on it; ours is silent until asked, authenticated, and only meaningful
   to the observer who asked.
5. **How does this compare to Mode 5?** Same idea — crypto challenge/response with time — but Mode 5 needs
   aircraft-class transponders and interrogators; we bring it to a phone and a coin-sized gadget, and add what
   Mode 5 lacks for small drones: the answer is attributed to the exact object the camera sees.
6. **Does the observer radiate?** Only one short challenge burst, only when the operator taps a box; detection is
   a passive camera, the phone is in airplane mode, and the gadget link to the phone is a USB cable.
7. **What about GPS-denied?** The crypto and the timing do not need GPS; the position gate degrades to configured
   positions and the near-field rule (< 30 m passes without a bearing) — production needs INS or observer-relative
   positioning, and that is our stated gap.
8. **Night, fog, daylight glare?** The RGB detector is the weak link at night; the same pipeline takes a thermal
   camera's frames, and the IFF exchange is radio, unaffected by light.
9. **Several drones at once?** Every drone gets its own box and tracker id; the operator challenges one box at a
   time, the box is locked for the 10 s window, and the answer carries the droneId and that box's nonce, so a
   verdict cannot land on the wrong ring.
10. **What existed before the hackathon?** ClikaRT 0.6.4 and its Kotlin binding, the RT-DETR v2 / OWLv2 weights,
    the Android Compose template, CameraX, usb-serial-for-android, the Arduino core with ArduinoJson / Crypto,
    FastAPI, SvelteKit and scrcpy; everything in this repository — detector integration, tracker, FOF-1 library
    with its vectors and selftest, firmware, USB link, UI, dashboard, eval — was built here.

**Also likely (keep in the pocket):**

- **Why symmetric HMAC and not signatures?** Fits 20 hours and tiny MCUs with constant-time compares; Ed25519
  per-drone signatures are the documented production step (`docs/PROTOCOL.md` §8).
- **Relay — exactly what catches it?** The claimed position is inside the tag and gated against range/bearing,
  and both the gadget's `responseMs` and the phone's USB round trip must stay within the budget (2.5 s).
- **How is the answer tied to the drone in the picture?** The tapped box is locked for the window, the verdict
  lands on that box, and the position claim must agree with where the camera sees it.
- **Offline, really?** Airplane mode with the OFFLINE badge (no validated network), ClikaRT with an offline
  license bundle, models pushed over USB, the laptop on the hotspot LAN only; turn the hotspot off and the ring
  still appears — only the dashboard stops.
- **Numbers?** Model load ⟨s⟩, ⟨ms/frame⟩ ms/frame and ⟨fps⟩ fps on phone A, APK ⟨MB⟩, precision/recall
  ⟨P/R⟩ on the eval set, tap-to-ring ⟨ms⟩.
- **Two device types (SC1)?** The phone runs ClikaRT in-process (Kotlin AAR); the laptop runs the same release
  through `clikart-cli … serve --offline` for the eval table and as the contingency detector.

## Demo video (cut from the backup recording)

The H14 rehearsal is recorded with `laptop/mirror.ps1 -Record` (scrcpy, touches shown); a phone-camera shot of
the operator and the gadgets is the second angle (PIT-03). Two cuts, both from `recordings/demo-<time>.mp4`:

- **Full run (≈ 3:00)** — trim the dead time before the first line and after the closing line, no other edits:

  ```bash
  ffmpeg -ss <start> -to <end> -i recordings/demo-<time>.mp4 -c copy recordings/demo-full.mp4
  ```

- **Highlight (≈ 45 s)** for the submission page — one clip per beat, joined:

  | # | Beat | Source in / out | What must be visible |
  |---|---|---|---|
  | 1 | OFFLINE + detect | ⟨in⟩ – ⟨out⟩ | OFFLINE badge, `drone · ?` amber, `person` |
  | 2 | Tap → FRIEND | ⟨in⟩ – ⟨out⟩ | the tap, the countdown arc, `FRIEND · RQ-07 · −58 dBm · 42 ms` |
  | 3 | Replay | ⟨in⟩ – ⟨out⟩ | `FOE · replayed nonce` |
  | 4 | Relay / Late | ⟨in⟩ – ⟨out⟩ | `FOE · position mismatch` or `FOE · late response` |
  | 5 | Silent | ⟨in⟩ – ⟨out⟩ | `UNKNOWN · no response` |
  | 6 | Dashboard | ⟨in⟩ – ⟨out⟩ | counts 1 · 3 · 1 and the event list |

  ```bash
  # one file per row: ffmpeg -ss <in> -to <out> -i recordings/demo-<time>.mp4 -c copy clip1.mp4  (… clip6.mp4)
  printf "file '%s'\n" clip1.mp4 clip2.mp4 clip3.mp4 clip4.mp4 clip5.mp4 clip6.mp4 > clips.txt
  ffmpeg -f concat -safe 0 -i clips.txt -c copy recordings/demo-highlight.mp4
  ```

  `-c copy` cuts on keyframes, so a clip can start up to a second early; re-encode a clip
  (`-c:v libx264 -crf 20`) if a beat must start exactly on the tap. The videos stay out of git (`recordings/` is
  ignored); upload them with the submission.
