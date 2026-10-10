import { frameTransform, toPreview, type FrameTransform } from '../camera/frameTransform';
import {
  DEFAULT_WINDOW_MS,
  EMPTY_STATE,
  UNCHALLENGED,
  isIdentifiable,
  type BoxF,
  type EventUi,
  type FrameResult,
  type ObjClass,
  type IffState,
  type ObserverUiState,
  type SignalUi,
  type StatusUi,
  type TrackUi,
  type VerdictKind,
} from '../core/types';
import { ReChecks } from '../signal/reChecks';
import type { ChallengeRequest, SignalEvent, SignalSource } from '../signal/signal';
import type { GrayFrame } from '../track/grayFrame';
import { MotionTracker, type Seed } from '../track/motionTracker';
import { Tracker } from '../track/tracker';

/** The tapped box frozen for the challenge window (track/BoxLock.kt); `rect` is in frame pixels. */
export interface BoxLock {
  boxId: number;
  cls: ObjClass;
  rect: BoxF;
  until: number;
}

export interface CoordinatorOptions {
  detectorName: string;
  signalSource: SignalSource;
  reChecks?: ReChecks;
  tracker?: Tracker;
  motion?: MotionTracker;
  /** Wall clock, ms (locks, countdowns, event times). */
  clock?: () => number;
  /** Monotonic ms for the phone-side round trip. */
  monotonic?: () => number;
  challengerId?: number;
  /** Drone ids this observer accepts as FRIEND; empty accepts any id the gadget verified. */
  acceptedDroneIds?: string[];
  /** `fill` for the CameraX preview (FILL_CENTER), `fit` for a letterboxed view. */
  previewMode?: 'fill' | 'fit';
  /** A drone is drawn (and challengeable) only once its track has matched this many frames; 1 shows it at once. */
  confirmHits?: number;
  windowMs?: number;
  eventCapacity?: number;
}

type Listener = (state: ObserverUiState) => void;

/**
 * The session (session/IffCoordinator.kt, plus what plans 02-03 and Phase 5 add): frames in, [ObserverUiState] out.
 *
 * - `tick(frame)`: detections (fast-forwarded from their capture time) -> sticky tracker -> preview coordinates; new
 *   tracks are logged as detection rows; the locked box stays on screen while the detector has lost it (DET-06).
 * - `motionTick(gray)`: the 10 fps motion tracker moves every drawn box between detections (track/MotionTracker.kt).
 * - `challenge(boxId)`: refuses while a window is open or for anything but a live drone or person track (a person
 *   carries the same responder as a friendly drone); locks the box for
 *   10 s, shows the countdown, asks the [SignalSource], measures the phone-side round trip, runs [ReChecks] (and the
 *   accepted-id list), then draws the verdict on that box, adds a gadget-panel row and a structured event. No answer
 *   in the window is `UNKNOWN · no response`; silence is never FRIEND.
 * - `reset()`: the Reset button — tracks, nonce ledger, verdicts, lock and log start clean.
 *
 * Single-threaded (the JS thread), so no locking is needed; a reset bumps a generation counter so an answer that
 * arrives after it is dropped instead of landing on a fresh session.
 */
export class IffCoordinator {
  private state: ObserverUiState;
  private listeners = new Set<Listener>();
  private readonly tracker: Tracker;
  private readonly motion: MotionTracker;
  readonly reChecks: ReChecks;
  private readonly clock: () => number;
  private readonly monotonic: () => number;
  private readonly windowMs: number;
  private readonly eventCapacity: number;
  /** `fill` for the CameraX preview (FILL_CENTER); `fit` letterboxes the simulated frame so all of it is visible. */
  previewMode: 'fill' | 'fit';
  private previewWidth = 0;
  private previewHeight = 0;
  /** The analysis frame size the boxes live in (from the last detection or gray frame). */
  private frameWidth = 0;
  private frameHeight = 0;
  private transform: FrameTransform = { scale: 1, offsetX: 0, offsetY: 0 };
  private lastTick = 0;
  private iffById = new Map<number, IffState>();
  private announced = new Set<number>();
  private events: EventUi[] = [];
  private generation = 0;
  private inFlight: AbortController | null = null;
  private _lock: BoxLock | null = null;
  signalSource: SignalSource;
  challengerId: number;
  acceptedDroneIds: string[];
  /** See [CoordinatorOptions.confirmHits]; 2 on the fast iPhone detector filters one-frame false drones. */
  confirmHits: number;

