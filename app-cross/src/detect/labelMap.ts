import type { ObjClass } from '../core/types';

/**
 * Detector label -> our classes, keyed on numeric ids (detect/LabelMap.kt), extended for this app:
 *
 * - RT-DETR v2 / COCO ids (ClikaRT on Android, the laptop serve, FofCoco on iPhone): 0 person -> PERSON,
 *   14 bird -> BIRD, 4 aeroplane and 33 kite -> DRONE (the COCO proxies for a small flying object).
 * - The iPhone's drone detector FofDrone: `drone` (id 2000) -> DRONE. On the Core ML path the COCO model gives only
 *   person and bird: its airplane/kite proxies found no drone FofDrone missed and added false ones.
 *
 * The native app maps 14 bird to DRONE; this app keeps birds apart so they are shown but never challenged.
 * Drones are small and rare in COCO, so the RT-DETR floor is low (0.28); person stays at 0.5; birds share the drone
 * floor. The iPhone build defaults the drone floor to 0.6 (app/settings.ts), where FofDrone keeps 12 of 13 real drone
 * photos and fires on 4 of 40 drone-free COCO images (13 of 40 at 0.3).
 */
export const DEFAULT_PERSON_THRESHOLD = 0.5;
export const DEFAULT_DRONE_THRESHOLD = 0.28;

const PERSON_IDS = new Set([0]);
const BIRD_IDS = new Set([14]);
const DRONE_IDS = new Set([4, 33, 2000]);

/** Core ML rows carry the model tag and label name; this gives them the numeric id the map is keyed on. */
const COREML_IDS: Record<string, Record<string, number>> = {
  coco: { person: 0, bird: 14 },
  drone: { drone: 2000 },
};

export function labelIdFor(model: string, label: string): number {
  return COREML_IDS[model]?.[label.normalize('NFC')] ?? -1;
}

export function classOf(labelId: number): ObjClass | null {
  if (PERSON_IDS.has(labelId)) return 'PERSON';
  if (BIRD_IDS.has(labelId)) return 'BIRD';
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
