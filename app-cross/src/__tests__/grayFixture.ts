import type { BoxF } from '../core/types';
import { GrayFrame } from '../track/grayFrame';

// Ported from app/src/test/.../track/MotionTrackerTest.kt: a 320x180 gray frame at step 4 (frame pixels = 4 x gray
// pixels) with a smooth gradient background and a textured 24x24 object.
export const STEP = 4;
export const GW = 320;
export const GH = 180;
export const SIDE = 24;

function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const texture: number[][] = (() => {
  const r = rng(7);
  return Array.from({ length: SIDE }, () => Array.from({ length: SIDE }, () => 40 + Math.floor(r() * 200)));
})();

/** The object's top-left at gray (x, y); null x draws no object. */
export function grayFrame(tsMs: number, x: number | null, y = 60, noise = 0, seed = 0): GrayFrame {
  const r = rng(seed + 1);
  const luma = new Uint8Array(GW * GH);
  for (let yy = 0; yy < GH; yy++) {
    for (let xx = 0; xx < GW; xx++) {
      let v = 90 + Math.floor((xx + yy) / 8);
      if (x !== null && xx >= x && xx < x + SIDE && yy >= y && yy < y + SIDE) v = texture[yy - y][xx - x];
      if (noise > 0) v += Math.floor(r() * (2 * noise + 1)) - noise;
      luma[yy * GW + xx] = Math.min(255, Math.max(0, v));
    }
  }
  return new GrayFrame(GW, GH, luma, STEP, GW * STEP, GH * STEP, tsMs);
}

/** The object's box in frame pixels for a gray top-left (x, y). */
export const objectBox = (x: number, y = 60): BoxF => ({ left: x * 4, top: y * 4, right: (x + SIDE) * 4, bottom: (y + SIDE) * 4 });

export function expectBox(expected: BoxF, actual: BoxF, tol = 4.01) {
  for (const k of ['left', 'top', 'right', 'bottom'] as const) expect(Math.abs(expected[k] - actual[k])).toBeLessThanOrEqual(tol);
}
