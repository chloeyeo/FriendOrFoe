#!/usr/bin/env python
"""FOF-1 reference implementation and test-vector generator (standard library only: hmac, hashlib, struct, json).

This file is the independent reference the Arduino `firmware/iff` library is checked against: the `selftest`
sketch replays the vectors it emits and prints PASS/FAIL per vector. Everything here is deterministic — a fixed
TEST master key, nonces derived from the vector name, fixed timestamps — so re-running it reproduces the same
files byte for byte.

Outputs (run from anywhere; paths are relative to this file):
  eval/vectors/<name>.json         one vector per file, every intermediate value included for debugging
  firmware/selftest/vectors.h      the same vectors as C arrays (fof1_vector FOF1_VECTORS[])

Protocol (docs/PROTOCOL.md is the prose; this is the executable version). All integers big-endian.

  dayKey   = HMAC-SHA256(master, "FOF-day" || "YYYYMMDD")          day = UTC date of the challenge's t
  droneKey = HMAC-SHA256(dayKey, "FOF-drone" || droneId:u16)

  challenge (31 B):  v:u8=1 | nonce:16 | t:u32 (unix s) | challengerId:u16 | cmac:8
                     cmac = HMAC-SHA256(dayKey, "C" || nonce || t:u32 || challengerId:u16)[0:8]
  response  (37 B):  v:u8=1 | droneId:u16 | nonce[0:8] | lat:i32 (1e-4 deg) | lon:i32 (1e-4 deg) | alt:i16 (10 m) | tag:16
                     tag  = HMAC-SHA256(droneKey, "R" || nonce(16) || t:u32 || challengerId:u16 || droneId:u16
                                                  || lat:i32 || lon:i32 || alt:i16)[0:16]

  verify order on the gadget (observer side):
    1. nonce ledger: issued by us, unused, younger than 10 s           -> FOE_REPLAYED_NONCE
    2. tag recomputes under droneKey(dayKey(day(t)), droneId)           -> FOE_BAD_TAG
    3. position gate: within 30 m (near field) passes; beyond 3 km fails; between: the bearing from the
       observer to the claimed position is within +-20 deg of the observer's heading               -> FOE_POSITION_MISMATCH
    4. response_ms within the 2500 ms budget                            -> FOE_LATE_RESPONSE
    no response within the window                                       -> UNKNOWN_NO_RESPONSE
"""

from __future__ import annotations

import datetime as _dt
import hashlib
import hmac
import json
import math
import os
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
VECTORS_DIR = os.path.join(HERE, "vectors")
VECTORS_H = os.path.join(ROOT, "firmware", "selftest", "vectors.h")

# ---------------------------------------------------------------------------------------------------------------
# Constants (mirrored in firmware/iff/src/fof1.h)
# ---------------------------------------------------------------------------------------------------------------
VERSION = 1
NONCE_LEN = 16
CHALLENGE_LEN = 31
RESPONSE_LEN = 37
CMAC_LEN = 8
TAG_LEN = 16
NONCE_WINDOW_S = 10
LATE_MS = 2500
NEAR_FIELD_M = 30.0
RANGE_MAX_M = 3000.0
CONE_DEG = 20.0
NO_RESPONSE_MS = 10000

# TEST ONLY master key: 00 01 02 ... 1f. Never used on real hardware.
MASTER_KEY = bytes(range(32))
# TEST ONLY impostor key (every byte 0xA5): what an enemy without the pre-placed key would have to use.
IMPOSTOR_KEY = bytes([0xA5] * 32)

VERDICTS = {
    "FOF1_FRIEND": None,
    "FOF1_FOE_REPLAYED_NONCE": "replayed nonce",
    "FOF1_FOE_BAD_TAG": "bad tag",
    "FOF1_FOE_POSITION_MISMATCH": "position mismatch",
    "FOF1_FOE_LATE_RESPONSE": "late response",
    "FOF1_UNKNOWN_NO_RESPONSE": "no response",
}


