# Gadget link over USB (owner: stream 2, Ihan)

The phone is USB host (OTG adapter); the **reader gadget** enumerates as a USB CDC serial device and the app opens
it with `com.github.mik3y:usb-serial-for-android:3.9.0` (`app/.../signal/UsbGadgetSignalSource.kt`). The gadget
generates the nonce, runs the radio exchange and the crypto (docs/PROTOCOL.md); the phone only sends commands and
reads answers. `laptop/gadget_cli.py` sends the same lines from a laptop, so the link is testable on the bench.

**Serial parameters:** 115200 baud, 8 data bits, no parity, 1 stop bit (8N1). DTR asserted on open.
**Framing:** one JSON object per line, UTF-8, terminated by `\n` (`\r\n` tolerated), max 512 bytes per line.
Unknown keys are ignored; unknown `type`s are logged and skipped; a malformed line fails loudly on the phone.

## Every line

phone → gadget

```
{"cmd":"challenge","boxId":3,"challengerId":7,"t":1760000000}
{"cmd":"status"}
```

| key | type | meaning |
|---|---|---|
| `cmd` | string | `challenge` or `status` |
| `boxId` | int | the tapped track; echoed nowhere, the phone keeps the mapping (box lock) |
| `challengerId` | int (u16) | this observer's id, goes into the challenge |
| `t` | int | unix seconds on the phone; the gadget uses it for the day key and the challenge |

gadget → phone

```
{"type":"answer","verdict":"FRIEND","reason":null,"droneId":"RQ-07","nonce":"<32 hex>","rssiDbm":-58,"responseMs":42,"airtimeMs":1.2}
{"type":"answer","verdict":"FOE","reason":"replayed nonce","droneId":"RQ-07","nonce":"<32 hex>","rssiDbm":-61,"responseMs":40}
{"type":"answer","verdict":"UNKNOWN","reason":"no response","nonce":"<32 hex>","responseMs":10000}
{"type":"status","fw":"0.1","battery":3.9,"mode":"HONEST"}
```

| key | type | meaning |
|---|---|---|
| `type` | string | `answer` (exactly one per challenge) or `status` |
| `verdict` | string | `FRIEND`, `FOE`, `UNKNOWN` — the gadget's own verify result |
| `reason` | string or null | `replayed nonce`, `bad tag`, `position mismatch`, `late response`, `no response`; null for FRIEND |
| `droneId` | string, optional | display id of the responder (`RQ-07`); absent when nothing answered |
| `nonce` | 32 hex chars | the 16-byte nonce the gadget used; the phone refuses any nonce seen before |
| `rssiDbm` | int, optional | received signal strength of the answer; absent if the radio does not report it |
| `responseMs` | int | end of challenge transmission → start of answer, gadget clock; 10000 on timeout |
| `airtimeMs` | number, optional | the answer packet's airtime (informational) |
| `fw` / `battery` / `mode` | status fields | firmware version, battery volts, the gadget's mode |

Timing on the phone: `SystemClock.elapsedRealtime()` from writing the `challenge` line to reading the `answer`
line is the USB round trip, compared with the 2.5 s budget alongside `responseMs` (`ReChecks`).

Bench use: `python laptop/gadget_cli.py --port COM5 challenge` / `status`; the drone-side sketch additionally
accepts `{"cmd":"mode","mode":"REPLAY"}` on its own serial port for switching attack modes from a laptop.

## Open questions (answer in the chat before H1, then write the answers here)

1. **Board** — which MCU/board for each gadget (ESP32-S3 / RP2040 / SAMD native USB, or AVR + CP210x/CH34x)?
2. **Radio** — module and packet API (LoRa SX127x/SX126x, nRF24L01+, ESP-NOW…); RSSI readout available?
3. **USB VID/PID** — of the reader as enumerated; is it in usb-serial-for-android's default prober table or does
   the app need a custom `ProbeTable`? Does the board reset when the port is opened (DTR)?
4. **Baud** — 115200 as drafted, or faster for native USB?
5. **Attack-mode switching** on the drone-side gadget during the demo: button, DIP switch, or the serial `mode`
   command from a laptop?
6. **Observer position/heading** for the position gate: configured in the gadget, or sent by the phone with the
   `challenge` line (`"lat"`, `"lon"`, `"heading"` keys)?
7. **Who flashes** the two gadgets and who brings the OTG adapter.
