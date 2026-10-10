import type { BoxF } from '../core/types';
import { GrayFrame } from './grayFrame';

/** The box of one track as of the latest gray frame, in analysis-frame pixels. */
export interface Motion {
  box: BoxF;
  mad: number;
  lost: boolean;
}

interface Patch {
  w: number;
  h: number;
  data: Uint8Array;
  contrast: number;
  half: Patch | null;
}

interface State {
  box: BoxF;
  gx: number;
  gy: number;
  patch: Patch | null;
  vx: number;
  vy: number;
  mad: number;
  lost: boolean;
  timestampMs: number;
}

/** A detection box carried to the latest buffered frame by [MotionTracker.fastForward]; hand it to `assign`. */
export class Seed {
  constructor(readonly state: State) {}
  get box(): BoxF {
    return this.state.box;
  }
  get lost(): boolean {
    return this.state.lost;
  }
}

export interface MotionOptions {
  bufferMs?: number;
  minRadius?: number;
  radiusFraction?: number;
  maxMad?: number;
  minContrast?: number;
  maxSamples?: number;
  innerFraction?: number;
}

const MIN_PATCH = 4;
/** Half-level patch pixels per side sampled in the coarse pass. */
const COARSE_SAMPLES = 12;
/** A match whose error exceeds this share of the patch's own texture is no better than a random place. */
const POOR_VS_CONTRAST = 0.9;

/**
 * The 10 fps box follower between detections, ported from track/MotionTracker.kt (same parameters, same search):
 *
 * - a ring buffer of the last `bufferMs` (5 s) of gray frames;
 * - per track, a luma template cut at detection time from the inner part of the box;
 * - on every gray frame a SAD search around the last position plus the last velocity: coarse on the half-resolution
 *   level, then every full-resolution position within 2 px of the coarse winner; ties keep the smaller move;
 * - a poor match (MAD above `maxMad`, above 0.9 x the template's texture, or a flat template) leaves the box where it
 *   was (`lost`), and later frames keep searching with the same template;
 * - `fastForward` matches a late detection's boxes on the buffered frame nearest its capture time and steps them
 *   through every newer frame, so they are drawn where the objects are now.
 *
 * Phone A needs seconds per detection, so without this a ring would sit where the drone was seconds ago.
 */
export class MotionTracker {
  private readonly bufferMs: number;
  private readonly minRadius: number;
  private readonly radiusFraction: number;
  private readonly maxMad: number;
  private readonly minContrast: number;
  private readonly maxSamples: number;
  private readonly innerFraction: number;
  private frames: GrayFrame[] = [];
  private states = new Map<number, State>();

  constructor(o: MotionOptions = {}) {
    this.bufferMs = o.bufferMs ?? 5000;
    this.minRadius = o.minRadius ?? 12;
    this.radiusFraction = o.radiusFraction ?? 0.5;
    this.maxMad = o.maxMad ?? 28;
    this.minContrast = o.minContrast ?? 4;
    this.maxSamples = o.maxSamples ?? 24;
    this.innerFraction = o.innerFraction ?? 0.1;
  }

  get latestTimestampMs(): number | null {
    return this.frames.length ? this.frames[this.frames.length - 1].timestampMs : null;
  }

  get bufferedFrames(): number {
    return this.frames.length;
  }

  get ids(): Set<number> {
    return new Set(this.states.keys());
  }

  /** Buffers `frame` and moves every tracked box to it; a frame not newer than the newest is ignored. */
  push(frame: GrayFrame): void {
    const last = this.frames[this.frames.length - 1];
    if (last) {
      if (frame.timestampMs <= last.timestampMs) return;
      if (frame.width !== last.width || frame.height !== last.height || frame.step !== last.step) {
        this.frames = [];
        for (const s of this.states.values()) s.patch = null;
      }
    }
    this.frames.push(frame);
    while (this.frames.length > 1 && frame.timestampMs - this.frames[0].timestampMs > this.bufferMs) this.frames.shift();
    for (const s of this.states.values()) this.advance(s, frame);
  }

  snapshot(): GrayFrame[] {
    return this.frames.slice();
  }

