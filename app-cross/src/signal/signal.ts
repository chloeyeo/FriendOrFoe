import type { VerdictKind } from '../core/types';

/** Where an answer came from (signal/SignalKind.kt). */
export type SignalKind = 'USB_GADGET' | 'FAKE';

/** One challenge the phone asks the gadget to run; `t` is unix seconds (signal/ChallengeRequest.kt). */
export interface ChallengeRequest {
  boxId: number;
  challengerId: number;
  t: number;
}

/** What the gadget reports after the radio exchange and its own crypto (signal/SignalEvent.kt). */
export interface SignalAnswer {
  type: 'answer';
  verdict: VerdictKind;
  reason: string | null;
  droneId: string | null;
  /** 16 bytes as lower-case hex; the phone rejects any nonce it has seen before. */
  nonceHex: string;
  rssiDbm: number | null;
  /** Challenge-to-answer time measured by the gadget (10000 on timeout). */
  responseMs: number;
  source: SignalKind;
}

export interface SignalStatus {
  type: 'status';
  text: string;
}

export type SignalEvent = SignalAnswer | SignalStatus;

/**
 * Contract 2 of 3 (signal/SignalSource.kt): runs one challenge and reports the gadget's lines through `emit` as
 * they arrive — zero or more statuses, then at most one answer. The returned promise settles after the answer or
 * when the source gives up; the coordinator owns the 10 s window and stops listening after it.
 */
export interface SignalSource {
  readonly kind: SignalKind;
  challenge(req: ChallengeRequest, emit: (event: SignalEvent) => void, signal: AbortSignal): Promise<void>;
  close(): void;
}

const HEX = '0123456789abcdef';

export function bytesToHex(bytes: Uint8Array): string {
  let out = '';
  for (const b of bytes) out += HEX[b >>> 4] + HEX[b & 0x0f];
  return out;
}

/** Accepts either case; throws on odd length or a non-hex character (signal/Hex.kt). Returns lower case. */
export function normalizeHex(hex: string): string {
  const s = hex.trim();
  if (s.length % 2 !== 0) throw new Error(`hex string has odd length ${s.length}`);
  if (!/^[0-9a-fA-F]*$/.test(s)) throw new Error(`not a hex string: '${s}'`);
  return s.toLowerCase();
}
