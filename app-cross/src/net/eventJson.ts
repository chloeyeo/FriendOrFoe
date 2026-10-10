import type { BoxF, EventUi, TrackUi } from '../core/types';
import { tagFor } from '../ui/overlayStyle';

/**
 * Bodies for the laptop dashboard (laptop/dashboard/app.py), the same shapes net/EventJson.kt posts:
 * event {t, class, box, iffStatus, droneId, reason, text, trackId, score, rssiDbm, roundTripMs};
 * frame {t, width, height, tracks:[{id, class, box, tag, tone, detail}]} with boxes in preview pixels.
 */
function boxArray(b: BoxF): number[] {
  return [b.left, b.top, b.right, b.bottom].map((v) => Math.round(v * 10) / 10);
}

export function eventBody(e: EventUi) {
  return {
    t: e.t,
    class: e.cls ? e.cls.toLowerCase() : null,
    box: e.box ? boxArray(e.box) : null,
    iffStatus: e.verdict ?? null,
    droneId: e.droneId ?? null,
    reason: e.reason ?? null,
    text: e.text,
    trackId: e.trackId ?? null,
    score: e.score ?? null,
    rssiDbm: e.rssiDbm ?? null,
    roundTripMs: e.roundTripMs ?? null,
  };
}

export function frameBody(t: number, tracks: TrackUi[], width: number, height: number) {
  return {
    t,
    width: Math.round(width),
    height: Math.round(height),
    tracks: tracks.map((tr) => {
      const tag = tagFor(tr, t);
      return { id: tr.id, class: tr.cls.toLowerCase(), box: boxArray(tr.box), tag: tag.text, tone: tag.tone, detail: tag.detail };
    }),
  };
}

/**
 * Which log rows still have to go to the dashboard (net/EventDiff.kt). The log is newest first and the phone
 * remembers the newest row it sent; everything above it is new. If that row is gone (Reset) the dashboard is reset
 * first and gets the whole log again, so its view always matches the phone's. `send` is oldest first.
 */
export function planEvents(events: EventUi[], lastSent: EventUi | null): { reset: boolean; send: EventUi[] } {
  if (lastSent === null) return { reset: false, send: events.slice().reverse() };
  const i = events.indexOf(lastSent);
  if (i < 0) return { reset: true, send: events.slice().reverse() };
  return { reset: false, send: events.slice(0, i).reverse() };
}
