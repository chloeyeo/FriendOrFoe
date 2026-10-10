import { Platform } from 'react-native';

import type { VerdictKind } from '../core/types';
import type { Tone } from './overlayStyle';

/** The web dashboard's neutral observer palette (web/src/app.css), so the phone and the judges' screen match. */
export const colors = {
  amber: '#edb85c',
  green: '#84d891',
  red: '#ff7168',
  neutral: '#d9dcdf',
  ink: '#101113',
  surface0: '#101113',
  surface1: '#181a1d',
  surface2: '#23262a',
  line: '#3b4046',
  text: '#eeefed',
  muted: '#a6abb1',
  accent: '#d2d6db',
  viewport: '#111316',
  grid: 'rgba(48, 54, 60, 0.27)',
  scrim: 'rgba(16, 17, 19, 0.92)',
  noticeBg: '#2a2112',
} as const;

export const mono = Platform.select({
  android: 'monospace',
  ios: 'Menlo',
  default: "ui-monospace, 'SF Mono', Menlo, Consolas, monospace",
});

export function toneColor(tone: Tone): string {
  return colors[tone];
}

/** Silence is never friendly: UNKNOWN is red like FOE (web/src/lib/format.ts). */
export function verdictColor(v: VerdictKind): string {
  return v === 'FRIEND' ? colors.green : colors.red;
}