  constructor(options: CoordinatorOptions) {
    this.signalSource = options.signalSource;
    this.reChecks = options.reChecks ?? new ReChecks();
    this.tracker = options.tracker ?? new Tracker();
    this.motion = options.motion ?? new MotionTracker();
    this.clock = options.clock ?? (() => Date.now());
    this.monotonic = options.monotonic ?? (() => (globalThis.performance?.now ? globalThis.performance.now() : Date.now()));
    this.challengerId = options.challengerId ?? 7;
    this.acceptedDroneIds = options.acceptedDroneIds ?? [];
    this.confirmHits = options.confirmHits ?? 1;
    this.previewMode = options.previewMode ?? 'fill';
    this.windowMs = options.windowMs ?? DEFAULT_WINDOW_MS;
    this.eventCapacity = options.eventCapacity ?? 200;
    this.state = { ...EMPTY_STATE, status: { ...EMPTY_STATE.status, detectorName: options.detectorName } };
  }

  get lock(): BoxLock | null {
    return this._lock;
  }

  getState = (): ObserverUiState => this.state;

  subscribe = (listener: Listener): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  /** The overlay's size in pixels, so boxes can be mapped into it. */
  setPreviewSize(width: number, height: number): void {
    this.previewWidth = width;
    this.previewHeight = height;
  }

  /** Publishes status fields without a frame (model loading note, detector name, link health, OFFLINE). */
  setStatus(update: (status: StatusUi) => StatusUi): void {
    this.publish({ status: update(this.state.status) });
  }

  /** Appends a plain status line to the event log. */
  log(text: string): void {
    this.addEvent({ t: this.clock(), text });
    this.publish({ events: this.events });
  }

  /**
   * One detection frame. A frame that carries its capture time is first fast-forwarded on the gray frames buffered
   * since (the native app's IffCoordinator.tick): its boxes are matched where the camera saw them and carried to where
   * the objects are now, the tracked boxes are moved to their motion positions, then the tracker matches the two.
   */
  tick(frame: FrameResult): void {
    const now = this.clock();
    let detections = frame.detections;
    if (frame.timestampMs !== undefined) {
      const seeds = this.motion.fastForward(frame.timestampMs, detections.map((d) => d.box));
      detections = detections.map((d, i) => ({ ...d, box: seeds[i].box }));
      for (const t of this.tracker.tracks) {
        const m = this.motion.box(t.id);
        if (m) this.tracker.moveTo(t.id, m.box);
      }
      const seen = this.tracker.update(detections, now);
      const seedByBox = new Map<BoxF, Seed>();
      detections.forEach((d, i) => {
        if (!seedByBox.has(d.box)) seedByBox.set(d.box, seeds[i]);
      });
      for (const t of seen) {
        const seed = seedByBox.get(t.box);
        if (seed) {
          this.motion.assign(t.id, seed);
          seedByBox.delete(t.box);
        }
      }
    } else {
      this.tracker.update(detections, now);
    }
    this.frameWidth = frame.width;
    this.frameHeight = frame.height;

    const fps = this.lastTick > 0 && now > this.lastTick ? 1000 / (now - this.lastTick) : 0;
    this.lastTick = now;
    this.publishTracks(now, true, { ...this.state.status, msPerFrame: Math.round(frame.msPerFrame ?? 0), fps, modelReady: true });
  }

  /** One 10 fps gray frame: moves every tracked box (the native app's motionTick); detections are left alone. */
  motionTick(gray: GrayFrame): void {
    this.motion.push(gray);
    this.frameWidth = gray.frameWidth;
    this.frameHeight = gray.frameHeight;
    if (this.motion.ids.size === 0 && !this._lock) return;
    this.publishTracks(this.clock(), false);
  }

  /**
   * The camera zoomed by `factor` (new zoom / old zoom). Digital zoom crops the frame around its centre, so every
   * tracked box, and the locked box, is scaled about the centre and stays on its object with its id and verdict; the
   * motion templates no longer match the new scale and are dropped until the next detection reseeds them.
   */
  rescale(factor: number): void {
    if (!(factor > 0) || factor === 1 || this.frameWidth <= 0 || this.frameHeight <= 0) return;
    const cx = this.frameWidth / 2;
    const cy = this.frameHeight / 2;
    const scale = (b: BoxF): BoxF => ({
      left: cx + (b.left - cx) * factor,
      top: cy + (b.top - cy) * factor,
      right: cx + (b.right - cx) * factor,
      bottom: cy + (b.bottom - cy) * factor,
    });
    this.tracker.mapBoxes(scale);
    this.motion.reset();
    if (this._lock) this._lock = { ...this._lock, rect: scale(this._lock.rect) };
    this.publishTracks(this.clock(), false);
  }

