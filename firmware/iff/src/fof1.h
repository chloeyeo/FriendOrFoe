/*
 * fof1.h — FOF-1 radio-silent IFF, the gadget side (Arduino C/C++ library, C-compatible API).
 *
 * Owner: stream 2 (Ihan). Reference: eval/gen_vectors.py (Python, independent) and docs/PROTOCOL.md.
 * Runs on both gadgets: the reader (observer side: issue a challenge, verify the answer) and the drone
 * (responder side: check the challenge, build the answer). The selftest sketch replays the vectors in
 * firmware/selftest/vectors.h through this API and prints PASS/FAIL per vector.
 *
 * Everything on the wire is a fixed binary layout, big-endian, never JSON text; every MAC is over such a layout.
 *
 *   dayKey   = HMAC-SHA256(master[32], "FOF-day" || "YYYYMMDD")            YYYYMMDD = UTC date of the challenge's t
 *   droneKey = HMAC-SHA256(dayKey,     "FOF-drone" || droneId:u16)
 *
 *   challenge, 31 bytes (observer -> responder, one burst):
 *     offset  0  v            u8   = 1
 *     offset  1  nonce        16 B random (hardware RNG)
 *     offset 17  t            u32  unix seconds
 *     offset 21  challengerId u16
 *     offset 23  cmac         8 B  = HMAC-SHA256(dayKey, "C" || nonce || t:u32 || challengerId:u16)[0:8]
 *
 *   response, 37 bytes (responder -> observer, directional, short range):
 *     offset  0  v            u8   = 1
 *     offset  1  droneId      u16
 *     offset  3  nonce[0:8]   8 B  the first half of the challenge nonce
 *     offset 11  lat          i32  degrees * 1e4 (south negative)
 *     offset 15  lon          i32  degrees * 1e4 (west negative)
 *     offset 19  alt          i16  meters / 10
 *     offset 21  tag          16 B = HMAC-SHA256(droneKey, "R" || nonce(16) || t:u32 || challengerId:u16 || droneId:u16
 *                                                        || lat:i32 || lon:i32 || alt:i16)[0:16]
 *
 *   verify order on the reader gadget:
 *     1. nonce ledger — issued by us, unused, younger than FOF1_NONCE_WINDOW_S  -> FOF1_FOE_REPLAYED_NONCE
 *     2. tag recomputes under droneKey(dayKey(day(t)), droneId)                 -> FOF1_FOE_BAD_TAG
 *     3. position gate (near field <= 30 m passes; > 3 km fails; else bearing within +-20 deg of heading)
 *                                                                               -> FOF1_FOE_POSITION_MISMATCH
 *     4. response_ms <= FOF1_LATE_MS                                            -> FOF1_FOE_LATE_RESPONSE
 *     nothing answered within the window                                        -> FOF1_UNKNOWN_NO_RESPONSE
 *   The phone re-checks nonce freshness, round trip, RSSI and attribution on its own (app/.../signal/ReChecks.kt).
 *
 * HMAC-SHA256: rweather Crypto (`#include <SHA256.h>`, `HMAC<SHA256>` via `hmac.resetHMAC(key, len)` /
 * `update` / `finalizeHMAC(key, len, out, 32)`) on any board, or ESP32's built-in mbedtls (`mbedtls_md_hmac`).
 */
#ifndef FOF1_H
#define FOF1_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define FOF1_VERSION 1
#define FOF1_KEY_LEN 32
#define FOF1_NONCE_LEN 16
#define FOF1_CHALLENGE_LEN 31
#define FOF1_RESPONSE_LEN 37
#define FOF1_CMAC_LEN 8
#define FOF1_TAG_LEN 16
#define FOF1_NONCE_WINDOW_S 10      /* a nonce is answerable for this long after it was issued */
#define FOF1_LATE_MS 2500           /* response time budget (gadget-measured) */
#define FOF1_NEAR_FIELD_M 30        /* within this range the position gate needs no bearing */
#define FOF1_RANGE_MAX_M 3000       /* beyond this range the position gate fails */
#define FOF1_CONE_DEG 20            /* +- cone around the observer's heading between near field and max range */
#define FOF1_LEDGER_SIZE 16         /* nonces remembered at once */

/* Return codes. Functions that return `int` answer FOF1_OK or FOF1_ERR_*. */
#define FOF1_OK 0
#define FOF1_ERR_ARG 1              /* bad length or NULL */
#define FOF1_ERR_NOT_IMPLEMENTED 255

typedef enum {
  FOF1_FRIEND = 0,
  FOF1_FOE_REPLAYED_NONCE = 1,
  FOF1_FOE_BAD_TAG = 2,
  FOF1_FOE_POSITION_MISMATCH = 3,
  FOF1_FOE_LATE_RESPONSE = 4,
  FOF1_UNKNOWN_NO_RESPONSE = 5,
  FOF1_ERR_NOT_IMPLEMENTED_VERDICT = 255 /* the skeleton's answer until stream 2 lands */
} fof1_verdict;

/* A quantized position: degrees * 1e4 and meters / 10, signed. */
typedef struct {
  int32_t lat_e4;
  int32_t lon_e4;
  int16_t alt_10m;
} fof1_position;

/* A decoded challenge (the wire form is 31 bytes, see the layout above). */
typedef struct {
  uint8_t v;
  uint8_t nonce[FOF1_NONCE_LEN];
  uint32_t t;
  uint16_t challenger_id;
  uint8_t cmac[FOF1_CMAC_LEN];
} fof1_challenge;

