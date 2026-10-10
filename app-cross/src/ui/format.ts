import { useEffect, useState } from 'react';

import type { EventUi } from '../core/types';

/** `now`, `12 s ago`, `3 min ago`, `2 h ago` — the web dashboard's wording (web/src/lib/format.ts). */
export function ageText(nowMs: number, tMs: number): string {
  const s = Math.max(0, Math.floor((nowMs - tMs) / 1000));
  if (s < 1) return 'now';
  if (s < 60) return `${s} s ago`;
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  return `${Math.floor(s / 3600)} h ago`;
}

export function clock(tMs: number): string {
  const d = new Date(tMs);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

const REASONS: Record<string, string> = {
  'replayed nonce': 'Replayed response',
  'late response': 'Response too late',
  'position mismatch': 'Position mismatch',
  'signal mismatch': 'Signal mismatch',
  'no response': 'No response',
  'bad tag': 'Authentication failed',
  'unknown drone id': 'Drone id not accepted',
  'unknown person id': 'Person id not accepted',
};

/** The result row's second line, in the web dashboard's words. */
export function reasonOf(e: EventUi): string {
  if (e.reason) return REASONS[e.reason] ?? e.reason;
  return e.verdict === 'FRIEND' ? 'Identity verified' : 'Identity not verified';
}

export function targetOf(e: EventUi): string {
  if (e.cls === 'PERSON') return e.droneId ? `Person · ${e.droneId}` : e.trackId != null ? `Person #${e.trackId}` : 'Unidentified person';
  return e.droneId || (e.trackId != null ? `Drone #${e.trackId}` : 'Unidentified drone');
}

/** Wall-clock ms, re-rendered every `periodMs` while mounted (ages, countdowns). */
export function useNow(periodMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), periodMs);
    return () => clearInterval(timer);
  }, [periodMs]);
  return now;
}
