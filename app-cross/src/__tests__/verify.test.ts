import { box } from '../core/box';
import type { RawDetection } from '../core/types';
import { confirmDrones } from '../detect/verify';
import { DEFAULT_SETTINGS, modelsFor } from '../app/settings';

jest.mock('@react-native-async-storage/async-storage', () => require('@react-native-async-storage/async-storage/jest/async-storage-mock'));

const row = (model: string, b = box(100, 100, 200, 160), score = 0.85): RawDetection => ({ labelId: -1, score, box: b, model });

describe('confirmDrones', () => {
  it('keeps a FofDrone box only when FofDrone2 boxed the same object, and never draws FofDrone2 itself', () => {
    expect(confirmDrones([row('drone'), row('drone2', box(105, 102, 205, 165), 0.3)])).toEqual([row('drone')]);
    expect(confirmDrones([row('drone')])).toEqual([]);
    expect(confirmDrones([row('drone'), row('drone2', box(400, 300, 450, 340))])).toEqual([]);
  });

  it('passes rows from other models through', () => {
    const person = row('coco');
    const android = row('clika-drone');
    expect(confirmDrones([person, android])).toEqual([person, android]);
  });
});

describe('modelsFor', () => {
  it('runs only the models the switched-on classes need', () => {
    const ios = { ...DEFAULT_SETTINGS, detector: 'coreml' as const };
    expect(modelsFor(ios)).toEqual(['coco', 'drone', 'drone2']);
    expect(modelsFor({ ...ios, detectPerson: false, detectBird: false })).toEqual(['drone', 'drone2']);
    expect(modelsFor({ ...ios, detectDrone: false })).toEqual(['coco']);
    const android = { ...DEFAULT_SETTINGS, detector: 'clikart' as const };
    expect(modelsFor(android)).toEqual(['clika-person', 'clika-drone']);
    expect(modelsFor({ ...android, detectPerson: false })).toEqual(['clika-drone']);
  });
});
