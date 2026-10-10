import { box } from '../core/box';
import type { Detection } from '../core/types';
import { Tracker } from '../track/tracker';

const drone = (b = box(100, 100, 200, 180)): Detection => ({ cls: 'DRONE', score: 0.6, box: b });
const person = (b = box(500, 100, 600, 400)): Detection => ({ cls: 'PERSON', score: 0.9, box: b });

describe('Tracker', () => {
  it('keeps the id of an overlapping box and starts new ids for new objects', () => {
    const t = new Tracker();
    const [first] = t.update([drone()], 0);
    const [again] = t.update([drone(box(105, 102, 205, 182))], 1000);
    expect(again.id).toBe(first.id);
    const seen = t.update([drone(box(105, 102, 205, 182)), drone(box(900, 100, 950, 140))], 2000);
    expect(seen.map((s) => s.id)).toEqual([first.id, first.id + 1]);
  });

  it('never matches across classes', () => {
    const t = new Tracker();
    const [d] = t.update([drone()], 0);
    const [p] = t.update([person(box(100, 100, 200, 180))], 500);
    expect(p.id).not.toBe(d.id);
  });

  it('keeps a lost drone for 5 s of wall clock, then drops it', () => {
    const t = new Tracker();
    t.update([drone()], 1_000);
    for (const now of [2_000, 4_000, 5_900]) {
      expect(t.update([], now)).toEqual([]);
      expect(t.tracks).toHaveLength(1);
    }
    t.update([], 6_100);
    expect(t.tracks).toHaveLength(0);
  });

  it('keeps a lost person for 1.5 s only', () => {
    const t = new Tracker();
    t.update([person()], 1_000);
    t.update([], 2_400);
    expect(t.tracks).toHaveLength(1);
    t.update([], 2_600);
    expect(t.tracks).toHaveLength(0);
  });

  it('re-acquires a coasting drone with the same id', () => {
    const t = new Tracker();
    const [first] = t.update([drone()], 1_000);
    t.update([], 3_000);
    const [back] = t.update([drone(box(102, 100, 202, 180))], 5_500);
    expect(back).toMatchObject({ id: first.id, misses: 0, lastSeenMillis: 5_500 });
  });

  it('reset forgets tracks but ids keep counting', () => {
    const t = new Tracker();
    const [a] = t.update([drone()], 0);
    t.reset();
    const [b] = t.update([drone()], 10);
    expect(b.id).toBe(a.id + 1);
  });
});
