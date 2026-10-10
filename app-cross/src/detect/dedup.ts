import { iou } from '../core/box';
import type { Detection, RawDetection } from '../core/types';
import { accept, DEFAULT_THRESHOLDS, type Thresholds } from './labelMap';

/**
 * IoU de-duplication of one frame (detect/Dedup.kt, D-08): when two boxes overlap at IoU >= the threshold the
 * lower-scored same-class duplicate goes, and across classes the DRONE box wins, so a drone that is also called a
 * person stays challengeable. Input is score-descending; output is score-descending.
 */
export function dedup(detections: Detection[], iouThreshold = 0.5): Detection[] {
  let kept: Detection[] = [];
  for (const det of detections) {
    const overlapping = kept.filter((k) => iou(k.box, det.box) >= iouThreshold);
    if (overlapping.length === 0) {
      kept.push(det);
    } else if (det.cls === 'DRONE' && overlapping.every((k) => k.cls === 'PERSON')) {
      kept = kept.filter((k) => !overlapping.includes(k));
      kept.push(det);
    }
    // Same class (the kept one scored higher) or a person under a kept drone: drop the new one.
  }
  return kept.sort((a, b) => b.score - a.score);
}

/** The one mapping from raw rows to detections both the on-device and the laptop detector use (RawDetection.kt). */
export function toDetections(rows: RawDetection[], thresholds: Thresholds = DEFAULT_THRESHOLDS, iouThreshold = 0.5): Detection[] {
  const mapped: Detection[] = [];
  for (const r of rows) {
    const cls = accept(r.labelId, r.score, thresholds);
    if (cls !== null) mapped.push({ cls, score: r.score, box: r.box });
  }
  return dedup(mapped, iouThreshold);
}
