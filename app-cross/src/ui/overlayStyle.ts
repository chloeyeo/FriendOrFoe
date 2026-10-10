import { area } from '../core/box';
import { isIdentifiable, type IffState, type TrackUi } from '../core/types';

/** Ring and tag colour family (ui/overlay/OverlayStyle.kt); the web dashboard uses the same names. */
export type Tone = 'amber' | 'green' | 'red' | 'neutral';

export interface Countdown {
  /** 1 just challenged -> 0 window closed. */
  fraction: number;
  seconds: number;
}

/** What the overlay draws for one track, decided without drawing code so it is unit-tested. */
export interface TrackTag {
  text: string;
  tone: Tone;
  detail: string | null;
  countdown: Countdown | null;
}

export const TAG_SEP = ' · ';

/** `-58` -> `−58 dBm` (typographic minus reads better at a distance). */
export function dbm(rssi: number): string {
  return `${String(rssi).replace('-', '−')} dBm`;
}

export function millis(ms: number): string {
  return `${ms} ms`;
}

export function countdown(untilMs: number, nowMs: number, windowMs: number): Countdown {
  const window = Math.max(1, windowMs);
  const remaining = Math.min(Math.max(untilMs - nowMs, 0), window);
  return { fraction: remaining / window, seconds: Math.ceil(remaining / 1000) };
}

function evidence(v: Extract<IffState, { kind: 'VERDICT' }>): string | null {
  const parts = [v.droneId, v.rssiDbm !== null ? dbm(v.rssiDbm) : null, v.roundTripMs !== null ? millis(v.roundTripMs) : null];
  const kept = parts.filter((p): p is string => !!p);
  return kept.length ? kept.join(TAG_SEP) : null;
}

export function tagFor(track: TrackUi, nowMs: number): TrackTag {
  if (track.cls === 'BIRD') return { text: 'bird', tone: 'neutral', detail: null, countdown: null };
  const iff = track.iff;
  const noun = track.cls === 'PERSON' ? 'person' : 'drone';
  switch (iff.kind) {
    case 'UNCHALLENGED':
      // An unasked drone is the threat on screen (amber); an unasked person stays calm until someone taps it.
      return track.cls === 'PERSON'
        ? { text: 'person', tone: 'neutral', detail: null, countdown: null }
        : { text: `drone${TAG_SEP}?`, tone: 'amber', detail: null, countdown: null };
    case 'CHALLENGED': {
      const c = countdown(iff.until, nowMs, iff.windowMs);
      return { text: `${noun}${TAG_SEP}challenge ${c.seconds} s`, tone: 'amber', detail: null, countdown: c };
    }
    case 'VERDICT':
      if (iff.verdict === 'FRIEND') {
        const parts = ['FRIEND', iff.droneId, iff.rssiDbm !== null ? dbm(iff.rssiDbm) : null, iff.roundTripMs !== null ? millis(iff.roundTripMs) : null];
        return { text: parts.filter((p): p is string => !!p).join(TAG_SEP), tone: 'green', detail: null, countdown: null };
      }
      if (iff.verdict === 'FOE') {
        return { text: ['FOE', iff.reason].filter((p): p is string => !!p).join(TAG_SEP), tone: 'red', detail: evidence(iff), countdown: null };
      }
      return { text: `UNKNOWN${TAG_SEP}${iff.reason ?? 'no response'}`, tone: 'red', detail: evidence(iff), countdown: null };
  }
}

/**
 * The web dashboard's rule for what stays on the object: verdict, reason and identity; the measurements (dBm, ms)
 * live in the result details (web/src/lib/components/FrameView.svelte `compactLabel`).
 */
export function compactLabel(text: string): string {
  return text
    .split(TAG_SEP)
    .filter((part) => !/\b(?:dBm|ms)$/.test(part.trim()))
    .join(TAG_SEP);
}

/**
 * The drone or person track under a tap at (x, y) in preview pixels, or null (ui/overlay/HitTest.kt): boxes grow by
 * `slop` so a small, far drone is still easy to hit; when boxes overlap the smallest wins (a drone in front of a
 * person is the drone).
 */
export function targetAt(tracks: TrackUi[], x: number, y: number, slop = 0): TrackUi | null {
  let best: TrackUi | null = null;
  for (const t of tracks) {
    if (!isIdentifiable(t.cls)) continue;
    const b = t.box;
    if (x < b.left - slop || x > b.right + slop || y < b.top - slop || y > b.bottom + slop) continue;
    if (!best || area(b) < area(best.box)) best = t;
  }
  return best;
}
