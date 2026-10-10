import { DEFAULT_SETTINGS, sanitize } from '../app/settings';

jest.mock('@react-native-async-storage/async-storage', () => require('@react-native-async-storage/async-storage/jest/async-storage-mock'));

// jest-expo runs as iOS; no native module is loaded, so only the simulated detector is available here.
describe('settings', () => {
  it('fills unknown or mistyped fields from the defaults', () => {
    expect(sanitize({ threads: 'four', challengerId: 9, bogus: true })).toMatchObject({ threads: DEFAULT_SETTINGS.threads, challengerId: 9 });
    expect(sanitize(null)).toEqual(DEFAULT_SETTINGS);
  });

  it('moves schema-1 iPhone settings to the Core ML defaults once', () => {
    const old = sanitize({ intervalMs: 1000, droneThreshold: 0.28 });
    expect(old).toMatchObject({ intervalMs: 500, droneThreshold: 0.6 });
    const chosen = sanitize({ intervalMs: 1000, droneThreshold: 0.28, schema: 2 });
    expect(chosen).toMatchObject({ intervalMs: 1000, droneThreshold: 0.28 });
  });
});
