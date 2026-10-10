import type { VerdictKind } from '../core/types';
import { bytesToHex, type ChallengeRequest, type SignalAnswer, type SignalEvent, type SignalSource } from './signal';

/** The drone-side gadget's attack modes, mirrored so the phone can be demoed without hardware. */
export type FakeMode = 'HONEST' | 'REPLAY' | 'RELAY' | 'LATE' | 'SILENT' | 'IMPOSTOR';

export const FAKE_MODES: FakeMode[] = ['HONEST', 'REPLAY', 'RELAY', 'LATE', 'SILENT', 'IMPOSTOR'];

/** mulberry32: tiny deterministic PRNG so the fake nonces repeat across runs like `Random(7)` in Kotlin. */
function prng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function sleep(ms: number, signal: AbortSignal): Promise<boolean> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve(false);
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve(true);
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      resolve(false);
    };
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

/**
 * Scripted answers for every verdict through the real session path (signal/FakeSignalSource.kt): a status line,
 * then after a short delay the answer a gadget in that mode would send.
 * HONEST FRIEND −58 dBm 42 ms · REPLAY FOE `replayed nonce` re-using the previous nonce (so the phone ledger fires
 * too) · RELAY FOE `position mismatch` · LATE FRIEND after 3.5 s (the phone turns it into `late response`) ·
 * SILENT nothing (the 10 s window ends UNKNOWN) · IMPOSTOR FOE `bad tag`.
 */
export class FakeSignalSource implements SignalSource {
  readonly kind = 'FAKE' as const;
  mode: FakeMode;
  private lastNonce: string | null = null;
  private readonly random: () => number;

  constructor(mode: FakeMode = 'HONEST', private readonly droneId = 'RQ-07', seed = 7) {
    this.mode = mode;
    this.random = prng(seed);
  }

  async challenge(_req: ChallengeRequest, emit: (event: SignalEvent) => void, signal: AbortSignal): Promise<void> {
    const mode = this.mode;
    if (mode === 'SILENT') return;
    emit({ type: 'status', text: `challenge sent (fake, ${mode})` });
    const answer = (verdict: VerdictKind, reason: string | null, nonceHex: string, rssiDbm: number, responseMs: number): SignalAnswer => ({
      type: 'answer', verdict, reason, droneId: this.droneId, nonceHex, rssiDbm, responseMs, source: 'FAKE',
    });
    switch (mode) {
      case 'HONEST':
        if (await sleep(300, signal)) emit(answer('FRIEND', null, this.freshNonce(), -58, 42));
        break;
      case 'REPLAY': {
        const replayed = this.lastNonce ?? this.freshNonce();
        if (await sleep(300, signal)) emit(answer('FOE', 'replayed nonce', replayed, -61, 40));
        break;
      }
      case 'RELAY':
        if (await sleep(600, signal)) emit(answer('FOE', 'position mismatch', this.freshNonce(), -66, 180));
        break;
      case 'LATE':
        if (await sleep(3500, signal)) emit(answer('FRIEND', null, this.freshNonce(), -60, 3450));
        break;
      case 'IMPOSTOR':
        if (await sleep(300, signal)) emit(answer('FOE', 'bad tag', this.freshNonce(), -59, 45));
        break;
    }
  }

  close(): void {}

  private freshNonce(): string {
    const bytes = new Uint8Array(16);
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(this.random() * 256);
    this.lastNonce = bytesToHex(bytes);
    return this.lastNonce;
  }
}
