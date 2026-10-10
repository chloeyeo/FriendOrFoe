import type { VerdictKind } from '../core/types';
import type { SignalAnswer } from './signal';

export interface ReChecksConfig {
  rttBudgetMs: number;
  rssiFloorDbm: number;
  /** An answer without RSSI fails instead of being skipped. */
  strict: boolean;
}

export const DEFAULT_RECHECKS: ReChecksConfig = { rttBudgetMs: 2500, rssiFloorDbm: -70, strict: false };

export interface ReCheckResult {
  verdict: VerdictKind;
  reason: string | null;
  notes: string[];
}

/**
 * The phone's own checks on a gadget answer (signal/ReChecks.kt): the nonce was never seen by this phone
 * (`replayed nonce`); the phone-measured round trip and the gadget's `responseMs` are both within the budget
 * (`late response`); the RSSI is at or above the floor (`signal mismatch`). The stricter verdict wins
 * (FRIEND < UNKNOWN < FOE); when the phone escalates its reason replaces the gadget's, when the gadget already said
 * FOE its reason is kept and the phone's finding goes to the notes. UNKNOWN passes through, only recording the nonce.
 */
export class ReChecks {
  private seen = new Set<string>();

  constructor(public config: ReChecksConfig = DEFAULT_RECHECKS) {}

  get seenCount(): number {
    return this.seen.size;
  }

  check(answer: SignalAnswer, roundTripMs: number): ReCheckResult {
    const notes: string[] = [];
    let verdict = answer.verdict;
    let reason = answer.reason;
    const fail = (phoneReason: string) => {
      if (verdict !== 'FOE') {
        verdict = 'FOE';
        reason = phoneReason;
        notes.push(`phone: ${phoneReason}`);
      } else {
        notes.push(`phone also: ${phoneReason}`);
      }
    };

    const nonce = answer.nonceHex;
    const fresh = nonce === null || !this.seen.has(nonce);
    if (nonce !== null) this.seen.add(nonce);

    if (answer.verdict === 'UNKNOWN') {
      notes.push('no answer to re-check');
      return { verdict, reason, notes };
    }
    if (nonce === null) notes.push('nonce not reported; replay check skipped');
    else if (!fresh) fail('replayed nonce');
    if (roundTripMs > this.config.rttBudgetMs || answer.responseMs > this.config.rttBudgetMs) fail('late response');
    const rssi = answer.rssiDbm;
    if (rssi === null) {
      if (this.config.strict) fail('signal mismatch');
      else notes.push('rssi not reported; signal check skipped');
    } else if (rssi < this.config.rssiFloorDbm) {
      fail('signal mismatch');
    }
    return { verdict, reason, notes };
  }

  /** Forgets the nonce ledger (a new day key, a new session, the Reset button). */
  reset(): void {
    this.seen.clear();
  }
}