# ---------------------------------------------------------------------------------------------------------------
# Keys
# ---------------------------------------------------------------------------------------------------------------
def hmac256(key: bytes, msg: bytes) -> bytes:
    return hmac.new(key, msg, hashlib.sha256).digest()


def day_string(t: int) -> str:
    """UTC date of unix time t as YYYYMMDD."""
    return _dt.datetime.fromtimestamp(t, _dt.timezone.utc).strftime("%Y%m%d")


def derive_day_key(master: bytes, day: str) -> bytes:
    assert len(master) == 32 and len(day) == 8
    return hmac256(master, b"FOF-day" + day.encode("ascii"))


def derive_drone_key(day_key: bytes, drone_id: int) -> bytes:
    return hmac256(day_key, b"FOF-drone" + struct.pack(">H", drone_id))


# ---------------------------------------------------------------------------------------------------------------
# Challenge
# ---------------------------------------------------------------------------------------------------------------
def challenge_mac_input(nonce: bytes, t: int, challenger_id: int) -> bytes:
    return b"C" + nonce + struct.pack(">IH", t, challenger_id)


def build_challenge(day_key: bytes, nonce: bytes, t: int, challenger_id: int) -> bytes:
    assert len(nonce) == NONCE_LEN
    cmac = hmac256(day_key, challenge_mac_input(nonce, t, challenger_id))[:CMAC_LEN]
    wire = struct.pack(">B", VERSION) + nonce + struct.pack(">IH", t, challenger_id) + cmac
    assert len(wire) == CHALLENGE_LEN
    return wire


def parse_challenge(wire: bytes) -> dict:
    assert len(wire) == CHALLENGE_LEN, len(wire)
    (v,) = struct.unpack(">B", wire[:1])
    nonce = wire[1:17]
    t, challenger_id = struct.unpack(">IH", wire[17:23])
    cmac = wire[23:31]
    return {"v": v, "nonce": nonce, "t": t, "challenger_id": challenger_id, "cmac": cmac}


def check_challenge(day_key: bytes, wire: bytes) -> bool:
    """The responder's gate: it answers only an authentic challenge (closes 'ask tiger first to harvest lion')."""
    ch = parse_challenge(wire)
    if ch["v"] != VERSION:
        return False
    expect = hmac256(day_key, challenge_mac_input(ch["nonce"], ch["t"], ch["challenger_id"]))[:CMAC_LEN]
    return hmac.compare_digest(expect, ch["cmac"])


# ---------------------------------------------------------------------------------------------------------------
# Position
# ---------------------------------------------------------------------------------------------------------------
def quantize(lat: float, lon: float, alt_m: float) -> tuple[int, int, int]:
    """lat/lon to 1e-4 deg (about 11 m), altitude to 10 m, as signed integers (southern/western are negative)."""
    return int(round(lat * 1e4)), int(round(lon * 1e4)), int(round(alt_m / 10.0))


def distance_and_bearing(obs: tuple[int, int, int], claimed: tuple[int, int, int]) -> tuple[float, float]:
    """Equirectangular horizontal distance in meters and the bearing observer -> claimed in degrees (0 = north).
    Good to well under 1 % at the ranges that matter (< 3 km)."""
    lat1 = obs[0] * 1e-4
    lat2 = claimed[0] * 1e-4
    dlat = (claimed[0] - obs[0]) * 1e-4
    dlon = (claimed[1] - obs[1]) * 1e-4
    mean_lat = math.radians((lat1 + lat2) / 2.0)
    north = dlat * 111320.0
    east = dlon * 111320.0 * math.cos(mean_lat)
    distance = math.hypot(north, east)
    bearing = (math.degrees(math.atan2(east, north)) + 360.0) % 360.0
    return distance, bearing


def position_gate(obs: tuple[int, int, int], heading_deg: float, claimed: tuple[int, int, int]) -> bool:
    distance, bearing = distance_and_bearing(obs, claimed)
    if distance <= NEAR_FIELD_M:
        return True
    if distance > RANGE_MAX_M:
        return False
    diff = abs((bearing - heading_deg + 180.0) % 360.0 - 180.0)
    return diff <= CONE_DEG


