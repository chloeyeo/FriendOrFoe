/*
 * fof1.cpp — FOF-1 gadget-side implementation. SKELETON: every protocol function answers
 * FOF1_ERR_NOT_IMPLEMENTED / FOF1_ERR_NOT_IMPLEMENTED_VERDICT until stream 2 (Ihan) fills it in; the selftest
 * sketch therefore prints FAIL for every vector today, by design.
 *
 * HMAC-SHA256 comes from rweather's Crypto library (`#include <Crypto.h>`, `#include <SHA256.h>`:
 *   SHA256 h; h.resetHMAC(key, 32); h.update(msg, len); h.finalizeHMAC(key, 32, out, 32);)
 * or, on ESP32, from the built-in mbedtls:
 *   mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), key, 32, msg, len, out);
 * Pick one in a small static helper `hmac_sha256(key, msg, len, out)` and use it everywhere below.
 *
 * Byte order: every multi-byte integer on the wire and inside a MAC input is big-endian
 * (write helpers be16/be32 and read helpers rd16/rd32; never memcpy a struct onto the wire).
 * The reference is eval/gen_vectors.py — when in doubt, do what the Python does.
 */
#include "fof1.h"

#include <string.h>

/* ---- keys ---------------------------------------------------------------------------------------------------- */

int fof1_derive_day_key(const uint8_t master[FOF1_KEY_LEN], const char day[8], uint8_t out[FOF1_KEY_LEN]) {
  (void)master; (void)day; (void)out;
  /* TODO(stream 2): out = HMAC-SHA256(master, "FOF-day" || day[8]). */
  return FOF1_ERR_NOT_IMPLEMENTED;
}

int fof1_derive_drone_key(const uint8_t day_key[FOF1_KEY_LEN], uint16_t drone_id, uint8_t out[FOF1_KEY_LEN]) {
  (void)day_key; (void)drone_id; (void)out;
  /* TODO(stream 2): out = HMAC-SHA256(day_key, "FOF-drone" || be16(drone_id)). */
  return FOF1_ERR_NOT_IMPLEMENTED;
}

int fof1_day_of(uint32_t t, char day[8]) {
  (void)t; (void)day;
  /* TODO(stream 2): civil date from unix seconds (UTC), e.g. Howard Hinnant's days_from_civil inverse; write
   * "YYYYMMDD" without a terminator. The midnight vector (t = 1791590400 -> "20261010") pins the boundary. */
  return FOF1_ERR_NOT_IMPLEMENTED;
}

/* ---- challenge ----------------------------------------------------------------------------------------------- */

int fof1_build_challenge(const uint8_t day_key[FOF1_KEY_LEN], const uint8_t nonce[FOF1_NONCE_LEN], uint32_t t,
                         uint16_t challenger_id, uint8_t out[FOF1_CHALLENGE_LEN]) {
  (void)day_key; (void)nonce; (void)t; (void)challenger_id; (void)out;
  /* TODO(stream 2): out = 0x01 | nonce | be32(t) | be16(challenger_id) | HMAC(day_key, "C"|nonce|be32(t)|be16(id))[0:8]. */
  return FOF1_ERR_NOT_IMPLEMENTED;
}

fof1_verdict fof1_check_challenge(const uint8_t day_key[FOF1_KEY_LEN], const uint8_t wire[FOF1_CHALLENGE_LEN],
                                  fof1_challenge* out) {
  (void)day_key; (void)wire; (void)out;
  /* TODO(stream 2): parse, recompute the cmac, constant-time compare; FOF1_FRIEND when authentic. */
  return FOF1_ERR_NOT_IMPLEMENTED_VERDICT;
}

/* ---- response ------------------------------------------------------------------------------------------------ */

int fof1_build_response(const uint8_t drone_key[FOF1_KEY_LEN], const fof1_challenge* challenge, uint16_t drone_id,
                        const fof1_position* pos, uint8_t out[FOF1_RESPONSE_LEN]) {
  (void)drone_key; (void)challenge; (void)drone_id; (void)pos; (void)out;
  /* TODO(stream 2): out = 0x01 | be16(drone_id) | nonce[0:8] | be32(lat) | be32(lon) | be16(alt) |
   *   HMAC(drone_key, "R" | nonce(16) | be32(t) | be16(challenger_id) | be16(drone_id) | be32(lat) | be32(lon) | be16(alt))[0:16]. */
  return FOF1_ERR_NOT_IMPLEMENTED;
}

