/*
 * reader.ino — the observer-side gadget: USB CDC serial to the phone (newline-delimited JSON, see
 * docs/GADGET_LINK.md), radio challenge-response with the drone-side gadget, FOF-1 verification (fof1 library),
 * and the answer line with verdict, RSSI and response time.
 *
 * SKELETON (stream 2 / Ihan): the serial line protocol is wired; the radio exchange and the crypto are TODO.
 * Until then every challenge answers a status line saying so, never a fake verdict.
 *
 * Libraries: ArduinoJson 7.x, fof1 (firmware/iff), rweather Crypto or ESP32 mbedtls (through fof1).
 * Board/radio: open question #1 in README section 7 — fill docs/GADGET_LINK.md when decided.
 */
#include <ArduinoJson.h>
#include <fof1.h>

static const uint32_t SERIAL_BAUD = 115200;
static const char* FW_VERSION = "0.1.0-skeleton";

static char lineBuf[256];
static size_t lineLen = 0;

// TODO(stream 2): the pre-placed 32-byte master key from NVS/EEPROM; the TEST key below matches eval/gen_vectors.py
// and must never ship.
static const uint8_t MASTER_KEY[FOF1_KEY_LEN] = {
  0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
  0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
};

static fof1_nonce_ledger ledger;

static void sendStatus(const char* mode) {
  JsonDocument doc;
  doc["type"] = "status";
  doc["fw"] = FW_VERSION;
  doc["battery"] = 3.9;          // TODO(stream 2): read the battery ADC
  doc["mode"] = mode;
  serializeJson(doc, Serial);
  Serial.println();
}

// seq and boxId are the challenge line's own values, echoed so the phone can tie the answer to its tap (SIG-03).
static void sendAnswer(uint32_t seq, int boxId, fof1_verdict verdict, const char* droneId,
                       const uint8_t nonce[FOF1_NONCE_LEN], int rssiDbm, bool hasRssi, uint32_t responseMs) {
  JsonDocument doc;
  doc["type"] = "answer";
  doc["seq"] = seq;
  doc["boxId"] = boxId;
  doc["verdict"] = fof1_verdict_word(verdict);
  const char* reason = fof1_verdict_reason(verdict);
  if (reason) doc["reason"] = reason; else doc["reason"] = (const char*)NULL;
  if (droneId) doc["droneId"] = droneId;
  char hex[FOF1_NONCE_LEN * 2 + 1];
  for (int i = 0; i < FOF1_NONCE_LEN; i++) {
    static const char digits[] = "0123456789abcdef";
    hex[2 * i] = digits[nonce[i] >> 4];
    hex[2 * i + 1] = digits[nonce[i] & 0x0f];
  }
  hex[FOF1_NONCE_LEN * 2] = '\0';
  doc["nonce"] = hex;
  if (hasRssi) doc["rssiDbm"] = rssiDbm;
  doc["responseMs"] = responseMs;
  serializeJson(doc, Serial);
  Serial.println();
}

static void handleChallenge(uint32_t seq, int boxId, uint16_t challengerId, uint32_t t) {
  (void)seq; (void)boxId;
  // TODO(stream 2):
  //   1. nonce[16] from the hardware RNG (esp_random() / board RNG); fof1_ledger_issue(&ledger, nonce, t)
  //   2. day_key = fof1_derive_day_key(MASTER_KEY, fof1_day_of(t)); fof1_build_challenge(day_key, nonce, t, challengerId, wire)
  //   3. radio.send(wire, 31); t0 = millis(); wait up to 10 s for a 37-byte reply, note RSSI
  //   4. verdict = fof1_verify_response(day_key, &ledger, &ch, reply or NULL, millis() - t0, &observerPos, heading, now, &resp)
  //   5. sendAnswer(seq, boxId, verdict, "RQ-xx" from resp.drone_id, nonce, rssi, true, millis() - t0)
  //      (also on the 10 s timeout: sendAnswer(seq, boxId, FOF1_UNKNOWN_NO_RESPONSE, NULL, nonce, 0, false, 10000))
  (void)challengerId; (void)t;
  sendStatus("NOT_IMPLEMENTED");  // honest placeholder: the phone shows it in the event log, never as a verdict
}

static void handleLine(const char* line) {
  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, line);
  if (err) {
    JsonDocument out;
    out["type"] = "error";
    out["text"] = err.c_str();
    serializeJson(out, Serial);
    Serial.println();
    return;
  }
  const char* cmd = doc["cmd"] | "";
  if (strcmp(cmd, "challenge") == 0) {
    handleChallenge((uint32_t)(doc["seq"] | 0UL), doc["boxId"] | 0, (uint16_t)(doc["challengerId"] | 0),
                    (uint32_t)(doc["t"] | 0UL));
  } else if (strcmp(cmd, "status") == 0) {
    sendStatus("HONEST");  // TODO(stream 2): the reader has no attack modes; report link/radio state instead
  } else {
    JsonDocument out;
    out["type"] = "error";
    out["text"] = "unknown cmd";
    serializeJson(out, Serial);
    Serial.println();
  }
}

void setup() {
  Serial.begin(SERIAL_BAUD);
  fof1_ledger_init(&ledger);
  // TODO(stream 2): radio.begin(); load MASTER_KEY from NVS; load the configured observer position/heading.
  sendStatus("HONEST");
}

void loop() {
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (lineLen > 0) {
        lineBuf[lineLen] = '\0';
        handleLine(lineBuf);
        lineLen = 0;
      }
    } else if (lineLen < sizeof(lineBuf) - 1) {
      lineBuf[lineLen++] = c;
    } else {
      lineLen = 0;  // overlong line: drop it
    }
  }
  // TODO(stream 2): service the radio here (non-blocking) so a late reply can still be timed.
}