# ---------------------------------------------------------------------------------------------------------------
# Response
# ---------------------------------------------------------------------------------------------------------------
def response_mac_input(nonce: bytes, t: int, challenger_id: int, drone_id: int, pos: tuple[int, int, int]) -> bytes:
    return b"R" + nonce + struct.pack(">IHHiih", t, challenger_id, drone_id, pos[0], pos[1], pos[2])


def build_response(drone_key: bytes, nonce: bytes, t: int, challenger_id: int, drone_id: int, pos: tuple[int, int, int]) -> bytes:
    tag = hmac256(drone_key, response_mac_input(nonce, t, challenger_id, drone_id, pos))[:TAG_LEN]
    wire = struct.pack(">BH", VERSION, drone_id) + nonce[:8] + struct.pack(">iih", pos[0], pos[1], pos[2]) + tag
    assert len(wire) == RESPONSE_LEN
    return wire


def parse_response(wire: bytes) -> dict:
    assert len(wire) == RESPONSE_LEN, len(wire)
    v, drone_id = struct.unpack(">BH", wire[:3])
    nonce8 = wire[3:11]
    lat_q, lon_q, alt_q = struct.unpack(">iih", wire[11:21])
    tag = wire[21:37]
    return {"v": v, "drone_id": drone_id, "nonce8": nonce8, "pos": (lat_q, lon_q, alt_q), "tag": tag}


class NonceLedger:
    """The observer's record of the nonces it issued: a nonce is accepted once, within NONCE_WINDOW_S."""

    def __init__(self) -> None:
        self.entries: dict[bytes, dict] = {}

    def issue(self, nonce: bytes, t: int) -> None:
        self.entries[nonce] = {"issued_t": t, "used": False}

    def consume(self, nonce8: bytes, now: int) -> bool:
        for nonce, entry in self.entries.items():
            if nonce[:8] == nonce8:
                if entry["used"] or now - entry["issued_t"] >= NONCE_WINDOW_S:
                    return False
                entry["used"] = True
                return True
        return False


def verify_response(day_key: bytes, ledger: NonceLedger, challenge: dict, wire: bytes | None, response_ms: int,
                    obs: tuple[int, int, int], heading_deg: float, now: int) -> str:
    if wire is None:
        return "FOF1_UNKNOWN_NO_RESPONSE"
    resp = parse_response(wire)
    if resp["v"] != VERSION:
        return "FOF1_FOE_BAD_TAG"
    # 1. nonce: ours, unused, fresh
    if resp["nonce8"] != challenge["nonce"][:8] or not ledger.consume(resp["nonce8"], now):
        return "FOF1_FOE_REPLAYED_NONCE"
    # 2. tag
    drone_key = derive_drone_key(day_key, resp["drone_id"])
    expect = hmac256(drone_key, response_mac_input(challenge["nonce"], challenge["t"], challenge["challenger_id"],
                                                   resp["drone_id"], resp["pos"]))[:TAG_LEN]
    if not hmac.compare_digest(expect, resp["tag"]):
        return "FOF1_FOE_BAD_TAG"
    # 3. position
    if not position_gate(obs, heading_deg, resp["pos"]):
        return "FOF1_FOE_POSITION_MISMATCH"
    # 4. timing
    if response_ms > LATE_MS:
        return "FOF1_FOE_LATE_RESPONSE"
    return "FOF1_FRIEND"


# ---------------------------------------------------------------------------------------------------------------
# Vectors
# ---------------------------------------------------------------------------------------------------------------
def nonce_for(name: str) -> bytes:
    """A fixed, name-derived 16-byte nonce (a real gadget uses its hardware RNG)."""
    return hmac256(MASTER_KEY, b"test-nonce:" + name.encode("ascii"))[:NONCE_LEN]