  /** One seed per box of a detection made on the frame captured at `t0Ms`; see the class comment. */
  fastForward(t0Ms: number, boxes: BoxF[], frames: GrayFrame[] = this.frames): Seed[] {
    if (!frames.length) return boxes.map((b) => new Seed(newState(b)));
    let base = 0;
    let bestGap = Infinity;
    frames.forEach((f, i) => {
      const gap = Math.abs(f.timestampMs - t0Ms);
      if (gap < bestGap) {
        bestGap = gap;
        base = i;
      }
    });
    const baseFrame = frames[base];
    const seeds = boxes.map((b) => {
      const s = newState(b, baseFrame.timestampMs);
      this.cut(s, baseFrame);
      return new Seed(s);
    });
    for (let k = base + 1; k < frames.length; k++) for (const seed of seeds) this.advance(seed.state, frames[k]);
    return seeds;
  }

  /** Steps seeds through the buffered frames newer than each (those pushed during a fastForward on a snapshot). */
  catchUp(seeds: Seed[]): void {
    for (const f of this.frames) for (const seed of seeds) this.advance(seed.state, f);
  }

  assign(id: number, seed: Seed): void {
    this.states.set(id, seed.state);
  }

  box(id: number): Motion | null {
    const s = this.states.get(id);
    return s ? { box: s.box, mad: s.mad, lost: s.lost } : null;
  }

  retain(keep: Set<number>): void {
    for (const id of [...this.states.keys()]) if (!keep.has(id)) this.states.delete(id);
  }

  /** Forgets every track; the frame buffer is kept. */
  reset(): void {
    this.states.clear();
  }

  private cut(s: State, frame: GrayFrame): void {
    const step = frame.step;
    const trimX = (s.box.right - s.box.left) * this.innerFraction;
    const trimY = (s.box.bottom - s.box.top) * this.innerFraction;
    const l = clamp(Math.floor((s.box.left + trimX) / step), 0, frame.width);
    const t = clamp(Math.floor((s.box.top + trimY) / step), 0, frame.height);
    const r = clamp(Math.ceil((s.box.right - trimX) / step), 0, frame.width);
    const b = clamp(Math.ceil((s.box.bottom - trimY) / step), 0, frame.height);
    const w = r - l;
    const h = b - t;
    if (w < MIN_PATCH || h < MIN_PATCH) {
      s.patch = null;
      return;
    }
    s.patch = template(frame, l, t, w, h);
    s.gx = l;
    s.gy = t;
  }

  private advance(s: State, frame: GrayFrame): void {
    if (frame.timestampMs <= s.timestampMs) return;
    s.timestampMs = frame.timestampMs;
    const patch = s.patch;
    if (!patch) return;
    if (patch.w > frame.width || patch.h > frame.height || patch.contrast < this.minContrast) {
      s.lost = true;
      s.mad = 0;
      return;
    }
    const fine = Math.max(1, Math.ceil(Math.max(patch.w, patch.h) / this.maxSamples));
    const boxGray = Math.max(s.box.right - s.box.left, s.box.bottom - s.box.top) / frame.step;
    const radius = Math.max(this.minRadius, Math.round(this.radiusFraction * boxGray));
    const halfPatch = patch.half;
    const halfFrame = frame.half;
    let found: [number, number, number];
    if (halfPatch && halfFrame) {
      const hr = (radius + 1) >> 1;
      const coarseStride = Math.max(1, Math.ceil(Math.max(halfPatch.w, halfPatch.h) / COARSE_SAMPLES));
      const c = search(halfFrame, halfPatch, s.gx >> 1, s.gy >> 1, trunc2(s.vx), trunc2(s.vy), hr, Math.max(1, (hr + 7) >> 3), coarseStride);
      found = search(frame, patch, s.gx, s.gy, 2 * c[0], 2 * c[1], 2, 1, fine);
    } else {
      found = search(frame, patch, s.gx, s.gy, s.vx, s.vy, radius, 1, fine);
    }
    const [bestDx, bestDy, sadValue] = found;
    const mad = sadValue / samplesOf(patch, fine);
    s.mad = mad;
    if (mad > this.maxMad || mad > patch.contrast * POOR_VS_CONTRAST) {
      s.lost = true;
      s.vx = 0;
      s.vy = 0;
      return;
    }
    s.lost = false;
    s.vx = bestDx;
    s.vy = bestDy;
    s.gx += bestDx;
    s.gy += bestDy;
    const step = frame.step;
    s.box = { left: s.box.left + bestDx * step, top: s.box.top + bestDy * step, right: s.box.right + bestDx * step, bottom: s.box.bottom + bestDy * step };
    const refreshed = template(frame, s.gx, s.gy, patch.w, patch.h);
    s.patch = refreshed.contrast >= this.minContrast ? refreshed : patch;
  }
}

