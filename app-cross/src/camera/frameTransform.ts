import type { BoxF } from '../core/types';

/**
 * Analysis-frame pixels -> preview-view pixels (camera/FrameTransform.kt). `fill` mirrors CameraX's FILL_CENTER
 * (scale until the frame covers the view, crop the overflow equally); `fit` letterboxes the whole frame inside the
 * view, which is how the simulated backdrop and the web build show it.
 */
export interface FrameTransform {
  scale: number;
  offsetX: number;
  offsetY: number;
}

export function frameTransform(
  frameWidth: number,
  frameHeight: number,
  viewWidth: number,
  viewHeight: number,
  mode: 'fill' | 'fit' = 'fill',
): FrameTransform {
  if (frameWidth <= 0 || frameHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
    return { scale: 1, offsetX: 0, offsetY: 0 };
  }
  const sx = viewWidth / frameWidth;
  const sy = viewHeight / frameHeight;
  const scale = mode === 'fill' ? Math.max(sx, sy) : Math.min(sx, sy);
  return { scale, offsetX: (viewWidth - frameWidth * scale) / 2, offsetY: (viewHeight - frameHeight * scale) / 2 };
}

export function toPreview(t: FrameTransform, b: BoxF): BoxF {
  return {
    left: b.left * t.scale + t.offsetX,
    top: b.top * t.scale + t.offsetY,
    right: b.right * t.scale + t.offsetX,
    bottom: b.bottom * t.scale + t.offsetY,
  };
}

export function toFrame(t: FrameTransform, x: number, y: number): [number, number] {
  return [(x - t.offsetX) / t.scale, (y - t.offsetY) / t.scale];
}