SEOUL = (37.5665, 126.9780, 30.0)        # observer, northern hemisphere
SYDNEY = (-33.8688, 151.2093, 20.0)      # observer, southern hemisphere (negative latitude)
T_DEMO = 1760000000                      # the README's example timestamp (2025-10-09 08:53:20 UTC)
T_MIDNIGHT = int(_dt.datetime(2026, 10, 10, 0, 0, 0, tzinfo=_dt.timezone.utc).timestamp())  # 1791590400


def offset(pos: tuple[float, float, float], north_m: float, east_m: float, dalt_m: float = 0.0) -> tuple[float, float, float]:
    lat, lon, alt = pos
    dlat = north_m / 111320.0
    dlon = east_m / (111320.0 * math.cos(math.radians(lat)))
    return lat + dlat, lon + dlon, alt + dalt_m


def make_vector(name: str, description: str, *, t: int, observer: tuple[float, float, float], heading_deg: float,
                responder: tuple[float, float, float] | None, response_ms: int, now_offset_s: int = 1,
                challenger_id: int = 7, drone_id: int = 7, responder_master: bytes = MASTER_KEY,
                nonce_consumed_before: bool = False, expected: str) -> dict:
    nonce = nonce_for(name)
    day = day_string(t)
    day_key = derive_day_key(MASTER_KEY, day)
    drone_key = derive_drone_key(day_key, drone_id)
    challenge = build_challenge(day_key, nonce, t, challenger_id)
    assert check_challenge(day_key, challenge)
    obs_q = quantize(*observer)

    response = None
    tag = None
    resp_q = None
    if responder is not None:
        resp_q = quantize(*responder)
        responder_day_key = derive_day_key(responder_master, day)
        responder_drone_key = derive_drone_key(responder_day_key, drone_id)
        response = build_response(responder_drone_key, nonce, t, challenger_id, drone_id, resp_q)
        tag = response[-TAG_LEN:]

    # Replay the verification exactly as the gadget would, and insist the designed verdict comes out.
    ledger = NonceLedger()
    ledger.issue(nonce, t)
    now = t + now_offset_s
    if nonce_consumed_before:
        assert ledger.consume(nonce[:8], now)
    verdict = verify_response(day_key, ledger, parse_challenge(challenge), response, response_ms, obs_q, heading_deg, now)
    assert verdict == expected, f"{name}: reference verifier says {verdict}, vector expects {expected}"

    return {
        "name": name,
        "description": description,
        "protocol": "FOF-1",
        "version": VERSION,
        "master_key_hex": MASTER_KEY.hex(),
        "responder_master_key_hex": responder_master.hex(),
        "day": day,
        "day_key_hex": day_key.hex(),
        "drone_key_hex": drone_key.hex(),
        "t": t,
        "now": now,
        "challenger_id": challenger_id,
        "drone_id": drone_id,
        "drone_name": f"RQ-{drone_id:02d}",
        "nonce_hex": nonce.hex(),
        "challenge_hex": challenge.hex(),
        "cmac_hex": challenge[-CMAC_LEN:].hex(),
        "observer": {
            "lat": observer[0], "lon": observer[1], "alt_m": observer[2],
            "lat_e4": obs_q[0], "lon_e4": obs_q[1], "alt_10m": obs_q[2], "heading_deg": heading_deg,
        },
        "responder": None if resp_q is None else {
            "lat": responder[0], "lon": responder[1], "alt_m": responder[2],
            "lat_e4": resp_q[0], "lon_e4": resp_q[1], "alt_10m": resp_q[2],
        },
        "response_hex": None if response is None else response.hex(),
        "tag_hex": None if tag is None else tag.hex(),
        "response_ms": response_ms,
        "nonce_consumed_before": nonce_consumed_before,
        "gates": {
            "nonce_window_s": NONCE_WINDOW_S, "late_ms": LATE_MS, "near_field_m": NEAR_FIELD_M,
            "range_max_m": RANGE_MAX_M, "cone_deg": CONE_DEG,
        },
        "expected": {"verdict": expected, "reason": VERDICTS[expected]},
    }


