# Gadget link over USB (owner: stream 2, Ihan)

**Source of truth: Ihan's firmware** — [`firmware/README.md`](../firmware/README.md) and
[`firmware/reader/reader.ino`](../firmware/reader/reader.ino) (serial interface v2). This page is the phone's view
of it: what `UsbGadgetSignalSource` + `ReaderLink` send, what they expect back, and how the reader's verdicts
become rings. Where this page and the firmware disagree, the firmware wins and this page is wrong.

## Hardware

- **Reader gadget** (plugged into the phone): ESP32-C3, Arduino-ESP32 3.3.12 + NimBLE-Arduino 2.5.0, built with
  `CDCOnBoot=cdc`, so the chip's built-in **USB-Serial/JTAG** is the serial port. It enumerates as CDC ACM
  **`303a:1001`** (plus a JTAG interface). The phone is USB host over OTG and powers it.
- **Drone gadget**: NU40DK (nRF52840), TinyUSB CDC. The phone never talks to it (its STATUS says `role:"drone"`,
  and the phone's handshake refuses anything that is not `role:"reader"`).
- **Radio**: BLE5 extended, non-connectable advertising of one 38-byte HMAC message, repeated every 50 ms for up to
  600 ms per query. Nothing is transmitted at boot or on STATUS; only a QUERY puts a challenge on air.
- `READER_ID = 1`, `DRONE_ID = 1` and the 3 s timeouts are compile-time constants (`firmware/reader/config.h`). The
  shared HMAC key is provisioned by Ihan over his serial tool with `KEY <64 hex>`; **the phone never sends `KEY`
  and never logs one.**

## Serial settings

| | |
|---|---|
| Baud | 115200 (data bits, parity and stop bits are ignored by USB CDC; the phone sets 8N1) |
| Opening | `303a:1001`: **DTR off, RTS off** (DTR with an RTS change can drop the C3 into download mode, and it talks without DTR). Any other CDC board: DTR on, RTS off. Ihan's pySerial sets them before `open()`; Android can only set them right after. |
| Phone → reader | ASCII command line + LF. At most 79 bytes before the LF; nothing runs until the LF. Commands are case-sensitive. |
| Reader → phone | JSON lines ending in LF; legacy text lines (boot banner, key store) ending in CRLF; one bare LF now and then (USB wake). Lines can arrive split across USB packets: reassembled at LF, 1024 bytes max on the phone (the reader's STATUS is about 500, at most 533). |
| Read loop | always running: the C3 only sends while the host reads. |

## Commands (phone → reader)

```
QUERY k3v9x2qa-12-3
STATUS k3v9x2qa-s1
```

| command | what the reader does |
|---|---|
| `QUERY <request_id>` | one challenge with a fresh 16-byte nonce, always answered by an `ACK` first; no automatic retry |
| `STATUS [request_id]` | a `STATUS` snapshot with the id echoed; never transmits over the air |
| `HELP` | the command list |
| `KEY <64 hex>` | provisioning, Ihan only; reboots the board. **Never sent by the phone.** |

**Request ids**: `[A-Za-z0-9_-]{1,32}`. The reader remembers the current and the last completed id and answers a
reused one with `ACK DUPLICATE` and, if it completed, the **old** RESULT with `replay:true`. The phone therefore
makes every id unique across app restarts: `<8 random [a-z0-9] per launch>-<seq>-<boxId>`, e.g. `k3v9x2qa-12-3`
(STATUS ids: `<prefix>-s<n>`).

## Events (reader → phone)

Captured on Ihan's bench (`firmware/diagnostics/2026-10-10/adv600.json`, trial 1; the 76-hex `raw` packet dump
omitted here), one FRIEND exchange in arrival order:

```
{"event":"ACK","request_id":"k3v9x2qa-12-3","accepted":true,"reason":"ACCEPTED","state":"QUERYING"}
{"event":"STATE","boot_id":"ae07effed99ac4d4","request_id":"k3v9x2qa-12-3","state":"QUERYING","reason":"CHANGED"}
{"event":"TX_CHALLENGE","request_id":"k3v9x2qa-12-3","query":1,"at_ms":8104,"elapsed_ms":0,"bytes":38,"version":1,"type":1,"reader_id":1,"drone_id":1,"nonce":"44ad3f50c8539c3d2121b4cb9e7e1e25","tag":"612e4fc4fdcd1f5958c7b557004bdda7","result":"BROADCAST_STARTED"}
{"event":"RESULT","request_id":"k3v9x2qa-12-3","query":1,"response_received":true,"verdict":"FRIEND","reason":"NONE","response_ms":460,"elapsed_ms":460,"replay":false}
{"event":"STATE","boot_id":"ae07effed99ac4d4","request_id":"k3v9x2qa-12-3","state":"COOLDOWN","reason":"CHANGED"}
{"event":"RX_RESPONSE","request_id":"k3v9x2qa-12-3","query":1,"at_ms":8564,"elapsed_ms":460,"bytes":38,"version":1,"type":2,"reader_id":1,"drone_id":1,"nonce":"44ad3f50c8539c3d2121b4cb9e7e1e25","tag":"3ada182c07bc22049b25bb3fc8326391","result":"FRIEND"}
{"event":"STATE","boot_id":"ae07effed99ac4d4","request_id":"k3v9x2qa-12-3","state":"IDLE","reason":"CHANGED"}
```

No answer (`baseline.json`): the RESULT comes at 3000 ms with `response_ms` null:

```
{"event":"RESULT","request_id":"k3v9x2qa-13-3","query":2,"response_received":false,"verdict":"UNKNOWN","reason":"NO_RESPONSE","response_ms":null,"elapsed_ms":3000,"replay":false}
```

The handshake reply (from the firmware's format string; no reader STATUS was captured):

```
{"event":"STATUS","request_id":"k3v9x2qa-s1","role":"reader","interface_version":2,"boot_id":"ae07effed99ac4d4","manual_query":true,"response_timeout_ms":3000,"key_configured":true,"radio_ready":true,"state":"IDLE","active_request_id":"","last_result":null}
```

| event | what the phone does with it |
|---|---|
| `ACK` | first reply to the QUERY. `accepted:false` (BUSY, KEY_NOT_CONFIGURED, RADIO_ERROR, ...) ends the challenge at once as `UNKNOWN · <reason>`. `DUPLICATE` is accepted:true (should not happen with unique ids). An ACK with `request_id:""` and `accepted:false` is a malformed-command reply and goes to the open query. |
| `STATE` | tracks the reader's mode (IDLE / QUERYING / COOLDOWN). `reason:"BOOT"` or a new `boot_id` = the reader rebooted: the query in flight ends `UNKNOWN · gadget rebooted`, the handshake runs again. |
| `TX_CHALLENGE` | the only place the nonce appears; taken for the phone's replay ledger. Lossy diagnostic queue, may come after the RESULT. |
| `RX_RESPONSE` | one per answer packet; `IGNORED_*` results never matter. A counted one supplies the nonce if TX_CHALLENGE was lost. No RSSI field. |
| `RESULT` | the verdict, exactly once per query, matched by `request_id`. |
| `STATUS` | handshake (compatible = `role:"reader"`, `interface_version:2`, `manual_query:true`, a `boot_id`), key and radio state, and `last_result` to recover a lost RESULT. |
| `DIAG` | `dropped_packet_logs` or `radio_error`/`receive_queue_overflow`: logged. |
| `HELP`, other events | logged. |
| text lines | `KEY: stored`, `KEY: missing; ...`, `RADIO: BLE5 extended broadcast; ...`, `CONFIG: ...`, ESP ROM text: logged with hex runs cut. A line containing `waiting for download` means the C3 is in download mode: taps say `UNKNOWN · gadget in download mode` until it is reset (release BOOT, press RESET or re-plug). |

## One challenge, on the phone

1. **Port open** → `STATUS <id>` at once, again every 1 s, give up after 5 s (as Ihan's host). The reader is
   usable from the first compatible STATUS; the status-strip gadget dot turns green then. If opening the port
   reboots the C3, its own boot `STATE BOOT` + `STATUS` complete the handshake.
2. **Tap** → if the reader is not handshaken yet, one more STATUS and up to 1.5 s wait, else `UNKNOWN · gadget not
   ready`. Key missing → `UNKNOWN · gadget key not set`; radio not ready → `UNKNOWN · gadget radio error`.
3. **Cooldown**: the reader refuses any QUERY for 3 s from the previous query's start, even after an early FRIEND.
   If its last STATE was QUERYING or COOLDOWN, the phone waits up to 3.2 s for STATE IDLE, then writes the QUERY
   (a remaining BUSY becomes `UNKNOWN · gadget busy`).
4. **`QUERY <id>`** written; the phone's clock starts here for the round trip.
5. **ACK** expected within 2 s, else `UNKNOWN · gadget no ack`.
6. **RESULT** expected within ~3 s (the reader's deadline); at 3.5 s without one the phone asks `STATUS` once and
   takes `last_result` if it carries this id; 5 s after the ACK without a RESULT → `UNKNOWN · gadget no result`
   (the link lost it; distinct from the reader's radio `NO_RESPONSE`, which is `UNKNOWN · no response`).
7. If the RESULT came before its TX_CHALLENGE, the phone waits up to 300 ms for the nonce, then goes on without it.

The phone's own 10 s challenge window still bounds all of this.

## Verdict mapping

The reader says only FRIEND or UNKNOWN; the reasons are `NONE | NO_RESPONSE | INVALID_MESSAGE | AUTH_FAILED |
RADIO_ERROR` (`FofProtocol.h`). The phone (`signal/ReaderVerdicts.kt`) fails closed: FRIEND only for
`FRIEND · NONE · response_received:true · replay:false`.

| reader says | ring |
|---|---|
| `FRIEND` / `NONE` | **FRIEND** · drone 1 · `<rtt>` ms |
| `UNKNOWN` / `NO_RESPONSE` (nothing with the current nonce in 3 s; old or relayed answers are ignored by the reader and also end here) | **UNKNOWN** · no response |
| `UNKNOWN` / `AUTH_FAILED` (current nonce, wrong HMAC tag: an impostor) | **FOE** · bad tag |
| `UNKNOWN` / `INVALID_MESSAGE` (wrong length, version, type, reader or drone id) | **UNKNOWN** · invalid message |
| `UNKNOWN` / `RADIO_ERROR` | **UNKNOWN** · gadget radio error |
| any RESULT with `replay:true` (the reader re-sending a stored verdict: `ACK DUPLICATE` + the old RESULT, or `STATUS last_result`; never an answer to this nonce) | **UNKNOWN** · stale result |
| FRIEND with another reason, UNKNOWN with `NONE`, an unknown reason | **UNKNOWN** · invalid result / the reason in words |
| `ACK` BUSY / KEY_NOT_CONFIGURED / RADIO_ERROR / INVALID_* / COMMAND_TOO_LONG | **UNKNOWN** · gadget busy / gadget key not set / gadget radio error / gadget refused ... |
| phone-side: no port, not handshaken, no ACK, no RESULT, reboot, unplug, download mode | **UNKNOWN** · no gadget / gadget not ready / gadget no ack / gadget no result / gadget rebooted / gadget unplugged / gadget in download mode |

A RESULT for one of this phone's earlier request ids (its challenge already ended) is logged as `late result for
box N ignored` and never drawn.

## Re-checks on the phone (`ReChecks`)

- **Nonce ledger**: a nonce seen before → `FOE · replayed nonce` (the reader's TX_CHALLENGE nonce). When the
  reader's log line carrying it was lost the check is skipped with a note.
- **Round trip**: phone clock from the QUERY write to the RESULT line, and the reader's `response_ms`, both within
  2.5 s → else `FOE · late response`. A result recovered from `STATUS last_result` arrived with the 3.5 s STATUS,
  not when the reader decided, so its round trip is the reader's own `response_ms` (else `elapsed_ms`). (The reader itself accepts answers up to 2999 ms; captured FRIENDs were 19-910 ms.)
- **RSSI**: Ihan's reader reports none, so the -70 dBm gate is skipped with a note (`RSSI n/a` in the event log).
  With **Strict physical** on in Settings, a missing RSSI is `FOE · signal mismatch`, so leave it off with this
  reader. An `rssi_dbm` / `rssi` field is read automatically if a later firmware adds one.

## Not in the firmware (by design, `firmware/README.md`)

Position check, ranging, relay protection and multi-responder arbitration. The phone's `challengerId` and time are
not sent (the reader has fixed ids and no clock). There are no drone-side attack modes; the replay, relay and
impostor demos stay on the `Fake` signal source (tagged `FAKE`).

## Still needs the real hardware

- Whether usb-serial-for-android claims the composite 303a:1001 by itself (the phone also maps it to CDC ACM
  explicitly), and the permission prompt / "always open" via `UsbAttachActivity`.
- Whether Android's open + `setDTR(false)` + `setRTS(false)` leaves the C3 running (watch for `STATE BOOT` or
  `waiting for download` in the event log).
- Boot output order and any ESP ROM text on USB-Serial/JTAG after a reset (not captured).
- Real round trips over OTG against the 2.5 s budget; whether the C3 runs from the phone's OTG power.
- Unplug and re-plug mid-challenge (`UNKNOWN · gadget unplugged` expected), and adb over Wi-Fi while the single
  USB-C port holds the gadget.

Bench tools are Ihan's: `firmware/tools/reader_gui.py` (browser GUI) and `firmware/tools/serial_monitor.py`.