/* A decoded response (the wire form is 37 bytes). */
typedef struct {
  uint8_t v;
  uint16_t drone_id;
  uint8_t nonce8[8];
  fof1_position pos;
  uint8_t tag[FOF1_TAG_LEN];
} fof1_response;

/* The observer's nonce ledger: every nonce it issued, when, and whether it was already answered. */
typedef struct {
  uint8_t nonce[FOF1_NONCE_LEN];
  uint32_t issued_t;
  uint8_t used;
  uint8_t valid;
} fof1_ledger_entry;

typedef struct {
  fof1_ledger_entry entries[FOF1_LEDGER_SIZE];
  uint8_t next; /* ring index */
} fof1_nonce_ledger;

/* ---- keys ---------------------------------------------------------------------------------------------------- */

/* dayKey = HMAC-SHA256(master, "FOF-day" || day); day is exactly 8 ASCII chars "YYYYMMDD" (UTC). */
int fof1_derive_day_key(const uint8_t master[FOF1_KEY_LEN], const char day[8], uint8_t out[FOF1_KEY_LEN]);

/* droneKey = HMAC-SHA256(dayKey, "FOF-drone" || droneId:u16 big-endian). */
int fof1_derive_drone_key(const uint8_t day_key[FOF1_KEY_LEN], uint16_t drone_id, uint8_t out[FOF1_KEY_LEN]);

/* "YYYYMMDD" of unix time t (UTC) into day[8] (no terminator); the day the keys of a challenge belong to. */
int fof1_day_of(uint32_t t, char day[8]);

/* ---- challenge (observer builds, responder checks) ----------------------------------------------------------- */

/* Serializes v=1 | nonce | t | challengerId | cmac into out[31]. */
int fof1_build_challenge(const uint8_t day_key[FOF1_KEY_LEN], const uint8_t nonce[FOF1_NONCE_LEN], uint32_t t,
                         uint16_t challenger_id, uint8_t out[FOF1_CHALLENGE_LEN]);

/* Parses wire[31] into *out and recomputes the cmac. FOF1_FRIEND when authentic (answer it), FOF1_FOE_BAD_TAG
 * when not (stay silent: an unauthenticated challenge never lights the drone up). */
fof1_verdict fof1_check_challenge(const uint8_t day_key[FOF1_KEY_LEN], const uint8_t wire[FOF1_CHALLENGE_LEN],
                                  fof1_challenge* out);

/* ---- response (responder builds, observer verifies) ---------------------------------------------------------- */

/* Serializes v=1 | droneId | nonce[0:8] | pos | tag into out[37]; the tag binds the full nonce, t and challengerId
 * of *challenge, the droneId and the position. */
int fof1_build_response(const uint8_t drone_key[FOF1_KEY_LEN], const fof1_challenge* challenge, uint16_t drone_id,
                        const fof1_position* pos, uint8_t out[FOF1_RESPONSE_LEN]);

/* Parses wire[37] into *out (no verification). */
int fof1_parse_response(const uint8_t wire[FOF1_RESPONSE_LEN], fof1_response* out);

/* The observer's full verify order (1 ledger, 2 tag, 3 position, 4 timing) for the answer `wire` to `challenge`.
 * `wire == NULL` means nothing answered -> FOF1_UNKNOWN_NO_RESPONSE. `response_ms` is the gadget-measured
 * challenge-to-answer time, `observer` / `heading_deg` the configured (or GPS) observer position and camera heading,
 * `now` the unix time at verification. When `out` is not NULL the decoded response is stored there. */
fof1_verdict fof1_verify_response(const uint8_t day_key[FOF1_KEY_LEN], fof1_nonce_ledger* ledger,
                                  const fof1_challenge* challenge, const uint8_t wire[FOF1_RESPONSE_LEN],
                                  uint32_t response_ms, const fof1_position* observer, int16_t heading_deg,
                                  uint32_t now, fof1_response* out);

/* ---- nonce ledger -------------------------------------------------------------------------------------------- */

void fof1_ledger_init(fof1_nonce_ledger* ledger);

/* Remembers a nonce the observer just issued at unix time t (overwrites the oldest slot). */
int fof1_ledger_issue(fof1_nonce_ledger* ledger, const uint8_t nonce[FOF1_NONCE_LEN], uint32_t t);

/* Accepts nonce8 once: FOF1_FRIEND when it matches an issued, unused entry younger than FOF1_NONCE_WINDOW_S (the
 * entry is then marked used); FOF1_FOE_REPLAYED_NONCE otherwise (unknown, already used, or expired). */
fof1_verdict fof1_ledger_consume(fof1_nonce_ledger* ledger, const uint8_t nonce8[8], uint32_t now);

/* ---- position gate ------------------------------------------------------------------------------------------- */

/* 1 when `claimed` passes the gate seen from `observer` looking along heading_deg (0 = north), else 0.
 * Equirectangular distance; near field <= FOF1_NEAR_FIELD_M passes; > FOF1_RANGE_MAX_M fails; otherwise the bearing
 * observer -> claimed must be within +-FOF1_CONE_DEG of heading_deg. Altitude is carried but not gated (v0.1). */
int fof1_position_gate(const fof1_position* observer, int16_t heading_deg, const fof1_position* claimed);

/* ---- strings ------------------------------------------------------------------------------------------------- */

/* The JSON "verdict" word: "FRIEND", "FOE", "UNKNOWN". */
const char* fof1_verdict_word(fof1_verdict v);

/* The JSON "reason" text ("replayed nonce", "bad tag", "position mismatch", "late response", "no response"),
 * NULL for FOF1_FRIEND. */
const char* fof1_verdict_reason(fof1_verdict v);

#ifdef __cplusplus
}
#endif

#endif /* FOF1_H */