def all_vectors() -> list[dict]:
    return [
        make_vector(
            "friend", "Honest responder 12 m north-east of the observer (near field), quick answer.",
            t=T_DEMO, observer=SEOUL, heading_deg=45.0, responder=offset(SEOUL, 8.5, 8.5, 25.0),
            response_ms=42, expected="FOF1_FRIEND",
        ),
        make_vector(
            "friend_south", "Honest responder in the southern hemisphere (negative latitude), near field.",
            t=T_DEMO + 600, observer=SYDNEY, heading_deg=300.0, responder=offset(SYDNEY, -10.0, 9.0, 15.0),
            response_ms=55, expected="FOF1_FRIEND",
        ),
        make_vector(
            "friend_midnight_utc", "Honest responder at exactly 2026-10-10T00:00:00Z: the day key is for 20261010.",
            t=T_MIDNIGHT, observer=SEOUL, heading_deg=45.0, responder=offset(SEOUL, 8.5, 8.5, 25.0),
            response_ms=47, expected="FOF1_FRIEND",
        ),
        make_vector(
            "replayed_nonce", "The same answer presented a second time: the nonce was already consumed.",
            t=T_DEMO + 20, observer=SEOUL, heading_deg=45.0, responder=offset(SEOUL, 8.5, 8.5, 25.0),
            response_ms=40, nonce_consumed_before=True, expected="FOF1_FOE_REPLAYED_NONCE",
        ),
        make_vector(
            "bad_tag", "Impostor: a responder without the pre-placed master key (tag computed with the wrong key).",
            t=T_DEMO + 40, observer=SEOUL, heading_deg=45.0, responder=offset(SEOUL, 8.5, 8.5, 25.0),
            response_ms=45, responder_master=IMPOSTOR_KEY, expected="FOF1_FOE_BAD_TAG",
        ),
        make_vector(
            "position_mismatch", "Relay: a valid answer claiming a position 5.2 km away, beyond the 3 km range gate.",
            t=T_DEMO + 60, observer=SEOUL, heading_deg=45.0, responder=offset(SEOUL, 3700.0, 3700.0, 120.0),
            response_ms=180, expected="FOF1_FOE_POSITION_MISMATCH",
        ),
        make_vector(
            "late_response", "Relay latency: a valid near-field answer that took 4000 ms, over the 2500 ms budget.",
            t=T_DEMO + 80, observer=SEOUL, heading_deg=45.0, responder=offset(SEOUL, 8.5, 8.5, 25.0),
            response_ms=4000, now_offset_s=4, expected="FOF1_FOE_LATE_RESPONSE",
        ),
        make_vector(
            "no_response", "Silence: nothing answered within the window. Never FRIEND.",
            t=T_DEMO + 100, observer=SEOUL, heading_deg=45.0, responder=None,
            response_ms=NO_RESPONSE_MS, now_offset_s=10, expected="FOF1_UNKNOWN_NO_RESPONSE",
        ),
    ]


# ---------------------------------------------------------------------------------------------------------------
# Emit
# ---------------------------------------------------------------------------------------------------------------
def c_bytes(hex_or_none: str | None, length: int) -> str:
    data = bytes.fromhex(hex_or_none) if hex_or_none else bytes(length)
    assert len(data) == length, (len(data), length)
    return "{" + ", ".join(f"0x{b:02x}" for b in data) + "}"