function newState(box: BoxF, timestampMs = -Infinity): State {
  return { box, gx: 0, gy: 0, patch: null, vx: 0, vy: 0, mad: 0, lost: false, timestampMs };
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, v));
}

/** Kotlin's Int division truncates toward zero (`vx / 2`). */
function trunc2(v: number): number {
  return Math.trunc(v / 2);
}

function template(frame: GrayFrame, l: number, t: number, w: number, h: number): Patch {
  const half = frame.half;
  let hp: Patch | null = null;
  if (half && w >= 2 * MIN_PATCH && h >= 2 * MIN_PATCH) {
    const hl = l >> 1;
    const ht = t >> 1;
    const hw = Math.min(w >> 1, half.width - hl);
    const hh = Math.min(h >> 1, half.height - ht);
    if (hw >= MIN_PATCH && hh >= MIN_PATCH) hp = extract(half, hl, ht, hw, hh, null);
  }
  return extract(frame, l, t, w, h, hp);
}

function extract(frame: GrayFrame, l: number, t: number, w: number, h: number, half: Patch | null): Patch {
  const data = new Uint8Array(w * h);
  for (let y = 0; y < h; y++) data.set(frame.luma.subarray((t + y) * frame.width + l, (t + y) * frame.width + l + w), y * w);
  let sum = 0;
  for (let i = 0; i < data.length; i++) sum += data[i];
  const mean = sum / data.length;
  let dev = 0;
  for (let i = 0; i < data.length; i++) dev += Math.abs(data[i] - mean);
  return { w, h, data, contrast: dev / data.length, half };
}

function samplesOf(patch: Patch, stride: number): number {
  return Math.floor((patch.h + stride - 1) / stride) * Math.floor((patch.w + stride - 1) / stride);
}

/** Best displacement (dx, dy, SAD) of `patch` whose last top-left was (x0, y0); see MotionTracker.kt `search`. */
function search(frame: GrayFrame, patch: Patch, x0: number, y0: number, cx: number, cy: number, radius: number, grid0: number, stride: number): [number, number, number] {
  const maxX = frame.width - patch.w;
  const maxY = frame.height - patch.h;
  if (maxX < 0 || maxY < 0) return [0, 0, Number.MAX_SAFE_INTEGER];
  const clampX = (dx: number) => clamp(x0 + dx, 0, maxX) - x0;
  const clampY = (dy: number) => clamp(y0 + dy, 0, maxY) - y0;

  let bestDx = clampX(0);
  let bestDy = clampY(0);
  let best = sad(frame, patch, x0 + bestDx, y0 + bestDy, stride, Number.MAX_SAFE_INTEGER);
  const tryAt = (dx0: number, dy0: number) => {
    const dx = clampX(dx0);
    const dy = clampY(dy0);
    if (dx === bestDx && dy === bestDy) return;
    const v = sad(frame, patch, x0 + dx, y0 + dy, stride, best);
    if (v < best || (v === best && Math.abs(dx) + Math.abs(dy) < Math.abs(bestDx) + Math.abs(bestDy))) {
      best = v;
      bestDx = dx;
      bestDy = dy;
    }
  };
  tryAt(cx, cy);
  let grid = Math.max(1, grid0);
  if (radius > 0) {
    for (let dy = -radius; dy <= radius; dy += grid) for (let dx = -radius; dx <= radius; dx += grid) tryAt(cx + dx, cy + dy);
  }
  for (;;) {
    const ox = bestDx;
    const oy = bestDy;
    for (let ry = -1; ry <= 1; ry++) for (let rx = -1; rx <= 1; rx++) if (rx !== 0 || ry !== 0) tryAt(ox + rx * grid, oy + ry * grid);
    if (grid === 1) break;
    grid = Math.max(1, grid >> 1);
  }
  return [bestDx, bestDy, best];
}

/** Sum of absolute differences of `patch` at (x, y), every `stride`-th pixel; stops early past `limit`. */
function sad(frame: GrayFrame, patch: Patch, x: number, y: number, stride: number, limit: number): number {
  let sum = 0;
  const fw = frame.width;
  const luma = frame.luma;
  const data = patch.data;
  for (let py = 0; py < patch.h; py += stride) {
    const fo = (y + py) * fw + x;
    const po = py * patch.w;
    for (let px = 0; px < patch.w; px += stride) sum += Math.abs(luma[fo + px] - data[po + px]);
    if (sum > limit) return sum;
  }
  return sum;
}
