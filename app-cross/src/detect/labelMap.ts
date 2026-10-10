import type { ObjClass } from '../core/types';

/**
 * RT-DETR v2 (COCO) `labelId` -> our two classes, keyed on the numeric id, never the label string
 * (detect/LabelMap.kt): 0 person -> PERSON; 4 aeroplane, 14 bird, 33 kite -> DRONE; everything else dropped.
 * Drones are small and rare in COCO, so their floor is low (0.28) while person stays at 0.5.
 */
export const DEFAULT_PERSON_THRESHOLD = 0.5;
export const DEFAULT_DRONE_THRESHOLD = 0.28;

const PERSON_ID = 0;
const DRONE_IDS = new Set([4, 14, 33]);

export function classOf(labelId: number): ObjClass | null {
  if (labelId === PERSON_ID) return 'PERSON';
  if (DRONE_IDS.has(labelId)) return 'DRONE';
  return null;
}

export interface Thresholds {
  person: number;
  drone: number;
}

export const DEFAULT_THRESHOLDS: Thresholds = { person: DEFAULT_PERSON_THRESHOLD, drone: DEFAULT_DRONE_THRESHOLD };

/** The class to keep for one raw row, or null when the id is not ours or the score is below its floor. */
export function accept(labelId: number, score: number, thresholds: Thresholds = DEFAULT_THRESHOLDS): ObjClass | null {
  const cls = classOf(labelId);
  if (cls === null) return null;
  const floor = cls === 'PERSON' ? thresholds.person : thresholds.drone;
  return score >= floor ? cls : null;
}
