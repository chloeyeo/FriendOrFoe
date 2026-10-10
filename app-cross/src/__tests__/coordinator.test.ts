import { box } from '../core/box';
import type { Detection, FrameResult } from '../core/types';
import { IffCoordinator } from '../session/iffCoordinator';
import { FakeSignalSource, type FakeMode } from '../signal/fakeSignalSource';

const DRONE: Detection = { cls: 'DRONE', score: 0.6, box: box(10, 10, 50, 40) };
const PERSON: Detection = { cls: 'PERSON', score: 0.9, box: box(80, 10, 110, 70) };
const frame = (detections: Detection[]): FrameResult => ({ width: 128, height: 72, detections, msPerFrame: 300 });

function setup(mode: FakeMode = 'HONEST') {
  let now = 1_000;
  const source = new FakeSignalSource(mode);
  const c = new IffCoordinator({
    detectorName: 'fake',
    signalSource: source,
    clock: () => now,
    monotonic: () => now,
    acceptedDroneIds: ['RQ-07'],
  });
  c.setPreviewSize(256, 144); // scale 2
  const advance = async (ms: number) => {
    now += ms;
    await jest.advanceTimersByTimeAsync(ms);
  };
  return { c, source, advance, at: () => now };
}

const droneId = (c: IffCoordinator) => c.getState().tracks.find((t) => t.cls === 'DRONE')!.id;
const iffOf = (c: IffCoordinator, id: number) => c.getState().tracks.find((t) => t.id === id)?.iff;

beforeEach(() => jest.useFakeTimers());
afterEach(() => jest.useRealTimers());

