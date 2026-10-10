import type { VerdictKind } from '../core/types';
import { normalizeHex, type ChallengeRequest, type SignalAnswer } from './signal';

/**
 * The BLE IFF reader's serial interface v2 (firmware/README.md, firmware/reader/reader.ino): an ESP32-C3 on USB CDC,
 * 115200 baud, LF line endings. The phone sends ASCII commands and reads one JSON object per line.
 *
 *   phone -> reader   QUERY <request_id>      one challenge broadcast; `[A-Za-z0-9_-]{1,32}`, never reused
 *                     STATUS [request_id]     a STATUS snapshot, including the last RESULT
 *   reader -> phone   {"event":"ACK","request_id":"q1","accepted":true,"reason":"ACCEPTED","state":"QUERYING"}
 *                     {"event":"RESULT","request_id":"q1","query":1,"response_received":true,"verdict":"FRIEND",
 *                      "reason":"NONE","response_ms":337,"elapsed_ms":337,"replay":false}
 *                     {"event":"STATE","boot_id":"b1f8b86d5ad40779","request_id":"q1","state":"IDLE","reason":"CHANGED"}
 *                     {"event":"STATUS","role":"reader","interface_version":2,"key_configured":true,...}
 *                     {"event":"TX_CHALLENGE"|"RX_RESPONSE","request_id":"q1","nonce":"<32 hex>","drone_id":1,...}
 *
 * The reader only says FRIEND or UNKNOWN; the phone owns the FOE verdict (see [answerFromResult]). `KEY` is never
 * sent from the phone: the HMAC key is set once over the laptop's serial monitor and stays on the reader.
 */
export const READER_INTERFACE_VERSION = 2;

const REQUEST_ID = /^[A-Za-z0-9_-]{1,32}$/;

export type ReaderState = 'IDLE' | 'QUERYING' | 'COOLDOWN';
export type ReaderReason = 'NONE' | 'NO_RESPONSE' | 'INVALID_MESSAGE' | 'AUTH_FAILED' | 'RADIO_ERROR';

export interface ReaderResult {
  event: 'RESULT';
  requestId: string;
  query: number;
  responseReceived: boolean;
  verdict: 'FRIEND' | 'UNKNOWN';
  reason: ReaderReason;
  /** Challenge broadcast to the authenticated response, measured on the reader; null without a response. */
  responseMs: number | null;
  elapsedMs: number;
  /** A repeat of the last result (a duplicate QUERY or a STATUS snapshot), not a radio replay. */
  replay: boolean;
}

export interface ReaderStatus {
  event: 'STATUS';
  requestId: string;
  role: string;
  interfaceVersion: number | null;
  bootId: string | null;
  manualQuery: boolean;
  keyConfigured: boolean;
  radioReady: boolean;
  state: ReaderState | null;
  lastResult: ReaderResult | null;
}

/** A radio packet the reader logged for the current request (the diagnostic lines; the reader may drop these). */
export interface ReaderPacket {
  event: 'TX_CHALLENGE' | 'RX_RESPONSE';
  requestId: string;
  query: number;
  /** BROADCAST_STARTED, FRIEND, AUTH_FAILED, IGNORED_NONCE_MISMATCH, IGNORED_LATE_RESPONSE, ... */
  result: string;
  nonceHex: string | null;
  droneId: number | null;
}

export type ReaderEvent =
  | { event: 'ACK'; requestId: string; accepted: boolean; reason: string; state: ReaderState | null }
  | ReaderResult
  | { event: 'STATE'; bootId: string; requestId: string; state: ReaderState; reason: string }
  | ReaderStatus
  | ReaderPacket
  | { event: 'DOWNLOAD_MODE' };

export function isRequestId(id: string): boolean {
  return REQUEST_ID.test(id);
}

export function encodeQuery(requestId: string): string {
  if (!isRequestId(requestId)) throw new Error(`bad request id '${requestId}'`);
  return `QUERY ${requestId}`;
}

export function encodeStatus(requestId?: string): string {
  if (requestId === undefined) return 'STATUS';
  if (!isRequestId(requestId)) throw new Error(`bad request id '${requestId}'`);
  return `STATUS ${requestId}`;
}

/** `c7b3-mg1x2k-4f` style: challenger, box, time and a salt, so no two challenges share an id (the reader answers a
 * repeated id with the old result instead of a new broadcast). */
export function requestIdFor(req: ChallengeRequest, salt: number): string {
  const id = `c${req.challengerId}b${req.boxId}-${req.t.toString(36)}-${(salt >>> 0).toString(36)}`;
  return id.length <= 32 ? id : id.slice(id.length - 32);
}

const STATES: ReaderState[] = ['IDLE', 'QUERYING', 'COOLDOWN'];
const REASONS: ReaderReason[] = ['NONE', 'NO_RESPONSE', 'INVALID_MESSAGE', 'AUTH_FAILED', 'RADIO_ERROR'];

const str = (o: Record<string, unknown>, k: string): string | null => (typeof o[k] === 'string' ? (o[k] as string) : null);
const int = (o: Record<string, unknown>, k: string): number | null =>
  typeof o[k] === 'number' && Number.isInteger(o[k]) ? (o[k] as number) : null;
