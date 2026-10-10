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
  /** Android dual detector snapshot dirs; empty = `<externalFilesDir>/models/rtdetr_v2_drone_rahul_320` and
   * `dfine_small_coco_320` (the native app's defaults at 320x320 input). */
  droneModelDir: string;
  personModelDir: string;
  /** `clikart-cli serve` on the laptop, the H4 contingency. */
  serveUrl: string;
  threads: number;
  intervalMs: number;
  /** Which classes to detect; a class switched off is not drawn, and its model is not run. */
  detectPerson: boolean;
  detectDrone: boolean;
  detectBird: boolean;
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

/**
 * FofDrone's floor (see detect/labelMap.ts): at 0.78 it keeps 9/13 real drone photos and fires on 1/40 drone-free
 * images (a real airplane); at 0.6 it kept 12/13 but also boxed a potted plant, a bat and a skateboard, which
 * showed up on the phone too.
 */
const IOS_DRONE_THRESHOLD = 0.78;
/** The iPhone default of schema 2, moved up to IOS_DRONE_THRESHOLD by schema 3. */
const SCHEMA2_IOS_DRONE_THRESHOLD = 0.6;
/** The native app's floor for the RahulKate drone model (ClassMap.DRONE_RAHUL). */
const ANDROID_DRONE_THRESHOLD = 0.3;

export const DEFAULT_SETTINGS: Settings = {
  detector: hasNativeCamera ? onDeviceDetector : 'fake',
  droneModelDir: '',
  personModelDir: '',
  serveUrl: 'http://192.168.43.100:8000',
  threads: 2,
  intervalMs: Platform.OS === 'ios' ? 500 : 1000,
  detectPerson: true,
  detectDrone: true,
  detectBird: true,
  signal: 'fake',
  fakeMode: 'HONEST',
  dashboardUrl: '',
  challengerId: 7,
  acceptedDroneIds: 'RQ-07',
  personThreshold: DEFAULT_PERSON_THRESHOLD,
  droneThreshold: Platform.OS === 'ios' ? IOS_DRONE_THRESHOLD : ANDROID_DRONE_THRESHOLD,
  rttBudgetMs: 2500,
  rssiFloorDbm: -70,
  strictPhysical: false,
};

const KEY = 'fof.settings.v1';
/** Bumped when a default changes in a way stored settings must follow; see [sanitize]. */
const SCHEMA = 3;

/** What this build can run: Android ClikaRT + laptop serve, iPhone Core ML, and everywhere the simulated boxes. */
export function availableDetectors(): DetectorKind[] {
  if (!hasNativeCamera) return ['fake'];
  return onDeviceDetector === 'coreml' ? ['coreml', 'fake'] : ['clikart', 'http', 'fake'];
}

/** The native model tags that must run for the classes switched on (iPhone Core ML, Android dual ClikaRT). */
export function modelsFor(s: Settings): string[] {
  if (s.detector === 'coreml') {
    return [...(s.detectPerson || s.detectBird ? ['coco'] : []), ...(s.detectDrone ? ['drone', 'drone2'] : [])];
  }
  return [...(s.detectPerson ? ['clika-person'] : []), ...(s.detectDrone ? ['clika-drone'] : [])];
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
  // Older schemas on iPhone hold defaults, not choices, so they move to this build's defaults once. Schema 1 (builds
  // without the iPhone camera): the forced 'fake' detector, 1 s interval and 0.28 drone floor. Schema 2: the 0.6 floor.
  const schema = typeof r.schema === 'number' ? r.schema : 1;
  if (schema < SCHEMA && Platform.OS === 'ios') {
    if (schema < 2) {
      if (out.detector === 'fake') out.detector = DEFAULT_SETTINGS.detector;
      if (out.intervalMs === 1000) out.intervalMs = DEFAULT_SETTINGS.intervalMs;
    }
    if (out.droneThreshold === DEFAULT_DRONE_THRESHOLD || out.droneThreshold === SCHEMA2_IOS_DRONE_THRESHOLD) {
      out.droneThreshold = DEFAULT_SETTINGS.droneThreshold;
    }
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