describe('IffCoordinator', () => {
  it('maps tracks into preview pixels and logs each new object once', () => {
    const { c } = setup();
    c.tick(frame([PERSON, DRONE]));
    c.tick(frame([PERSON, DRONE]));
    const s = c.getState();
    expect(s.tracks.find((t) => t.cls === 'DRONE')!.box).toEqual(box(20, 20, 100, 80));
    expect(s.events.filter((e) => e.cls).map((e) => e.text)).toEqual(['drone detected · box 2', 'person detected · box 1']);
    expect(s.status).toMatchObject({ msPerFrame: 300, modelReady: true });
  });

  it('locks a tapped drone, shows the countdown at once, and turns green on an honest answer', async () => {
    const { c, advance } = setup('HONEST');
    c.tick(frame([DRONE]));
    const id = droneId(c);
    expect(c.challenge(id)).toBe(true);
    expect(c.lock).toMatchObject({ boxId: id, until: 11_000 });
    expect(iffOf(c, id)).toEqual({ kind: 'CHALLENGED', until: 11_000, windowMs: 10_000 });
    await advance(300);
    expect(iffOf(c, id)).toMatchObject({ kind: 'VERDICT', verdict: 'FRIEND', droneId: 'RQ-07', rssiDbm: -58, roundTripMs: 42 });
    expect(c.lock).toBeNull();
    expect(c.getState().events[0]).toMatchObject({ text: 'FRIEND · RQ-07 · -58 dBm · 42 ms', verdict: 'FRIEND', trackId: id });
    expect(c.getState().signal[0]).toMatchObject({ verdict: 'FRIEND', droneId: 'RQ-07' });
  });

  it('identifies a person carrying a responder', async () => {
    const { c, advance } = setup('HONEST');
    c.tick(frame([PERSON]));
    const id = c.getState().tracks.find((t) => t.cls === 'PERSON')!.id;
    expect(c.challenge(id)).toBe(true);
    expect(c.lock).toMatchObject({ boxId: id, cls: 'PERSON' });
    await advance(300);
    expect(iffOf(c, id)).toMatchObject({ kind: 'VERDICT', verdict: 'FRIEND', droneId: 'RQ-07' });
    expect(c.getState().events[0]).toMatchObject({ verdict: 'FRIEND', cls: 'PERSON', trackId: id });
  });

  it('keeps a locked person on screen as a person after its track expires', async () => {
    const { c, advance } = setup('SILENT');
    c.tick(frame([PERSON]));
    const id = c.getState().tracks.find((t) => t.cls === 'PERSON')!.id;
    c.challenge(id);
    await advance(2_000); // past the person's 1.5 s sticky window, inside the challenge window
    c.tick(frame([]));
    expect(c.getState().tracks.find((t) => t.id === id)).toMatchObject({ cls: 'PERSON', iff: { kind: 'CHALLENGED' } });
  });

  it('turns a person with an id outside the accepted list into FOE unknown person id', async () => {
    const { c, advance } = setup('HONEST');
    c.acceptedDroneIds = ['RQ-01'];
    c.tick(frame([PERSON]));
    const id = c.getState().tracks.find((t) => t.cls === 'PERSON')!.id;
    c.challenge(id);
    await advance(300);
    expect(iffOf(c, id)).toMatchObject({ kind: 'VERDICT', verdict: 'FOE', reason: 'unknown person id' });
  });

  it('refuses a second tap during the window, taps on birds and unknown ids', () => {
    const { c } = setup('SILENT');
    c.tick(frame([PERSON, DRONE, { ...DRONE, box: box(60, 40, 90, 60) }]));
    const drones = c.getState().tracks.filter((t) => t.cls === 'DRONE').map((t) => t.id);
    const personId = c.getState().tracks.find((t) => t.cls === 'PERSON')!.id;
    expect(c.challenge(999)).toBe(false);
    expect(c.challenge(drones[0])).toBe(true);
    expect(c.challenge(drones[1])).toBe(false);
    expect(c.challenge(personId)).toBe(false);
    expect(iffOf(c, drones[1])).toEqual({ kind: 'UNCHALLENGED' });
  });

  it('keeps the locked box on screen at its frozen rect after the detector loses it', async () => {
    const { c, advance } = setup('SILENT');
    c.tick(frame([DRONE]));
    const id = droneId(c);
    c.challenge(id);
    await advance(6_000); // past the tracker's 5 s sticky window
    c.tick(frame([]));
    const pinned = c.getState().tracks.find((t) => t.id === id)!;
    expect(pinned).toMatchObject({ cls: 'DRONE', box: box(20, 20, 100, 80), iff: { kind: 'CHALLENGED' } });
  });

  it('ends a silent window in UNKNOWN no response, never FRIEND', async () => {
    const { c, advance } = setup('SILENT');
    c.tick(frame([DRONE]));
    const id = droneId(c);
    c.challenge(id);
    await advance(10_000);
    expect(iffOf(c, id)).toMatchObject({ kind: 'VERDICT', verdict: 'UNKNOWN', reason: 'no response' });
    expect(c.getState().events[0].text).toBe(`UNKNOWN · no response · box ${id}`);
    expect(c.lock).toBeNull();
  });

  it('catches a replayed nonce on the phone and a late answer from the gadget', async () => {
    const { c, source, advance } = setup('HONEST');
    c.tick(frame([DRONE]));
    const id = droneId(c);
    c.challenge(id);
    await advance(300);
    source.mode = 'LATE';
    c.challenge(id);
    await advance(3_500);
    expect(iffOf(c, id)).toMatchObject({ verdict: 'FOE', reason: 'late response', roundTripMs: 3450 });
    source.mode = 'REPLAY';
    c.challenge(id);
    await advance(300);
    expect(iffOf(c, id)).toMatchObject({ verdict: 'FOE', reason: 'replayed nonce' });
  });

  it('rejects a FRIEND whose drone id is not on the accepted list', async () => {
    const { c, advance } = setup('HONEST');
    c.acceptedDroneIds = ['RQ-01'];
    c.tick(frame([DRONE]));
    const id = droneId(c);
    c.challenge(id);
    await advance(300);
    expect(iffOf(c, id)).toMatchObject({ verdict: 'FOE', reason: 'unknown drone id' });
  });

  it('holds an unconfirmed drone back until it is seen in confirmHits frames', () => {
    const { c } = setup();
    c.confirmHits = 2;
    c.tick(frame([PERSON, DRONE]));
    expect(c.getState().tracks.map((t) => t.cls)).toEqual(['PERSON']);
    expect(c.challenge(2)).toBe(false); // not on screen, not challengeable
    c.tick(frame([PERSON, DRONE]));
    expect(c.getState().tracks.map((t) => t.cls)).toEqual(['PERSON', 'DRONE']);
    expect(c.getState().events.filter((e) => e.cls === 'DRONE')).toHaveLength(1);
  });

  it('reset clears everything and drops an answer that arrives afterwards', async () => {
    const { c, advance } = setup('HONEST');
    c.tick(frame([DRONE]));
    c.challenge(droneId(c));
    c.reset();
    await advance(300);
    expect(c.getState()).toMatchObject({ tracks: [], signal: [], events: [] });
    expect(c.lock).toBeNull();
    expect(c.reChecks.seenCount).toBe(0);
  });
});