int fof1_parse_response(const uint8_t wire[FOF1_RESPONSE_LEN], fof1_response* out) {
  (void)wire; (void)out;
  /* TODO(stream 2): big-endian reads at the documented offsets. */
  return FOF1_ERR_NOT_IMPLEMENTED;
}

fof1_verdict fof1_verify_response(const uint8_t day_key[FOF1_KEY_LEN], fof1_nonce_ledger* ledger,
                                  const fof1_challenge* challenge, const uint8_t wire[FOF1_RESPONSE_LEN],
                                  uint32_t response_ms, const fof1_position* observer, int16_t heading_deg,
                                  uint32_t now, fof1_response* out) {
  (void)day_key; (void)ledger; (void)challenge; (void)wire; (void)response_ms; (void)observer; (void)heading_deg;
  (void)now; (void)out;
  /* TODO(stream 2):
   *   if (!wire) return FOF1_UNKNOWN_NO_RESPONSE;
   *   parse; nonce8 must equal challenge->nonce[0:8] and fof1_ledger_consume(...) must pass -> else REPLAYED_NONCE
   *   drone_key = fof1_derive_drone_key(day_key, resp.drone_id); recompute tag; constant-time compare -> else BAD_TAG
   *   fof1_position_gate(observer, heading_deg, &resp.pos) -> else POSITION_MISMATCH
   *   response_ms > FOF1_LATE_MS -> LATE_RESPONSE
   *   FOF1_FRIEND */
  return FOF1_ERR_NOT_IMPLEMENTED_VERDICT;
}

/* ---- nonce ledger -------------------------------------------------------------------------------------------- */

void fof1_ledger_init(fof1_nonce_ledger* ledger) {
  if (ledger) memset(ledger, 0, sizeof(*ledger));
}

int fof1_ledger_issue(fof1_nonce_ledger* ledger, const uint8_t nonce[FOF1_NONCE_LEN], uint32_t t) {
  (void)ledger; (void)nonce; (void)t;
  /* TODO(stream 2): entries[next] = {nonce, t, used = 0, valid = 1}; next = (next + 1) % FOF1_LEDGER_SIZE. */
  return FOF1_ERR_NOT_IMPLEMENTED;
}

fof1_verdict fof1_ledger_consume(fof1_nonce_ledger* ledger, const uint8_t nonce8[8], uint32_t now) {
  (void)ledger; (void)nonce8; (void)now;
  /* TODO(stream 2): find the valid entry whose nonce[0:8] == nonce8; if none, used, or now - issued_t >= window
   *   -> FOF1_FOE_REPLAYED_NONCE; else mark used and return FOF1_FRIEND. */
  return FOF1_ERR_NOT_IMPLEMENTED_VERDICT;
}

/* ---- position gate ------------------------------------------------------------------------------------------- */

int fof1_position_gate(const fof1_position* observer, int16_t heading_deg, const fof1_position* claimed) {
  (void)observer; (void)heading_deg; (void)claimed;
  /* TODO(stream 2): north = dlat_deg * 111320; east = dlon_deg * 111320 * cos(mean_lat); d = hypot(north, east);
   *   d <= 30 -> 1; d > 3000 -> 0; else |wrap(bearing(east, north) - heading)| <= 20 -> 1. Mirror position_gate()
   *   in eval/gen_vectors.py exactly (float math is fine at these ranges). */
  return 0;
}

/* ---- strings ------------------------------------------------------------------------------------------------- */

const char* fof1_verdict_word(fof1_verdict v) {
  switch (v) {
    case FOF1_FRIEND: return "FRIEND";
    case FOF1_FOE_REPLAYED_NONCE:
    case FOF1_FOE_BAD_TAG:
    case FOF1_FOE_POSITION_MISMATCH:
    case FOF1_FOE_LATE_RESPONSE: return "FOE";
    case FOF1_UNKNOWN_NO_RESPONSE: return "UNKNOWN";
    default: return "UNKNOWN";
  }
}

const char* fof1_verdict_reason(fof1_verdict v) {
  switch (v) {
    case FOF1_FRIEND: return NULL;
    case FOF1_FOE_REPLAYED_NONCE: return "replayed nonce";
    case FOF1_FOE_BAD_TAG: return "bad tag";
    case FOF1_FOE_POSITION_MISMATCH: return "position mismatch";
    case FOF1_FOE_LATE_RESPONSE: return "late response";
    case FOF1_UNKNOWN_NO_RESPONSE: return "no response";
    default: return "not implemented";
  }
}
