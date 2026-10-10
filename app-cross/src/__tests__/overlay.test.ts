import { box } from '../core/box';
import type { EventUi, IffState, TrackUi } from '../core/types';
import { eventBody, frameBody, planEvents } from '../net/eventJson';
import { compactLabel, countdown, droneAt, tagFor } from '../ui/overlayStyle';

const track = (iff: IffState, patch: Partial<TrackUi> = {}): TrackUi => ({
  id: 2,
  cls: 'DRONE',
  box: box(100, 100, 200, 180),
  iff,
  score: 0.42,
  ...patch,
});

const verdict = (v: 'FRIEND' | 'FOE' | 'UNKNOWN', reason: string | null, droneId: string | null, rssi: number | null, ms: number | null): IffState => ({
  kind: 'VERDICT',
  verdict: v,
  reason,
  droneId,
  rssiDbm: rssi,
  roundTripMs: ms,
});

describe('tagFor', () => {
  it('draws every state the way the native overlay does', () => {
    expect(tagFor(track({ kind: 'UNCHALLENGED' }), 0)).toMatchObject({ text: 'drone · ?', tone: 'amber' });
    expect(tagFor(track({ kind: 'UNCHALLENGED' }, { cls: 'PERSON' }), 0)).toMatchObject({ text: 'person', tone: 'neutral' });
    expect(tagFor(track({ kind: 'UNCHALLENGED' }, { cls: 'BIRD' }), 0)).toMatchObject({ text: 'bird', tone: 'neutral' });
    expect(tagFor(track({ kind: 'CHALLENGED', until: 7_200, windowMs: 10_000 }), 0)).toMatchObject({
      text: 'drone · challenge 8 s',
      countdown: { fraction: 0.72, seconds: 8 },
    });
    expect(tagFor(track(verdict('FRIEND', null, 'RQ-07', -58, 42)), 0)).toMatchObject({ text: 'FRIEND · RQ-07 · −58 dBm · 42 ms', tone: 'green' });
    expect(tagFor(track(verdict('FOE', 'late response', 'RQ-07', -60, 3450)), 0)).toMatchObject({
      text: 'FOE · late response',
      tone: 'red',
      detail: 'RQ-07 · −60 dBm · 3450 ms',
    });
    expect(tagFor(track(verdict('UNKNOWN', null, null, null, null)), 0)).toMatchObject({ text: 'UNKNOWN · no response', detail: null });
  });

  it('keeps identity and reason on the object, like the web view', () => {
    expect(compactLabel('FRIEND · RQ-07 · −58 dBm · 42 ms')).toBe('FRIEND · RQ-07');
    expect(compactLabel('RQ-07 · −60 dBm · 3450 ms')).toBe('RQ-07');
  });

  it('clamps the countdown', () => {
    expect(countdown(0, 5_000, 10_000)).toEqual({ fraction: 0, seconds: 0 });
    expect(countdown(20_000, 0, 10_000)).toEqual({ fraction: 1, seconds: 10 });
  });
});

describe('droneAt', () => {
  it('hits drones only, with slop, smallest box first', () => {
    const big = track({ kind: 'UNCHALLENGED' }, { id: 1, box: box(0, 0, 400, 400) });
    const small = track({ kind: 'UNCHALLENGED' }, { id: 2, box: box(100, 100, 120, 120) });
    const person = track({ kind: 'UNCHALLENGED' }, { id: 3, cls: 'PERSON', box: box(90, 90, 130, 130) });
    expect(droneAt([big, small, person], 110, 110)?.id).toBe(2);
    expect(droneAt([small], 125, 110)).toBeNull();
    expect(droneAt([small], 125, 110, 8)?.id).toBe(2);
    const bird = track({ kind: 'UNCHALLENGED' }, { id: 4, cls: 'BIRD', box: box(100, 100, 120, 120) });
    expect(droneAt([bird], 110, 110)).toBeNull(); // a bird is never challenged
  });
});

describe('dashboard bodies', () => {
  it('posts the app.py event schema', () => {
    const e: EventUi = { t: 5, text: 'FOE · bad tag · RQ-07', trackId: 2, cls: 'DRONE', score: 0.42, box: box(1.25, 2, 3, 4), verdict: 'FOE', reason: 'bad tag', droneId: 'RQ-07', rssiDbm: -59, roundTripMs: 45 };
    expect(eventBody(e)).toEqual({
      t: 5, class: 'drone', box: [1.3, 2, 3, 4], iffStatus: 'FOE', droneId: 'RQ-07', reason: 'bad tag',
      text: 'FOE · bad tag · RQ-07', trackId: 2, score: 0.42, rssiDbm: -59, roundTripMs: 45,
    });
    expect(eventBody({ t: 1, text: 'challenge sent · box 2' })).toMatchObject({ class: null, box: null, iffStatus: null, trackId: null });
  });

  it('posts the drawn tracks with the phone tag', () => {
    const body = frameBody(0, [track(verdict('FRIEND', null, 'RQ-07', -58, 42))], 1920, 1080);
    expect(body.tracks[0]).toEqual({ id: 2, class: 'drone', box: [100, 100, 200, 180], tag: 'FRIEND · RQ-07 · −58 dBm · 42 ms', tone: 'green', detail: null });
  });

  it('sends only the new rows, or resets when the last sent row is gone', () => {
    const a: EventUi = { t: 1, text: 'a' };
    const b: EventUi = { t: 2, text: 'b' };
    const c: EventUi = { t: 3, text: 'c' };
    expect(planEvents([b, a], null)).toEqual({ reset: false, send: [a, b] });
    expect(planEvents([c, b, a], b)).toEqual({ reset: false, send: [c] });
    expect(planEvents([c], b)).toEqual({ reset: true, send: [c] });
  });
});