  /**
   * A class switched off in Settings: its rings go at once instead of coasting through the sticky window. A drone
   * under an open challenge keeps its lock, so the verdict still lands.
   */
  hideClasses(classes: ObjClass[]): void {
    if (!classes.length) return;
    this.tracker.forgetClasses(classes);
    this.publishTracks(this.clock(), false);
  }

  /** The boxes as the user sees them: the motion position when there is one, else the tracker's box. */
  private publishTracks(now: number, announce: boolean, status?: StatusUi): void {
    this.transform =
      this.previewWidth > 0 && this.previewHeight > 0 && this.frameWidth > 0 && this.frameHeight > 0
        ? frameTransform(this.frameWidth, this.frameHeight, this.previewWidth, this.previewHeight, this.previewMode)
        : { scale: 1, offsetX: 0, offsetY: 0 };

    // The window's verdict comes from challenge(); here an expired lock only stops pinning the box.
    if (this._lock && now >= this._lock.until) this._lock = null;

    const all = this.tracker.tracks;
    const keep = new Set(all.map((t) => t.id));
    if (this._lock) keep.add(this._lock.boxId);
    for (const id of [...this.iffById.keys()]) if (!keep.has(id)) this.iffById.delete(id);
    for (const id of [...this.announced]) if (!keep.has(id)) this.announced.delete(id);
    this.motion.retain(keep);

    // An unconfirmed drone (fewer matched frames than confirmHits) stays in the tracker but is not shown yet.
    const live = all.filter((t) => t.cls !== 'DRONE' || t.hits >= this.confirmHits || t.id === this._lock?.boxId);
    const shown = (id: number, fallback: BoxF) => toPreview(this.transform, this.motion.box(id)?.box ?? fallback);

    if (announce) {
      for (const t of live) {
        if (t.misses !== 0 || this.announced.has(t.id)) continue;
        this.announced.add(t.id);
        this.addEvent({ t: now, text: `${t.cls.toLowerCase()} detected · box ${t.id}`, trackId: t.id, cls: t.cls, score: t.score, box: shown(t.id, t.box) });
      }
    }

    const tracks: TrackUi[] = live.map((t) => ({
      id: t.id,
      cls: t.cls,
      box: shown(t.id, t.box),
      iff: this.iffById.get(t.id) ?? UNCHALLENGED,
      score: t.score,
    }));
    const lock = this._lock;
    if (lock && !live.some((t) => t.id === lock.boxId)) {
      tracks.push({
        id: lock.boxId,
        cls: lock.cls,
        box: shown(lock.boxId, lock.rect),
        iff: this.iffById.get(lock.boxId) ?? { kind: 'CHALLENGED', until: lock.until, windowMs: this.windowMs },
        score: 0,
      });
    }
    this.publish(status ? { tracks, events: this.events, status } : { tracks, events: this.events });
  }

