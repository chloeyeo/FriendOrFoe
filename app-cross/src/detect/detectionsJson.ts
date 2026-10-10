import type { RawDetection } from '../core/types';

/**
 * Parser for the `clikart-cli serve` `POST /v1/detections` response (detect/DetectionsJson.kt):
 * `{"detections":[{"box":{"xmin","ymin","xmax","ymax"},"score","label_id","label"}, ...]}`.
 * Untrusted LAN input: a body that is not JSON or has no `detections` array throws; a malformed row, a non-finite
 * number, an inverted box or a score outside 0..1 is skipped; row order is kept (the server answers
 * score-descending). Only the first 120 characters of a bad body reach the error message.
 */
export function parseDetections(body: string): RawDetection[] {
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    throw new Error(`detections body is not JSON: ${body.slice(0, 120)}`);
  }
  const rows = (root as { detections?: unknown } | null)?.detections;
  if (!Array.isArray(rows)) throw new Error('detections body has no detections array');
  const out: RawDetection[] = [];
  for (const row of rows) {
    const parsed = parseRow(row);
    if (parsed) out.push(parsed);
  }
  return out;
}

function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

function parseRow(row: unknown): RawDetection | null {
  if (row === null || typeof row !== 'object') return null;
  const r = row as Record<string, unknown>;
  const b = r.box as Record<string, unknown> | undefined;
  if (!b || typeof b !== 'object') return null;
  const xmin = num(b.xmin);
  const ymin = num(b.ymin);
  const xmax = num(b.xmax);
  const ymax = num(b.ymax);
  const score = num(r.score);
  const labelId = num(r.label_id);
  if (xmin === null || ymin === null || xmax === null || ymax === null || score === null || labelId === null) return null;
  if (!Number.isInteger(labelId)) return null;
  if (xmax < xmin || ymax < ymin) return null;
  if (score < 0 || score > 1) return null;
  return {
    labelId,
    score,
    box: { left: xmin, top: ymin, right: xmax, bottom: ymax },
    label: typeof r.label === 'string' ? r.label : '',
  };
}
