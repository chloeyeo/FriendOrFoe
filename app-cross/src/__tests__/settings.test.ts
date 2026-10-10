import { DEFAULT_SETTINGS, sanitize } from '../app/settings';

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
});
