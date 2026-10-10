import { box } from '../core/box';
import type { Detection } from '../core/types';
import { dedup, toDetections } from '../detect/dedup';
import { parseDetections } from '../detect/detectionsJson';
import { accept, classOf } from '../detect/labelMap';
import { FakeDetector } from '../detect/fakeDetector';
import { frameTransform, toFrame, toPreview } from '../camera/frameTransform';

const drone = (score: number, b = box(10, 10, 50, 40)): Detection => ({ cls: 'DRONE', score, box: b });
const person = (score: number, b = box(10, 10, 50, 40)): Detection => ({ cls: 'PERSON', score, box: b });

describe('LabelMap', () => {
  it('maps person and the drone proxies by id, drops the rest', () => {
    expect(classOf(0)).toBe('PERSON');
    expect([4, 14, 33].map(classOf)).toEqual(['DRONE', 'DRONE', 'DRONE']);
    expect(classOf(67)).toBeNull(); // cell phone
  });

  it('applies the per-class floors', () => {
    expect(accept(0, 0.49)).toBeNull();
    expect(accept(0, 0.5)).toBe('PERSON');
    expect(accept(4, 0.27)).toBeNull();
    expect(accept(4, 0.28)).toBe('DRONE');
    expect(accept(4, 0.3, { person: 0.5, drone: 0.4 })).toBeNull();
  });
});

describe('Dedup', () => {
  it('keeps the higher-scored same-class duplicate', () => {
    expect(dedup([drone(0.9), drone(0.5)])).toEqual([drone(0.9)]);
  });

  it('lets a drone replace the persons it overlaps, even when they scored higher', () => {
    expect(dedup([person(0.9), drone(0.4)])).toEqual([drone(0.4)]);
  });

  it('drops a person under a kept drone and keeps far-apart boxes', () => {
    const far = person(0.95, box(500, 500, 600, 700));
    expect(dedup([drone(0.9), person(0.8), far])).toEqual([far, drone(0.9)]);
  });

  it('maps raw rows through the label map, then de-duplicates', () => {
    const rows = [
      { labelId: 0, score: 0.92, box: box(10, 10, 50, 40) },
      { labelId: 33, score: 0.31, box: box(11, 10, 50, 41) },
      { labelId: 67, score: 0.99, box: box(200, 200, 260, 300) },
    ];
    expect(toDetections(rows)).toEqual([{ cls: 'DRONE', score: 0.31, box: box(11, 10, 50, 41) }]);
  });
});

describe('DetectionsJson', () => {
  it('parses rows in order and skips malformed ones', () => {
    const body = JSON.stringify({
      detections: [
        { box: { xmin: 1, ymin: 2, xmax: 30, ymax: 40 }, score: 0.61, label_id: 4, label: 'aeroplane' },
        { box: { xmin: 5, ymin: 5, xmax: 1, ymax: 9 }, score: 0.5, label_id: 0 },
        { box: { xmin: 1, ymin: 1, xmax: 2, ymax: 2 }, score: 1.5, label_id: 0 },
        { score: 0.4, label_id: 0 },
        null,
        { box: { xmin: 0, ymin: 0, xmax: 10, ymax: 10 }, score: 0.55, label_id: 0, extra: true },
      ],
      model: 'rt-detr',
    });
    expect(parseDetections(body)).toEqual([
      { labelId: 4, score: 0.61, box: box(1, 2, 30, 40), label: 'aeroplane' },
      { labelId: 0, score: 0.55, box: box(0, 0, 10, 10), label: '' },
    ]);
  });

  it('throws on a body that is not JSON or has no detections array', () => {
    expect(() => parseDetections('<html>')).toThrow('not JSON');
    expect(() => parseDetections('{"error":"busy"}')).toThrow('no detections array');
  });
});

describe('FrameTransform', () => {
  it('fills the view like FILL_CENTER, cropping the overflow equally', () => {
    const t = frameTransform(1280, 720, 1600, 720);
    expect(t.scale).toBeCloseTo(1.25);
    expect(t.offsetY).toBeCloseTo(-90);
    expect(toPreview(t, box(0, 72, 128, 144))).toEqual(box(0, 0, 160, 90));
    const [x, y] = toFrame(t, 160, 90);
    expect(x).toBeCloseTo(128);
    expect(y).toBeCloseTo(144);
  });

  it('letterboxes in fit mode', () => {
    const t = frameTransform(1920, 1080, 960, 720, 'fit');
    expect(t.scale).toBeCloseTo(0.5);
    expect(t.offsetX).toBe(0);
    expect(t.offsetY).toBe(90);
  });
});

describe('FakeDetector', () => {
  it('is deterministic and score-descending', () => {
    const a = new FakeDetector().detect(1920, 1080);
    const b = new FakeDetector().detect(1920, 1080);
    expect(a).toEqual(b);
    expect(a.map((d) => d.cls)).toEqual(['PERSON', 'DRONE']);
  });
});
