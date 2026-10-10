import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';

import { FofRuntime, hasNativeCamera, type CameraErrorEvent, type CameraFrameEvent, type GrayEvent } from '../../modules/fof-native';
import type { EventUi, ObjClass, RawDetection } from '../core/types';
import { toDetections } from '../detect/dedup';
import { labelIdFor } from '../detect/labelMap';
import { confirmDrones } from '../detect/verify';
import { parseDetections } from '../detect/detectionsJson';
import { FakeDetector } from '../detect/fakeDetector';
import { EventClient } from '../net/eventClient';
import { eventBody, frameBody, planEvents } from '../net/eventJson';
import { IffCoordinator } from '../session/iffCoordinator';
import { grayFromEvent } from '../track/grayFrame';
import { FakeSignalSource } from '../signal/fakeSignalSource';
import { UsbGadgetSignalSource, type GadgetLinkState } from '../signal/usbGadgetSignalSource';
import { probeOffline } from './offline';
import { acceptedIds, type Settings } from './settings';

export function detectorName(s: Settings): string {
  if (s.detector === 'clikart') {
    // The native app's DualDetector name; the default dirs are the 320x320-input snapshots.
    const at320 = !s.droneModelDir.trim() || s.droneModelDir.trim().replace(/\/+$/, '').endsWith('_320');
    return `clikart:drone-rahul+dfine-s${at320 ? ' @320' : ''}`;
  }
  if (s.detector === 'http') return 'http:' + s.serveUrl.replace(/^https?:\/\//, '').replace(/\/+$/, '');
  if (s.detector === 'coreml') return 'coreml:yolo11n+drone-yolo11s';
  return 'fake';
}

/**
 * Wires the session the way the native app's `FofApplication` + `MainActivity` are planned to (02-02, 02-04,
 * Phase 5): one coordinator for the process; the detector chosen in Settings (ClikaRT on the camera frames, the
 * laptop serve, or the simulated boxes on a timer); the signal source (reader gadget over USB, or the simulated
 * gadget); the OFFLINE probe; and the laptop dashboard mirror. Everything the screen shows comes from
 * `coordinator.getState()`.
 */
export function useSession(settings: Settings) {
  const coordinatorRef = useRef<IffCoordinator | null>(null);
  if (!coordinatorRef.current) {
    coordinatorRef.current = new IffCoordinator({ detectorName: detectorName(settings), signalSource: new FakeSignalSource(settings.fakeMode) });
  }
  const c = coordinatorRef.current;
  const state = useSyncExternalStore(c.subscribe, c.getState, c.getState);
  const preview = useRef({ width: 0, height: 0 });
  const loadNote = useRef<string | null>(null);
  const frameFailed = useRef(false);
  const [gadget, setGadget] = useState<GadgetLinkState>({ connected: false, note: null });
  const [usbSource, setUsbSource] = useState<UsbGadgetSignalSource | null>(null);
  const [cameraGranted, setCameraGranted] = useState(false);

  const onPreviewLayout = useCallback(
    (width: number, height: number) => {
      preview.current = { width, height };
      c.setPreviewSize(width, height);
    },
    [c],
  );

  // Signal source: the reader gadget over USB, or the simulated gadget (demo mode).
  useEffect(() => {
    if (settings.signal === 'usb') {
      const usb = new UsbGadgetSignalSource((link) => {
        setGadget(link);
        c.setStatus((s) => ({ ...s, gadgetConnected: link.connected }));
      });
      c.signalSource = usb;
      setUsbSource(usb);
      setGadget(usb.linkState);
      c.setStatus((s) => ({ ...s, gadgetConnected: false }));
      usb.connect().catch((e: Error) => c.log(`gadget · ${e.message}`));
      return () => {
        usb.close();
        setUsbSource(null);
      };
    }
    const fake = new FakeSignalSource(settings.fakeMode);
    c.signalSource = fake;
    setGadget({ connected: true, note: 'scripted answers; switch the drone-side mode below' });
    c.setStatus((s) => ({ ...s, gadgetConnected: true }));
    return () => fake.close();
    // fakeMode changes are applied in place below, without replacing the source (its nonce memory drives REPLAY).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [c, settings.signal]);

  useEffect(() => {
    if (c.signalSource instanceof FakeSignalSource) c.signalSource.mode = settings.fakeMode;
  }, [c, settings.fakeMode, settings.signal]);

  // A class switched off disappears at once (and its model stops running, see modelsFor).
  useEffect(() => {
    const off: ObjClass[] = [];
    if (!settings.detectPerson) off.push('PERSON');
    if (!settings.detectDrone) off.push('DRONE');
    if (!settings.detectBird) off.push('BIRD');
    c.hideClasses(off);
  }, [c, settings.detectPerson, settings.detectDrone, settings.detectBird]);

  useEffect(() => {
    c.reChecks.config = { rttBudgetMs: settings.rttBudgetMs, rssiFloorDbm: settings.rssiFloorDbm, strict: settings.strictPhysical };
    c.challengerId = settings.challengerId;
    c.acceptedDroneIds = acceptedIds(settings);
    // The iPhone detector runs several times a second, so a false drone that flickers for a frame or two is held back
    // (about 1 s at 0.5 s per frame); phone A's ClikaRT takes seconds per frame, where waiting would cost seconds.
    c.confirmHits = settings.detector === 'coreml' ? 3 : 1;
  }, [c, settings]);

  // Camera permission, once, on a build with the camera pane.
  useEffect(() => {
    if (!hasNativeCamera || settings.detector === 'fake' || cameraGranted) return;
    FofRuntime!
      .requestCameraPermission()
      .then((r) => {
        setCameraGranted(r.granted);
        if (!r.granted) c.setStatus((s) => ({ ...s, modelNote: 'camera permission denied' }));
      })
      .catch((e: Error) => c.setStatus((s) => ({ ...s, modelNote: e.message })));
  }, [c, settings.detector, cameraGranted]);

  // Detector bring-up.
  useEffect(() => {
    let cancelled = false;
    frameFailed.current = false;
    const note = (modelNote: string | null, modelReady: boolean) => {
      loadNote.current = modelNote;
      c.setStatus((s) => ({ ...s, detectorName: detectorName(settings), modelNote, modelReady }));
    };

    if (settings.detector === 'fake') {
      // A fixed 1280x720 frame, like the camera's analysis stream, letterboxed into the view: the boxes keep their
      // frame-pixel identity when the window is resized, so the tracker keeps its ids.
      c.previewMode = 'fit';
      note('simulated detector', true);
      const fake = new FakeDetector();
      const timer = setInterval(() => {
        const on = classesRef.current;
        const detections = fake.detect(1280, 720).filter((d) => (d.cls === 'PERSON' ? on.detectPerson : on.detectDrone));
        c.tick({ width: 1280, height: 720, detections, msPerFrame: 0 });
      }, settings.intervalMs);
      return () => clearInterval(timer);
    }
    c.previewMode = 'fill';

    if (settings.detector === 'http') {
      note(`laptop serve ${settings.serveUrl.replace(/^https?:\/\//, '')}`, false);
      return () => {};
    }

    // On-device: ClikaRT (Android: license probe + model open on the runtime lane) or Core ML (iPhone: the two bundled
    // detectors), with a seconds counter meanwhile.
    if (!FofRuntime) {
      note('on-device detector not in this build', false);
      return () => {};
    }
    const started = Date.now();
    note('model loading…', false);
    const ticker = setInterval(() => {
      if (!cancelled) note(`model loading… ${Math.round((Date.now() - started) / 1000)} s`, false);
    }, 1000);
    const defaults = FofRuntime.defaultModelDirs();
    FofRuntime.openDetector(settings.droneModelDir.trim() || defaults.drone, settings.personModelDir.trim() || defaults.person, settings.threads)
      .then((r) => {
        if (cancelled) return;
        clearInterval(ticker);
        note(`load ${(r.loadMs / 1000).toFixed(1)} s · warm ${r.warmupMs} ms · ${r.threads} thr`, true);
      })
      .catch((e: Error) => {
        if (cancelled) return;
        clearInterval(ticker);
        note(e.message || 'model failed to load', false);
        c.log(`model · ${e.message}`);
      });
    return () => {
      cancelled = true;
      clearInterval(ticker);
    };
    // Thresholds are applied per frame; they do not reload the model.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [c, settings.detector, settings.droneModelDir, settings.personModelDir, settings.threads, settings.serveUrl, settings.intervalMs]);

  const thresholds = { person: settings.personThreshold, drone: settings.droneThreshold };
  const thresholdsRef = useRef(thresholds);
  thresholdsRef.current = thresholds;
  const classesRef = useRef(settings);
  classesRef.current = settings;

  const onFrame = useCallback(
    (e: CameraFrameEvent) => {
      if (e.error) {
        frameFailed.current = true;
        c.setStatus((s) => ({ ...s, modelNote: e.error }));
        return;
      }
      if (!e.detected) return;
      let rows: RawDetection[];
      try {
        rows = e.rows
          ? e.rows.map((r) => ({
              labelId: r.model ? labelIdFor(r.model, r.label) : r.labelId,
              score: r.score,
              box: { left: r.left, top: r.top, right: r.right, bottom: r.bottom },
              label: r.label,
              model: r.model,
            }))
          : parseDetections(e.body ?? '');
        rows = confirmDrones(rows);
      } catch (err) {
        frameFailed.current = true;
        c.setStatus((s) => ({ ...s, modelNote: (err as Error).message.slice(0, 80) }));
        return;
      }
      if (frameFailed.current || e.note) {
        frameFailed.current = false;
        c.setStatus((s) => ({ ...s, modelNote: e.note ?? loadNote.current }));
      }
      const on = classesRef.current;
      const detections = toDetections(rows, thresholdsRef.current).filter(
        (d) => (d.cls === 'PERSON' && on.detectPerson) || (d.cls === 'DRONE' && on.detectDrone) || (d.cls === 'BIRD' && on.detectBird),
      );
      c.tick({ width: e.width, height: e.height, detections, msPerFrame: e.ms, timestampMs: e.ts });
    },
    [c],
  );

  // 10 fps gray frames: the motion tracker moves the rings between detections (the native app's motion tick).
  const onGray = useCallback((e: GrayEvent) => c.motionTick(grayFromEvent(e)), [c]);

  const onCameraError = useCallback((e: CameraErrorEvent) => c.setStatus((s) => ({ ...s, modelNote: `${e.code} · ${e.message}` })), [c]);

  // OFFLINE badge.
  useEffect(() => {
    let alive = true;
    const run = () =>
      probeOffline().then((offline) => {
        if (alive && offline !== c.getState().status.offline) c.setStatus((s) => ({ ...s, offline }));
      });
    void run();
    const timer = setInterval(run, 2000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [c]);

  // Laptop dashboard mirror: new log rows as they appear, the drawn tracks once a second.
  useEffect(() => {
    const url = settings.dashboardUrl.trim();
    if (!url) return;
    const client = new EventClient(url);
    let lastEvents: EventUi[] | null = null;
    let lastSent: EventUi | null = null;
    const flush = () => {
      const events = c.getState().events;
      if (events === lastEvents) return;
      lastEvents = events;
      const plan = planEvents(events, lastSent);
      if (plan.reset) client.reset();
      if (plan.send.length) client.post(plan.send.map(eventBody));
      lastSent = events[0] ?? null;
    };
    flush();
    const unsubscribe = c.subscribe(flush);
    const timer = setInterval(() => {
      const { width, height } = preview.current;
      if (width > 0 && height > 0) client.postFrame(frameBody(Date.now(), c.getState().tracks, width, height));
    }, 1000);
    return () => {
      unsubscribe();
      clearInterval(timer);
      client.close();
    };
  }, [c, settings.dashboardUrl]);

  const challenge = useCallback((id: number) => c.challenge(id), [c]);
  const zoomBy = useCallback((factor: number) => c.rescale(factor), [c]);
  const reset = useCallback(() => c.reset(), [c]);
  const connectGadget = useCallback(() => {
    usbSource?.connect().catch((e: Error) => c.log(`gadget · ${e.message}`));
  }, [c, usbSource]);

  return { state, onFrame, onGray, onCameraError, zoomBy, onPreviewLayout, challenge, reset, gadget, connectGadget, cameraGranted };
}