const state = (o: Record<string, unknown>, k: string): ReaderState | null => STATES.find((s) => s === o[k]) ?? null;

/**
 * Parses one reader line. Returns null for anything that is not a well-formed v2 event: a blank line, the ESP32 boot
 * ROM's text, HELP/DIAG lines, or an event with a missing field (the laptop GUI ignores those the same way). The one
 * text line that matters, the ROM's `waiting for download`, becomes DOWNLOAD_MODE.
 */
export function parseReaderLine(line: string): ReaderEvent | null {
  const text = line.trim();
  if (text === '') return null;
  if (!text.startsWith('{')) return /waiting for download/i.test(text) ? { event: 'DOWNLOAD_MODE' } : null;
  let o: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(text);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    o = parsed as Record<string, unknown>;
  } catch {
    return null;
  }
  return parseEvent(o);
}

function parseEvent(o: Record<string, unknown>): ReaderEvent | null {
  const requestId = str(o, 'request_id') ?? '';
  switch (o.event) {
    case 'ACK': {
      const reason = str(o, 'reason');
      if (typeof o.accepted !== 'boolean' || reason === null) return null;
      return { event: 'ACK', requestId, accepted: o.accepted, reason, state: state(o, 'state') };
    }
    case 'RESULT':
      return parseResult(o, requestId);
    case 'STATE': {
      const s = state(o, 'state');
      const bootId = str(o, 'boot_id');
      if (s === null || bootId === null) return null;
      return { event: 'STATE', bootId, requestId, state: s, reason: str(o, 'reason') ?? '' };
    }
    case 'STATUS': {
      const last = o.last_result;
      return {
        event: 'STATUS',
        requestId,
        role: str(o, 'role') ?? '',
        interfaceVersion: int(o, 'interface_version'),
        bootId: str(o, 'boot_id'),
        manualQuery: o.manual_query === true,
        keyConfigured: o.key_configured === true,
        radioReady: o.radio_ready === true,
        state: state(o, 'state'),
        lastResult: last && typeof last === 'object' ? parseResult(last as Record<string, unknown>, str(last as Record<string, unknown>, 'request_id') ?? '') : null,
      };
    }
    case 'TX_CHALLENGE':
    case 'RX_RESPONSE': {
      let nonceHex: string | null = null;
      const nonce = str(o, 'nonce');
      if (nonce !== null) {
        try {
          nonceHex = normalizeHex(nonce);
        } catch {
          nonceHex = null;
        }
      }
      return { event: o.event, requestId, query: int(o, 'query') ?? 0, result: str(o, 'result') ?? '', nonceHex, droneId: int(o, 'drone_id') };
    }
    default:
      return null;
  }
}

function parseResult(o: Record<string, unknown>, requestId: string): ReaderResult | null {
  const verdict = o.verdict === 'FRIEND' || o.verdict === 'UNKNOWN' ? o.verdict : null;
  const reason = REASONS.find((r) => r === o.reason) ?? null;
  const query = int(o, 'query');
  if (verdict === null || reason === null || query === null || typeof o.response_received !== 'boolean') return null;
  return {
    event: 'RESULT',
    requestId,
    query,
    responseReceived: o.response_received,
    verdict,
    reason,
    responseMs: int(o, 'response_ms'),
    elapsedMs: int(o, 'elapsed_ms') ?? 0,
    replay: o.replay === true,
  };
}

/**
 * The reader's result as the phone's answer. FRIEND stays FRIEND. A response that carried our nonce but failed the
 * HMAC (AUTH_FAILED) is FOE `bad tag`: something answered this exact challenge and could not prove the key. Silence,
 * a malformed packet and a radio fault are UNKNOWN: nothing was proven either way. The nonce and drone id come from
 * the reader's packet lines for this request when they arrived; RSSI is not reported by the reader.
 */
export function answerFromResult(r: ReaderResult, packet: { nonceHex: string | null; droneId: number | null }): SignalAnswer {
  const [verdict, reason]: [VerdictKind, string | null] =
    r.verdict === 'FRIEND'
      ? ['FRIEND', null]
      : r.reason === 'AUTH_FAILED'
        ? ['FOE', 'bad tag']
        : ['UNKNOWN', r.reason === 'NO_RESPONSE' ? 'no response' : r.reason.toLowerCase().replace('_', ' ')];
  return {
    type: 'answer',
    verdict,
    reason,
    droneId: packet.droneId !== null ? String(packet.droneId) : null,
    nonceHex: packet.nonceHex,
    rssiDbm: null,
    responseMs: r.responseMs ?? r.elapsedMs,
    source: 'USB_GADGET',
  };
}

/** What an ACK refusal means for the person holding the phone. */
export function refusalText(reason: string): string {
  switch (reason) {
    case 'KEY_NOT_CONFIGURED':
      return 'reader has no key (set it with KEY over the laptop serial monitor)';
    case 'RADIO_ERROR':
      return 'reader radio not ready';
    case 'BUSY':
      return 'reader busy';
    default:
      return `reader refused the query (${reason})`;
  }
}
