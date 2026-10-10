import { box } from '../core/box';
import type { Detection } from '../core/types';
import { IffCoordinator } from '../session/iffCoordinator';
import { FakeSignalSource } from '../signal/fakeSignalSource';

const DRONE: Detection = { cls: 'DRONE', score: 0.9, box: box(500, 300, 540, 330) };

describe('IffCoordinator.rescale (camera zoom)', () => {
  it('scales the drawn boxes about the frame centre and keeps ids and verdict state', () => {
    const c = new IffCoordinator({ detectorName: 'test', signalSource: new FakeSignalSource('SILENT'), clock: () => 1_000 });
    c.setPreviewSize(1280, 720);
    c.tick({ width: 1280, height: 720, detections: [DRONE] });
    const [track] = c.getState().tracks;
    c.challenge(track.id);
    c.rescale(2);
    const [zoomed] = c.getState().tracks;
    expect(zoomed.id).toBe(track.id);
    expect(zoomed.iff.kind).toBe('CHALLENGED');
    // (500, 300) is (-140, -60) from the centre (640, 360); at 2x it is (-280, -120) -> (360, 240).
    expect(zoomed.box).toEqual(box(360, 240, 440, 300));
    expect(c.lock?.rect).toEqual(box(360, 240, 440, 300));
  });

  it('a later detection of the zoomed object matches the same track', () => {
    const c = new IffCoordinator({ detectorName: 'test', signalSource: new FakeSignalSource(), clock: () => 1_000 });
    c.tick({ width: 1280, height: 720, detections: [DRONE] });
    const id = c.getState().tracks[0].id;
    c.rescale(2);
    c.tick({ width: 1280, height: 720, detections: [{ ...DRONE, box: box(362, 241, 442, 301) }] });
    expect(c.getState().tracks.map((t) => t.id)).toEqual([id]);
  });

  it('hides a switched-off class at once instead of letting it coast', () => {
    const c = new IffCoordinator({ detectorName: 'test', signalSource: new FakeSignalSource(), clock: () => 1_000 });
    c.tick({ width: 1280, height: 720, detections: [DRONE, { cls: 'PERSON', score: 0.9, box: box(800, 200, 900, 600) }] });
    c.hideClasses(['DRONE']);
    expect(c.getState().tracks.map((t) => t.cls)).toEqual(['PERSON']);
  });
});
