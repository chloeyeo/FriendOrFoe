import type { VerdictKind } from '../core/types';
import { normalizeHex, type ChallengeRequest, type SignalEvent } from './signal';

/**
 * The USB gadget link's line format (README "Gadget link over USB", docs/GADGET_LINK.md, signal/GadgetLineCodec.kt):
 * one JSON object per line, 115200 8N1.
 *
 *   phone -> gadget   {"cmd":"challenge","boxId":3,"challengerId":7,"t":1760000000}
 *                     {"cmd":"status"}
 *   gadget -> phone   {"type":"answer","verdict":"FRIEND","reason":null,"droneId":"RQ-07","nonce":"<32 hex>","rssiDbm":-58,"responseMs":42}
 *                     {"type":"status","fw":"0.1","battery":3.9,"mode":"HONEST"}
 */
export function encodeChallenge(req: ChallengeRequest): string {
  return JSON.stringify({ cmd: 'challenge', boxId: req.boxId, challengerId: req.challengerId, t: req.t });
}

export function encodeStatusRequest(): string {
  return JSON.stringify({ cmd: 'status' });
}

const VERDICTS: VerdictKind[] = ['FRIEND', 'FOE', 'UNKNOWN'];

function stringOrNull(obj: Record<string, unknown>, key: string): string | null {
  const v = obj[key];
  return v === undefined || v === null ? null : String(v);
}

/**
 * Parses one gadget -> phone line. Returns null for a blank line or an object whose `type` is not ours; throws
 * for malformed JSON, an unknown verdict, a missing `responseMs` or a bad nonce, so a firmware/phone disagreement
 * fails loudly instead of turning into a silent UNKNOWN.
 */
export function parseLine(line: string): SignalEvent | null {
  const text = line.trim();
  if (text === '') return null;
  let obj: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(text);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not an object');
    obj = parsed as Record<string, unknown>;
  } catch {
    throw new Error(`gadget line is not a JSON object: ${text}`);
  }
  switch (obj.type) {
    case 'answer':
      return parseAnswer(obj, text);
    case 'status':
      return { type: 'status', text: statusText(obj) };
    default:
      return null;
  }
}

function parseAnswer(obj: Record<string, unknown>, text: string): SignalEvent {
  const verdictText = stringOrNull(obj, 'verdict');
  if (verdictText === null) throw new Error(`answer without verdict: ${text}`);
  const verdict = VERDICTS.find((v) => v === verdictText.toUpperCase());
  if (!verdict) throw new Error(`unknown verdict '${verdictText}': ${text}`);
  if (typeof obj.responseMs !== 'number' || !Number.isFinite(obj.responseMs)) {
    throw new Error(`answer without responseMs: ${text}`);
  }
  const nonce = stringOrNull(obj, 'nonce');
  if (nonce === null) throw new Error(`answer without nonce: ${text}`);
  let nonceHex: string;
  try {
    nonceHex = normalizeHex(nonce);
  } catch (e) {
    throw new Error(`answer with a bad nonce '${nonce}': ${(e as Error).message}`);
  }
  const rssi = obj.rssiDbm;
  return {
    type: 'answer',
    verdict,
    reason: stringOrNull(obj, 'reason'),
    droneId: stringOrNull(obj, 'droneId'),
    nonceHex,
    rssiDbm: typeof rssi === 'number' && Number.isFinite(rssi) ? Math.trunc(rssi) : null,
    responseMs: Math.trunc(obj.responseMs),
    source: 'USB_GADGET',
  };
}

/** `fw 0.1 · battery 3.9 V · mode HONEST` from the fields present; `status` when none is. */
function statusText(obj: Record<string, unknown>): string {
  const parts: string[] = [];
  const fw = stringOrNull(obj, 'fw');
  if (fw !== null) parts.push(`fw ${fw}`);
  if (obj.battery !== undefined && obj.battery !== null) parts.push(`battery ${obj.battery} V`);
  const mode = stringOrNull(obj, 'mode');
  if (mode !== null) parts.push(`mode ${mode}`);
  return parts.length === 0 ? 'status' : parts.join(' · ');
}
