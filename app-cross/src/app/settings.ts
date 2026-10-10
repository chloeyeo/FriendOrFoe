import AsyncStorage from '@react-native-async-storage/async-storage';
import { Platform } from 'react-native';

import { FofUsb, hasNativeCamera, onDeviceDetector } from '../../modules/fof-native';
import { DEFAULT_DRONE_THRESHOLD, DEFAULT_PERSON_THRESHOLD } from '../detect/labelMap';
import { FAKE_MODES, type FakeMode } from '../signal/fakeSignalSource';

export type DetectorKind = 'clikart' | 'http' | 'coreml' | 'fake';
export type SignalKindSetting = 'usb' | 'fake';

/**
 * The app's settings (the native app's `Prefs` + `SettingsForm`). Defaults make a fresh install demo-able: on an
 * Android build the on-device ClikaRT detector, elsewhere the simulated one; the simulated gadget until the reader
 * gadget is connected; the README's physical-layer budgets.
 */
export interface Settings {
  detector: DetectorKind;
  /** Empty = the app's default snapshot directory (`<externalFilesDir>/models/rtdetr_v2_r18vd`). */
  modelDir: string;
  /** `clikart-cli serve` on the laptop, the H4 contingency. */
  serveUrl: string;
  threads: number;
  intervalMs: number;
  signal: SignalKindSetting;
  fakeMode: FakeMode;
  /** Laptop dashboard, e.g. `http://192.168.43.100:8081`; empty disables posting. */
  dashboardUrl: string;
  challengerId: number;
  /** Comma-separated drone ids accepted as FRIEND; empty accepts any verified id. */
  acceptedDroneIds: string;
  personThreshold: number;
  droneThreshold: number;
  rttBudgetMs: number;
  rssiFloorDbm: number;
  strictPhysical: boolean;
}

/** FofDrone's floor (see detect/labelMap.ts): 12/13 real drones kept, 4/40 drone-free images still fire. */
const IOS_DRONE_THRESHOLD = 0.6;

export const DEFAULT_SETTINGS: Settings = {
  detector: hasNativeCamera ? onDeviceDetector : 'fake',
  modelDir: '',
  serveUrl: 'http://192.168.43.100:8000',
  threads: 2,
  intervalMs: Platform.OS === 'ios' ? 500 : 1000,
  signal: 'fake',
  fakeMode: 'HONEST',
  dashboardUrl: '',
  challengerId: 7,
  acceptedDroneIds: 'RQ-07',
  personThreshold: DEFAULT_PERSON_THRESHOLD,
  droneThreshold: Platform.OS === 'ios' ? IOS_DRONE_THRESHOLD : DEFAULT_DRONE_THRESHOLD,
  rttBudgetMs: 2500,
  rssiFloorDbm: -70,
  strictPhysical: false,
};

const KEY = 'fof.settings.v1';
/** Bumped when a default changes in a way stored settings must follow; see [sanitize]. */
const SCHEMA = 2;

/** What this build can run: Android ClikaRT + laptop serve, iPhone Core ML, and everywhere the simulated boxes. */
export function availableDetectors(): DetectorKind[] {
  if (!hasNativeCamera) return ['fake'];
  return onDeviceDetector === 'coreml' ? ['coreml', 'fake'] : ['clikart', 'http', 'fake'];
}

export function acceptedIds(s: Settings): string[] {
  return s.acceptedDroneIds
    .split(',')
    .map((id) => id.trim())
    .filter(Boolean);
}

/** Keeps only known keys with the right types, so an old or hand-edited store can never break the app. */
export function sanitize(raw: unknown): Settings {
  const out: Settings = { ...DEFAULT_SETTINGS };
  if (!raw || typeof raw !== 'object') return out;
  const r = raw as Record<string, unknown>;
  for (const key of Object.keys(DEFAULT_SETTINGS) as (keyof Settings)[]) {
    const v = r[key];
    if (typeof v === typeof DEFAULT_SETTINGS[key]) (out as unknown as Record<string, unknown>)[key] = v;
  }
  if (!availableDetectors().includes(out.detector)) out.detector = DEFAULT_SETTINGS.detector;
  if (!['usb', 'fake'].includes(out.signal) || (out.signal === 'usb' && !FofUsb)) out.signal = 'fake';
  if (!FAKE_MODES.includes(out.fakeMode)) out.fakeMode = 'HONEST';
  // Schema 1 was saved by builds without the iPhone camera: their 'fake' detector, 1 s interval and 0.28 drone floor
  // were forced or defaulted, not chosen, so they move to this build's defaults once.
  if (r.schema !== SCHEMA && Platform.OS === 'ios') {
    if (out.detector === 'fake') out.detector = DEFAULT_SETTINGS.detector;
    if (out.intervalMs === 1000) out.intervalMs = DEFAULT_SETTINGS.intervalMs;
    if (out.droneThreshold === DEFAULT_DRONE_THRESHOLD) out.droneThreshold = DEFAULT_SETTINGS.droneThreshold;
  }
  return out;
}

export async function loadSettings(): Promise<Settings> {
  try {
    const text = await AsyncStorage.getItem(KEY);
    return sanitize(text ? JSON.parse(text) : null);
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

export async function saveSettings(s: Settings): Promise<void> {
  try {
    await AsyncStorage.setItem(KEY, JSON.stringify({ ...s, schema: SCHEMA }));
  } catch {
    // Settings still apply for this run.
  }
}

export type SettingsErrors = Partial<Record<keyof Settings, string>>;

const isHttp = (s: string) => /^https?:\/\/[^\s/]+/.test(s.trim());

/** Field errors for the settings form; an empty object means the draft can be saved. */
export function validate(s: Settings): SettingsErrors {
  const e: SettingsErrors = {};
  if (s.detector === 'http' && !isHttp(s.serveUrl)) e.serveUrl = 'http://<laptop-ip>:8000';
  if (s.dashboardUrl.trim() && !isHttp(s.dashboardUrl)) e.dashboardUrl = 'blank, or http://<laptop-ip>:8081';
  const int = (k: keyof Settings, v: number, lo: number, hi: number, hint: string) => {
    if (!Number.isInteger(v) || v < lo || v > hi) e[k] = hint;
  };
  int('threads', s.threads, 1, 8, '1 – 8');
  int('intervalMs', s.intervalMs, 200, 5000, '200 – 5000 ms');
  int('challengerId', s.challengerId, 0, 65535, '0 – 65535');
  int('rttBudgetMs', s.rttBudgetMs, 1, 10000, '1 – 10000 ms');
  int('rssiFloorDbm', s.rssiFloorDbm, -120, 0, '−120 – 0 dBm');
  for (const k of ['personThreshold', 'droneThreshold'] as const) {
    if (!(s[k] >= 0.05 && s[k] <= 0.95)) e[k] = '0.05 – 0.95';
  }
  return e;
}
