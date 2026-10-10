import type { BoxF } from './types';

export function box(left: number, top: number, right: number, bottom: number): BoxF {
  return { left, top, right, bottom };
}

export function area(b: BoxF): number {
  return Math.max(0, b.right - b.left) * Math.max(0, b.bottom - b.top);
}

export function contains(b: BoxF, x: number, y: number): boolean {
  return x >= b.left && x < b.right && y >= b.top && y < b.bottom;
}

/** Intersection over union; 0 when the boxes do not overlap or both are empty (detect/BoxF.kt). */
export function iou(a: BoxF, b: BoxF): number {
  const iw = Math.min(a.right, b.right) - Math.max(a.left, b.left);
  const ih = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
  if (iw <= 0 || ih <= 0) return 0;
  const inter = iw * ih;
  const union = area(a) + area(b) - inter;
  return union <= 0 ? 0 : inter / union;
}

export function sameBox(a: BoxF, b: BoxF): boolean {
  return a.left === b.left && a.top === b.top && a.right === b.right && a.bottom === b.bottom;
}
