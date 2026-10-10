import type { Verdict } from './types.js';

/** `now`, `12 s ago`, `3 min ago`, `2 h ago` — same wording as the phone's event log. */
export function ageText(nowMs: number, tMs: number): string {
	const s = Math.max(0, Math.floor((nowMs - tMs) / 1000));
	if (s < 1) return 'now';
	if (s < 60) return `${s} s ago`;
	if (s < 3600) return `${Math.floor(s / 60)} min ago`;
	return `${Math.floor(s / 3600)} h ago`;
}

/** `-58` → `−58 dBm` (typographic minus, as on the phone). */
export function dbm(rssi: number): string {
	return `${String(rssi).replace('-', '−')} dBm`;
}

export function clock(tMs: number): string {
	return new Date(tMs).toLocaleTimeString('en-GB', { hour12: false });
}

/** Silence is never friendly: UNKNOWN is red like FOE. */
export function verdictTone(v: Verdict): 'green' | 'red' {
	return v === 'FRIEND' ? 'green' : 'red';
}
