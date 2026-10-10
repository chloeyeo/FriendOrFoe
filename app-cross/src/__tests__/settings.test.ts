import { DEFAULT_SETTINGS, sanitize, validate } from '../app/settings';

jest.mock('@react-native-async-storage/async-storage', () => require('@react-native-async-storage/async-storage/jest/async-storage-mock'));

// jest-expo runs as iOS; no native module is loaded, so only the simulated detector is available here.
describe('settings', () => {
  it('fills unknown or mistyped fields from the defaults', () => {
    expect(sanitize({ threads: 'four', challengerId: 9, bogus: true })).toMatchObject({ threads: DEFAULT_SETTINGS.threads, challengerId: 9 });
    expect(sanitize(null)).toEqual(DEFAULT_SETTINGS);
  });

  it('moves older iPhone settings to the current defaults once', () => {
    expect(sanitize({ intervalMs: 1000, droneThreshold: 0.28 })).toMatchObject({ intervalMs: 500, droneThreshold: 0.78 });
    expect(sanitize({ intervalMs: 1000, droneThreshold: 0.6, schema: 2 })).toMatchObject({ intervalMs: 1000, droneThreshold: 0.78 });
    expect(sanitize({ droneThreshold: 0.65, schema: 2 })).toMatchObject({ droneThreshold: 0.65 }); // a chosen value stays
    expect(sanitize({ intervalMs: 1000, droneThreshold: 0.6, schema: 3 })).toMatchObject({ intervalMs: 1000, droneThreshold: 0.6 });
  });

  it("adds the reader's drone id to the old accepted-id default, keeping a chosen list", () => {
    expect(sanitize({ acceptedDroneIds: 'RQ-07', schema: 3 }).acceptedDroneIds).toBe('RQ-07, 1');
    expect(sanitize({ acceptedDroneIds: 'RQ-09', schema: 3 }).acceptedDroneIds).toBe('RQ-09');
    expect(sanitize({ acceptedDroneIds: 'RQ-07', schema: 4 }).acceptedDroneIds).toBe('RQ-07');
  });

  it('keeps the laptop bridge but not USB in a build without the USB module', () => {
    expect(sanitize({ signal: 'bridge' }).signal).toBe('bridge');
    expect(sanitize({ signal: 'usb' }).signal).toBe('fake');
    expect(validate({ ...DEFAULT_SETTINGS, signal: 'bridge', readerBridgeUrl: '10.0.0.2:8766' }).readerBridgeUrl).toBe('http://<laptop-ip>:8766');
    expect(validate({ ...DEFAULT_SETTINGS, signal: 'bridge' })).toEqual({});
  });
});
