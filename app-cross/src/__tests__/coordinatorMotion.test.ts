import type { Detection } from '../core/types';
import { IffCoordinator } from '../session/iffCoordinator';
import { FakeSignalSource } from '../signal/fakeSignalSource';
import { expectBox, GH, GW, grayFrame, objectBox, STEP } from './grayFixture';

// The native app's IffCoordinatorMotionTest, on the same synthetic object: frame pixels = 4 x gray pixels, and the
// preview is the frame itself (no scaling), so drawn boxes can be compared with frame boxes.
function setup() {
  let now = 10_000;
  const c = new IffCoordinator({ detectorName: 'test', signalSource: new FakeSignalSource(), clock: () => now });
  c.setPreviewSize(GW * STEP, GH * STEP);
  return { c, advance: (ms: number) => (now += ms) };
}

const drone = (x: number): Detection => ({ cls: 'DRONE', score: 0.8, box: objectBox(x) });
const drawn = (c: IffCoordinator) => c.getState().tracks.find((t) => t.cls === 'DRONE')!.box;

describe('IffCoordinator motion', () => {
  it('moves a drawn ring with the 10 fps gray frames between detections', () => {
    const { c, advance } = setup();
    c.motionTick(grayFrame(0, 40));
    c.tick({ width: GW * STEP, height: GH * STEP, detections: [drone(40)], timestampMs: 0 });
    expectBox(objectBox(40), drawn(c));
    let x = 40;
    for (let i = 1; i <= 10; i++) {
      x += 3;
      advance(100);
      c.motionTick(grayFrame(100 * i, x, 60, 2, i));
    }
    expectBox(objectBox(x), drawn(c));
  });

  it('draws a detection that arrives seconds late where the object is now, on the same track', () => {
    const { c } = setup();
    for (let i = 0; i <= 30; i++) c.motionTick(grayFrame(100 * i, 20 + 4 * i, 60, 2, i));
    // Captured at 0.5 s (object at gray x 40), delivered after the 3 s frame.
    c.tick({ width: GW * STEP, height: GH * STEP, detections: [drone(40)], timestampMs: 500 });
    expectBox(objectBox(20 + 4 * 30), drawn(c));
    const id = c.getState().tracks[0].id;
    // The next detection, also late, matches the same track because the tracked box moved with the object.
    for (let i = 31; i <= 40; i++) c.motionTick(grayFrame(100 * i, 20 + 4 * i, 60, 2, i));
    c.tick({ width: GW * STEP, height: GH * STEP, detections: [drone(20 + 4 * 35)], timestampMs: 3500 });
    expect(c.getState().tracks.map((t) => t.id)).toEqual([id]);
    expectBox(objectBox(20 + 4 * 40), drawn(c));
  });

  it('ignores gray frames while nothing is tracked', () => {
    const { c } = setup();
    const before = c.getState();
    c.motionTick(grayFrame(0, 40));
    expect(c.getState()).toBe(before);
  });
});
