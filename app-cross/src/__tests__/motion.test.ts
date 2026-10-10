import { MotionTracker } from '../track/motionTracker';
import { expectBox, grayFrame, objectBox } from './grayFixture';

describe('MotionTracker', () => {
  it('follows a moving object at 5 to 20 frame pixels per frame', () => {
    const m = new MotionTracker();
    let x = 40;
    m.push(grayFrame(0, x));
    m.assign(1, m.fastForward(0, [objectBox(x)])[0]);
    [2, 3, 5, 5, 4, 2, 1, 5, 5, 5, 3].forEach((d, i) => {
      x += d;
      m.push(grayFrame(100 * (i + 1), x, 60, 3, i));
      const got = m.box(1)!;
      expect(got.lost).toBe(false);
      expectBox(objectBox(x), got.box);
    });
  });

  it('follows a fast object with a steady velocity of 20 gray pixels per frame', () => {
    const m = new MotionTracker();
    let x = 10;
    m.push(grayFrame(0, x));
    m.assign(1, m.fastForward(0, [objectBox(x)])[0]);
    [8, 14, 20, 20, 20, 20, 20, 20, 20, 20, 20].forEach((d, i) => {
      x += d;
      m.push(grayFrame(100 * (i + 1), x, 60, 0, i));
      expectBox(objectBox(x), m.box(1)!.box);
    });
  });

  it('keeps a still object exactly still', () => {
    const m = new MotionTracker();
    m.push(grayFrame(0, 100));
    m.assign(1, m.fastForward(0, [objectBox(100)])[0]);
    for (let i = 1; i <= 20; i++) m.push(grayFrame(100 * i, 100));
    const got = m.box(1)!;
    expect(got.box).toEqual(objectBox(100));
    expect(got.lost).toBe(false);
    expect(got.mad).toBeCloseTo(0);
  });

  it('stops on a poor match, keeps the last position, and resumes when the object is back', () => {
    const m = new MotionTracker();
    m.push(grayFrame(0, 100));
    m.assign(1, m.fastForward(0, [objectBox(100)])[0]);
    m.push(grayFrame(100, 104));
    expectBox(objectBox(104), m.box(1)!.box);
    m.push(grayFrame(200, null));
    const gone = m.box(1)!;
    expect(gone.lost).toBe(true);
    expect(gone.box).toEqual(objectBox(104));
    m.push(grayFrame(300, null));
    expect(m.box(1)!.box).toEqual(objectBox(104));
    m.push(grayFrame(400, 107));
    const back = m.box(1)!;
    expect(back.lost).toBe(false);
    expectBox(objectBox(107), back.box);
  });

  it('fast-forwards a late detection to where the object is now', () => {
    const m = new MotionTracker();
    for (let i = 0; i <= 30; i++) m.push(grayFrame(100 * i, 20 + 4 * i, 60, 2, i));
    const seed = m.fastForward(500, [objectBox(40)])[0];
    expect(seed.lost).toBe(false);
    expectBox(objectBox(20 + 4 * 30), seed.box);
  });

  it('fast-forwards on a snapshot, then catchUp covers frames pushed meanwhile', () => {
    const m = new MotionTracker();
    for (let i = 0; i <= 10; i++) m.push(grayFrame(100 * i, 20 + 3 * i));
    const snapshot = m.snapshot();
    m.push(grayFrame(1100, 53));
    m.push(grayFrame(1200, 56));
    const seeds = m.fastForward(0, [objectBox(20)], snapshot);
    expectBox(objectBox(50), seeds[0].box);
    m.catchUp(seeds);
    expectBox(objectBox(56), seeds[0].box);
  });

  it('picks the buffered frame nearest the capture time', () => {
    const m = new MotionTracker();
    for (let i = 0; i <= 10; i++) m.push(grayFrame(100 * i, 20 + 3 * i));
    expectBox(objectBox(50), m.fastForward(520, [objectBox(35)])[0].box);
  });

  it('keeps a seed box unchanged without buffered frames', () => {
    const m = new MotionTracker();
    const b = { left: 10, top: 20, right: 30, bottom: 40 };
    const seed = m.fastForward(123, [b])[0];
    expect(seed.box).toEqual(b);
    m.assign(3, seed);
    expect(m.box(3)!.box).toEqual(b);
  });

  it('buffers about five seconds and retain forgets ids', () => {
    const m = new MotionTracker();
    for (let i = 0; i <= 80; i++) m.push(grayFrame(100 * i, null));
    expect(m.bufferedFrames).toBe(51);
    m.assign(1, m.fastForward(8000, [objectBox(10)])[0]);
    m.assign(2, m.fastForward(8000, [objectBox(100)])[0]);
    m.retain(new Set([2]));
    expect([...m.ids]).toEqual([2]);
    expect(m.box(2)).not.toBeNull();
  });
});