  /** Tap -> lock -> challenge -> answer -> re-check -> verdict. Returns false when the tap is refused. */
  challenge(boxId: number): boolean {
    const now = this.clock();
    if (this._lock && now < this._lock.until) return false;
    // A drone must be confirmed (drawn) first; a person is drawn at once. A bird is never challenged.
    const track = this.tracker.tracks.find(
      (t) => t.id === boxId && isIdentifiable(t.cls) && (t.cls !== 'DRONE' || t.hits >= this.confirmHits),
    );
    if (!track) return false;

    const until = now + this.windowMs;
    const rect = this.motion.box(boxId)?.box ?? track.box;
    const lock: BoxLock = { boxId, cls: track.cls, rect, until };
    this._lock = lock;
    this.iffById.set(boxId, { kind: 'CHALLENGED', until, windowMs: this.windowMs });
    const previewBox = toPreview(this.transform, rect);
    this.addEvent({ t: now, text: `challenge sent · box ${boxId}` });
    this.publish({
      tracks: this.state.tracks.map((t) => (t.id === boxId ? { ...t, iff: this.iffById.get(boxId)! } : t)),
      events: this.events,
    });

    const generation = this.generation;
    const controller = new AbortController();
    this.inFlight?.abort();
    this.inFlight = controller;
    const t0 = this.monotonic();
    let settled = false;

    const finish = (verdict: VerdictKind, reason: string | null, droneId: string | null, rssiDbm: number | null, ms: number | null, text: string) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      controller.abort();
      if (this.inFlight === controller) this.inFlight = null;
      if (generation !== this.generation) return;
      const t = this.clock();
      const iff: IffState = { kind: 'VERDICT', verdict, reason, droneId, rssiDbm, roundTripMs: ms };
      this.iffById.set(boxId, iff);
      if (this._lock === lock) this._lock = null;
      const row: SignalUi = { droneId, verdict, reason, rssiDbm, roundTripMs: ms, t };
      this.addEvent({ t, text, trackId: boxId, cls: track.cls, score: track.score, box: previewBox, verdict, reason, droneId, rssiDbm, roundTripMs: ms });
      this.publish({
        tracks: this.state.tracks.map((tr) => (tr.id === boxId ? { ...tr, iff } : tr)),
        signal: [row, ...this.state.signal].slice(0, 20),
        events: this.events,
        status: { ...this.state.status, rssiDbm: rssiDbm ?? this.state.status.rssiDbm },
      });
    };

    const timer = setTimeout(() => finish('UNKNOWN', 'no response', null, null, null, `UNKNOWN · no response · box ${boxId}`), this.windowMs);

    const emit = (event: SignalEvent) => {
      if (settled || generation !== this.generation) return;
      if (event.type === 'status') {
        this.log(event.text);
        return;
      }
      const roundTrip = Math.round(this.monotonic() - t0);
      const result = this.reChecks.check(event, roundTrip);
      let { verdict, reason } = result;
      if (verdict === 'FRIEND' && this.acceptedDroneIds.length > 0 && !this.acceptedDroneIds.includes(event.droneId ?? '')) {
        verdict = 'FOE';
        reason = track.cls === 'PERSON' ? 'unknown person id' : 'unknown drone id';
      }
      for (const note of result.notes) if (note.startsWith('phone')) this.log(`${note} · box ${boxId} · round trip ${roundTrip} ms`);
      finish(verdict, reason, event.droneId, event.rssiDbm, event.responseMs, verdictText(verdict, reason, event.droneId, event.rssiDbm, event.responseMs, boxId));
    };

    const req: ChallengeRequest = { boxId, challengerId: this.challengerId, t: Math.floor(now / 1000) };
    this.signalSource.challenge(req, emit, controller.signal).catch((e: unknown) => {
      if (settled || generation !== this.generation) return;
      this.log(`gadget error · ${e instanceof Error ? e.message : String(e)}`);
      finish('UNKNOWN', 'no response', null, null, null, `UNKNOWN · no response · box ${boxId}`);
    });
    return true;
  }

  reset(): void {
    this.generation++;
    this.inFlight?.abort();
    this.inFlight = null;
    this.tracker.reset();
    this.motion.reset();
    this.reChecks.reset();
    this.events = [];
    this.iffById.clear();
    this.announced.clear();
    this._lock = null;
    this.publish({ tracks: [], signal: [], events: [] });
  }

  close(): void {
    this.inFlight?.abort();
    this.signalSource.close();
    this.listeners.clear();
  }

  private addEvent(event: EventUi): void {
    this.events = [event, ...this.events].slice(0, this.eventCapacity);
  }

  private publish(patch: Partial<ObserverUiState>): void {
    this.state = { ...this.state, ...patch };
    for (const l of this.listeners) l(this.state);
  }
}

/** The event-log line for a verdict, in the native app's DemoScenario wording. */
export function verdictText(
  verdict: VerdictKind,
  reason: string | null,
  droneId: string | null,
  rssiDbm: number | null,
  ms: number | null,
  boxId: number,
): string {
  const parts: (string | null)[] =
    verdict === 'FRIEND'
      ? ['FRIEND', droneId, rssiDbm !== null ? `${rssiDbm} dBm` : null, ms !== null ? `${ms} ms` : null]
      : [verdict, reason ?? 'no response', droneId ?? `box ${boxId}`];
  return parts.filter((p): p is string => !!p).join(' · ');
}
