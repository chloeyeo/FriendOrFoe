import type { Detection } from '../core/types';

/**
 * Scripted boxes for development and the on-stage fallback (detect/FakeDetector.kt): one drone drifting left and
 * right across the upper third (one sweep every `sweepTicks` calls) and one person standing on the right.
 * Deterministic: the output depends only on the frame size and the number of calls so far.
 */
export class FakeDetector {
  readonly name = 'fake';
  private ticks = 0;

  constructor(private readonly sweepTicks = 60) {}

  detect(width: number, height: number): Detection[] {
    const tick = this.ticks++;
    const phase = (tick % this.sweepTicks) / this.sweepTicks;
    const sweep = phase < 0.5 ? phase * 2 : (1 - phase) * 2;
    const cx = width * (0.15 + 0.45 * sweep);
    const cy = height * (0.28 + 0.04 * sweep);
    const dw = width * 0.12;
    const dh = height * 0.1;
    const drone: Detection = {
      cls: 'DRONE',
      score: 0.62,
      box: { left: cx - dw / 2, top: cy - dh / 2, right: cx + dw / 2, bottom: cy + dh / 2 },
    };
    const person: Detection = {
      cls: 'PERSON',
      score: 0.91,
      box: { left: width * 0.66, top: height * 0.3, right: width * 0.8, bottom: height * 0.98 },
    };
    return [person, drone]; // score-descending, as the contract says
  }
}