def c_string(text: str | None) -> str:
    if text is None:
        return "NULL"
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def emit_header(vectors: list[dict]) -> str:
    lines = [
        "// Generated by eval/gen_vectors.py — do not edit; re-run the generator instead.",
        "// FOF-1 test vectors for the firmware/selftest sketch. Every array is the wire layout, big-endian.",
        "#pragma once",
        "#include <stdint.h>",
        "#include <stddef.h>",
        '#include "fof1.h"',
        "",
        "typedef struct {",
        "  const char* name;",
        "  const char* description;",
        "  uint8_t master_key[FOF1_KEY_LEN];",
        "  char day[9];                 /* YYYYMMDD, UTC date of t */",
        "  uint8_t day_key[FOF1_KEY_LEN];",
        "  uint8_t drone_key[FOF1_KEY_LEN];",
        "  uint32_t t;                  /* challenge time, unix seconds */",
        "  uint32_t now;                /* verification time, unix seconds */",
        "  uint16_t challenger_id;",
        "  uint16_t drone_id;",
        "  uint8_t nonce[FOF1_NONCE_LEN];",
        "  uint8_t challenge[FOF1_CHALLENGE_LEN];",
        "  uint8_t has_response;        /* 0: nothing answered (response[] is zeros) */",
        "  uint8_t response[FOF1_RESPONSE_LEN];",
        "  uint8_t response_is_honest;  /* 1: built with the right key; fof1_build_response must reproduce it */",
        "  int32_t resp_lat_e4; int32_t resp_lon_e4; int16_t resp_alt_10m;",
        "  int32_t obs_lat_e4; int32_t obs_lon_e4; int16_t obs_alt_10m; int16_t obs_heading_deg;",
        "  uint32_t response_ms;",
        "  uint8_t nonce_consumed_before; /* 1: consume the nonce once before verifying (replay) */",
        "  uint8_t expected_verdict;    /* a fof1_verdict value */",
        "  const char* expected_reason; /* NULL for FRIEND */",
        "} fof1_vector;",
        "",
        f"#define FOF1_VECTOR_COUNT {len(vectors)}",
        "",
        "static const fof1_vector FOF1_VECTORS[FOF1_VECTOR_COUNT] = {",
    ]
    for v in vectors:
        resp = v["responder"] or {"lat_e4": 0, "lon_e4": 0, "alt_10m": 0}
        obs = v["observer"]
        lines += [
            "  {",
            f"    {c_string(v['name'])},",
            f"    {c_string(v['description'])},",
            f"    {c_bytes(v['master_key_hex'], 32)},",
            f"    {c_string(v['day'])},",
            f"    {c_bytes(v['day_key_hex'], 32)},",
            f"    {c_bytes(v['drone_key_hex'], 32)},",
            f"    {v['t']}u, {v['now']}u,",
            f"    {v['challenger_id']}, {v['drone_id']},",
            f"    {c_bytes(v['nonce_hex'], 16)},",
            f"    {c_bytes(v['challenge_hex'], 31)},",
            f"    {1 if v['response_hex'] else 0},",
            f"    {c_bytes(v['response_hex'], 37)},",
            f"    {1 if v['response_hex'] and v['responder_master_key_hex'] == v['master_key_hex'] else 0},",
            f"    {resp['lat_e4']}, {resp['lon_e4']}, {resp['alt_10m']},",
            f"    {obs['lat_e4']}, {obs['lon_e4']}, {obs['alt_10m']}, {int(round(obs['heading_deg']))},",
            f"    {v['response_ms']}u,",
            f"    {1 if v['nonce_consumed_before'] else 0},",
            f"    (uint8_t){v['expected']['verdict']},",
            f"    {c_string(v['expected']['reason'])},",
            "  },",
        ]
    lines += ["};", ""]
    return "\n".join(lines)


def main() -> int:
    vectors = all_vectors()
    os.makedirs(VECTORS_DIR, exist_ok=True)
    for v in vectors:
        path = os.path.join(VECTORS_DIR, v["name"] + ".json")
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(v, fh, indent=2)
            fh.write("\n")
        print(f"wrote {os.path.relpath(path, ROOT)}  ->  {v['expected']['verdict']}")
    os.makedirs(os.path.dirname(VECTORS_H), exist_ok=True)
    with open(VECTORS_H, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(emit_header(vectors))
    print(f"wrote {os.path.relpath(VECTORS_H, ROOT)}  ({len(vectors)} vectors)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
