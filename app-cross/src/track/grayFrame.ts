/**
 * A small grayscale copy of one analysis frame for the 10 fps motion tracker (track/GrayFrame.kt). One luma byte
 * per pixel, row by row; gray pixel (x, y) covers the upright analysis-frame pixels starting at (x * step, y * step),
 * so a frame-pixel box maps to gray pixels by dividing by `step`. `timestampMs` is the camera clock the detection
 * frames carry too (the native side converts its sensor timestamp to milliseconds).
 */
export class GrayFrame {
  private halfLevel: GrayFrame | null | undefined;

  constructor(
    readonly width: number,
    readonly height: number,
    readonly luma: Uint8Array,
    readonly step: number,
    readonly frameWidth: number,
    readonly frameHeight: number,
    readonly timestampMs: number,
  ) {
    if (!(width > 0 && height > 0 && step > 0)) throw new Error(`gray frame ${width}x${height} step ${step}`);
    if (luma.length !== width * height) throw new Error(`luma must be ${width * height} bytes, got ${luma.length}`);
  }

  /** The next pyramid level: half the size, each pixel the mean of a 2x2 block, step doubled; null under 16 px. */
  get half(): GrayFrame | null {
    if (this.halfLevel !== undefined) return this.halfLevel;
    if (this.width < 16 || this.height < 16) return (this.halfLevel = null);
    const hw = this.width >> 1;
    const hh = this.height >> 1;
    const out = new Uint8Array(hw * hh);
    const w = this.width;
    const l = this.luma;
    for (let y = 0; y < hh; y++) {
      const r0 = 2 * y * w;
      const r1 = r0 + w;
      for (let x = 0; x < hw; x++) {
        const i = 2 * x;
        out[y * hw + x] = (l[r0 + i] + l[r0 + i + 1] + l[r1 + i] + l[r1 + i + 1]) >> 2;
      }
    }
    return (this.halfLevel = new GrayFrame(hw, hh, out, this.step * 2, this.frameWidth, this.frameHeight, this.timestampMs));
  }

  at(x: number, y: number): number {
    return this.luma[y * this.width + x];
  }
}

/** Decodes the native camera pane's gray event (base64 luma) into a [GrayFrame]. */
export function grayFromEvent(e: { width: number; height: number; step: number; frameWidth: number; frameHeight: number; t: number; luma: string }): GrayFrame {
  const bin = globalThis.atob(e.luma);
  const luma = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) luma[i] = bin.charCodeAt(i);
  return new GrayFrame(e.width, e.height, luma, e.step, e.frameWidth, e.frameHeight, e.t);
}
