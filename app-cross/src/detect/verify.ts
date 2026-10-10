import { iou } from '../core/box';
import type { RawDetection } from '../core/types';

/**
 * The iPhone's drone confirmation: a FofDrone row (`drone`) counts only when FofDrone2 (`drone2`) has a box of its
 * own on the same object (IoU >= `minIou`); FofDrone2's rows are evidence only and never drawn themselves. On 20 real
 * drone photos and 168 drone-free COCO images this kept 14/20 drones (as FofDrone alone) and cut the false images
 * from 3 to 2 (scripts/export_coreml.py). Rows from other models pass through.
 */
export function confirmDrones(rows: RawDetection[], minIou = 0.3, primary = 'drone', verifier = 'drone2'): RawDetection[] {
  const evidence = rows.filter((r) => r.model === verifier);
  return rows.filter((r) => {
    if (r.model === verifier) return false;
    if (r.model !== primary) return true;
    return evidence.some((v) => iou(v.box, r.box) >= minIou);
  });
}
