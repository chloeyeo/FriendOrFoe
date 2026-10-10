import { iou } from '../core/box';
import type { BoxF, Detection, ObjClass } from '../core/types';

export interface Track {
  id: number;
  cls: ObjClass;
  box: BoxF;
  score: number;
  /** 0 when seen in the latest update. */
  misses: number;
  lastSeenMillis: number;
}

export interface TrackerOptions {
  iouThreshold?: number;
  /** A drone box survives this long unseen (D-09: 5 s, so a challenged drone keeps its ring through a flicker). */
  droneStickyMs?: number;
  personStickyMs?: number;
}

/**
 * Greedy IoU matching with stable ids (track/Tracker.kt plus plan 02-03's wall-clock stickiness): same-class
 * pairs at IoU >= threshold are matched best-first; a matched track keeps its id and takes the new box; an
 * unmatched detection starts a new track; an unmatched track coasts on its last box until it has been unseen for
 * longer than its class's sticky window. Stickiness is wall clock, so it is the same at 0.5 fps and at 2 fps.
 */
export class Tracker {
  private nextId = 1;
  private live: Track[] = [];
  private readonly iouThreshold: number;
  private readonly droneStickyMs: number;
  private readonly personStickyMs: number;

  constructor(options: TrackerOptions = {}) {
    this.iouThreshold = options.iouThreshold ?? 0.3;
    this.droneStickyMs = options.droneStickyMs ?? 5_000;
    this.personStickyMs = options.personStickyMs ?? 1_500;
  }

  /** Every live track, oldest first, including the ones coasting through a miss. */
  get tracks(): Track[] {
    return this.live.slice();
  }

  /** Feeds one frame's detections; returns the tracks seen in this frame (matched or new), oldest first. */
  update(detections: Detection[], nowMillis: number): Track[] {
    const candidates: [number, number, number][] = []; // iou, track index, detection index
    this.live.forEach((track, ti) => {
      detections.forEach((det, di) => {
        if (det.cls !== track.cls) return;
        const v = iou(track.box, det.box);
        if (v >= this.iouThreshold) candidates.push([v, ti, di]);
      });
    });
    candidates.sort((a, b) => b[0] - a[0]);

    const trackTaken = new Array<boolean>(this.live.length).fill(false);
    const detectionTaken = new Array<boolean>(detections.length).fill(false);
    const updated = new Array<Track | null>(this.live.length).fill(null);
    for (const [, ti, di] of candidates) {
      if (trackTaken[ti] || detectionTaken[di]) continue;
      trackTaken[ti] = true;
      detectionTaken[di] = true;
      const det = detections[di];
      updated[ti] = { ...this.live[ti], box: det.box, score: det.score, misses: 0, lastSeenMillis: nowMillis };
    }

    const next: Track[] = [];
    this.live.forEach((track, ti) => {
      const u = updated[ti];
      if (u) next.push(u);
      else if (nowMillis - track.lastSeenMillis <= this.stickyMs(track.cls)) next.push({ ...track, misses: track.misses + 1 });
    });
    detections.forEach((det, di) => {
      if (detectionTaken[di]) return;
      next.push({ id: this.nextId++, cls: det.cls, box: det.box, score: det.score, misses: 0, lastSeenMillis: nowMillis });
    });
    this.live = next;
    return this.live.filter((t) => t.misses === 0);
  }

  /** Forgets every track; ids keep counting up so an old ring is never confused with a new object. */
  reset(): void {
    this.live = [];
  }

  private stickyMs(cls: ObjClass): number {
    return cls === 'DRONE' ? this.droneStickyMs : this.personStickyMs;
  }
}
