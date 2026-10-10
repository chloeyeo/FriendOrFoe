/*
 * drone.ino — the drone-side gadget (stands in for the friendly drone): listens on the radio, answers only an
 * authenticated FOF-1 challenge, and carries the demo attack modes.
 *
 * SKELETON (stream 2 / Ihan): mode enum, mode switching over USB serial and the answer flow are laid out; the
 * radio exchange and the crypto are TODO. The drone transmits nothing until it is asked.
 *
 * Libraries: ArduinoJson 7.x, fof1 (firmware/iff), rweather Crypto or ESP32 mbedtls (through fof1).
 * How the modes are switched on stage (button, serial, DIP) is README open question #1 -> docs/GADGET_LINK.md.
 */
#include <ArduinoJson.h>
#include <fof1.h>

/* The demo's attack modes (README section 2 and PROJECT.md). */
enum AttackMode : uint8_t {
  MODE_HONEST = 0,   // answer correctly with the real position
  MODE_REPLAY,       // re-send the previous answer (old nonce) -> FOE · replayed nonce
  MODE_RELAY,        // answer with a far-away position (relayed drone) -> FOE · position mismatch
  MODE_LATE,         // answer correctly but after a delay -> FOE · late response
  MODE_SILENT,       // never answer -> UNKNOWN · no response
  MODE_IMPOSTOR,     // answer with the wrong key -> FOE · bad tag
};

static const char* MODE_NAMES[] = { "HONEST", "REPLAY", "RELAY", "LATE", "SILENT", "IMPOSTOR" };
static AttackMode mode = MODE_HONEST;

static const uint16_t DRONE_ID = 7;  // "RQ-07"
static const uint32_t LATE_DELAY_MS = 3500;

// TODO(stream 2): the pre-placed 32-byte master key from NVS/EEPROM; the TEST key below matches eval/gen_vectors.py
// and must never ship. The impostor mode uses IMPOSTOR_KEY (every byte 0xA5), as the bad_tag vector does.
static const uint8_t MASTER_KEY[FOF1_KEY_LEN] = {
  0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
  0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
};
static const uint8_t IMPOSTOR_KEY[FOF1_KEY_LEN] = {
  0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5,
  0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5, 0xa5,
};

// Configured positions for the indoor demo (no GPS fix in the venue): honest = where the drone really is,
// relay = 5 km away. TODO(stream 2): agree the coordinates with the observer config (README open question #2).
static const fof1_position HONEST_POS = { 375666, 1269781, 6 };   // ~Seoul, 1e-4 deg, alt 55 m / 10
static const fof1_position RELAY_POS  = { 375998, 1270200, 15 };

static uint8_t lastResponse[FOF1_RESPONSE_LEN];
static bool haveLastResponse = false;

static char lineBuf[128];
static size_t lineLen = 0;

static void sendStatus() {
  JsonDocument doc;
  doc["type"] = "status";
  doc["fw"] = "0.1.0-skeleton";
  doc["mode"] = MODE_NAMES[mode];
  serializeJson(doc, Serial);
  Serial.println();
}

/* Serial control for the bench and the stage: {"cmd":"mode","mode":"REPLAY"} and {"cmd":"status"}. */
static void handleLine(const char* line) {
  JsonDocument doc;
  if (deserializeJson(doc, line)) return;
  const char* cmd = doc["cmd"] | "";
  if (strcmp(cmd, "mode") == 0) {
    const char* name = doc["mode"] | "";
    for (uint8_t i = 0; i < sizeof(MODE_NAMES) / sizeof(MODE_NAMES[0]); i++) {
      if (strcmp(name, MODE_NAMES[i]) == 0) mode = (AttackMode)i;
    }
  }
  sendStatus();
}

/* Called when a 31-byte challenge arrives on the radio. */
static void onChallenge(const uint8_t wire[FOF1_CHALLENGE_LEN], uint32_t nowUnix) {
  // TODO(stream 2):
  //   day = fof1_day_of(challenge t) — accept only if |t - nowUnix| <= 86400 (clock drift: yesterday/today/tomorrow)
  //   day_key = fof1_derive_day_key(MASTER_KEY, day); if (fof1_check_challenge(day_key, wire, &ch) != FOF1_FRIEND) return; // silent
  //   switch (mode):
  //     SILENT:   return;
  //     REPLAY:   if (haveLastResponse) radio.send(lastResponse, 37); return;
  //     IMPOSTOR: key from IMPOSTOR_KEY instead of MASTER_KEY, HONEST_POS
  //     RELAY:    RELAY_POS
  //     LATE:     delay(LATE_DELAY_MS) then HONEST_POS
  //     HONEST:   HONEST_POS
  //   drone_key = fof1_derive_drone_key(day_key, DRONE_ID); fof1_build_response(drone_key, &ch, DRONE_ID, &pos, out);
  //   radio.send(out, 37); memcpy(lastResponse, out, 37); haveLastResponse = true;
  (void)wire; (void)nowUnix;
}

void setup() {
  Serial.begin(115200);
  // TODO(stream 2): radio.begin() in receive mode; a button/DIP read for the mode; NVS for the key.
  sendStatus();
}

void loop() {
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (lineLen > 0) { lineBuf[lineLen] = '\0'; handleLine(lineBuf); lineLen = 0; }
    } else if (lineLen < sizeof(lineBuf) - 1) {
      lineBuf[lineLen++] = c;
    } else {
      lineLen = 0;
    }
  }
  // TODO(stream 2): if (radio.available() == FOF1_CHALLENGE_LEN) { radio.read(buf); onChallenge(buf, unixNow()); }
}
