import { frameTransform, toPreview, type FrameTransform } from '../camera/frameTransform';
import {
  DEFAULT_WINDOW_MS,
  EMPTY_STATE,
  UNCHALLENGED,
  type BoxF,
  type EventUi,
  type FrameResult,
  type IffState,
  type ObserverUiState,
  type SignalUi,
  type StatusUi,
  type TrackUi,
  type VerdictKind,
} from '../core/types';
import { ReChecks } from '../signal/reChecks';
import type { ChallengeRequest, SignalEvent, SignalSource } from '../signal/signal';
import { Tracker } from '../track/tracker';

/** The tapped box frozen for the challenge window (track/BoxLock.kt); `rect` is in frame pixels. */
export interface BoxLock {
  boxId: number;
  rect: BoxF;
  until: number;
}

export interface CoordinatorOptions {
  detectorName: string;
  signalSource: SignalSource;
  reChecks?: ReChecks;
  tracker?: Tracker;
  /** Wall clock, ms (locks, countdowns, event times). */
  clock?: () => number;
  /** Monotonic ms for the phone-side round trip. */
  monotonic?: () => number;
  challengerId?: number;
  /** Drone ids this observer accepts as FRIEND; empty accepts any id the gadget verified. */
  acceptedDroneIds?: string[];
  /** `fill` for the CameraX preview (FILL_CENTER), `fit` for a letterboxed view. */
  previewMode?: 'fill' | 'fit';
  windowMs?: number;
  eventCapacity?: number;
}

type Listener = (state: ObserverUiState) => void;

/**
 * The session (session/IffCoordinator.kt, plus what plans 02-03 and Phase 5 add): frames in, [ObserverUiState] out.
 *
 * - `tick(frame)`: detections -> sticky tracker -> preview coordinates; new tracks are logged as detection rows; the
 *   locked box stays on screen at its frozen rect while the detector has lost it (DET-06).
 * - `challenge(boxId)`: refuses while a window is open or for anything but a live drone track; locks the box for
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
  readonly reChecks: ReChecks;
  private readonly clock: () => number;
  private readonly monotonic: () => number;
  private readonly windowMs: number;
  private readonly eventCapacity: number;
  /** `fill` for the CameraX preview (FILL_CENTER); `fit` letterboxes the simulated frame so all of it is visible. */
  previewMode: 'fill' | 'fit';
  private previewWidth = 0;
  private previewHeight = 0;
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

  constructor(options: CoordinatorOptions) {
    this.signalSource = options.signalSource;
    this.reChecks = options.reChecks ?? new ReChecks();
    this.tracker = options.tracker ?? new Tracker();
    this.clock = options.clock ?? (() => Date.now());
    this.monotonic = options.monotonic ?? (() => (globalThis.performance?.now ? globalThis.performance.now() : Date.now()));
    this.challengerId = options.challengerId ?? 7;
    this.acceptedDroneIds = options.acceptedDroneIds ?? [];
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

  tick(frame: FrameResult): void {
    const now = this.clock();
    this.tracker.update(frame.detections, now);
    this.transform =
      this.previewWidth > 0 && this.previewHeight > 0
        ? frameTransform(frame.width, frame.height, this.previewWidth, this.previewHeight, this.previewMode)
        : { scale: 1, offsetX: 0, offsetY: 0 };

    // The window's verdict comes from challenge(); here an expired lock only stops pinning the box.
    if (this._lock && now >= this._lock.until) this._lock = null;

    const live = this.tracker.tracks;
    const keep = new Set(live.map((t) => t.id));
    if (this._lock) keep.add(this._lock.boxId);
    for (const id of [...this.iffById.keys()]) if (!keep.has(id)) this.iffById.delete(id);
    for (const id of [...this.announced]) if (!keep.has(id)) this.announced.delete(id);

    for (const t of live) {
      if (t.misses !== 0 || this.announced.has(t.id)) continue;
      this.announced.add(t.id);
      const name = t.cls === 'DRONE' ? 'drone' : 'person';
      this.addEvent({ t: now, text: `${name} detected · box ${t.id}`, trackId: t.id, cls: t.cls, score: t.score, box: toPreview(this.transform, t.box) });
    }

    const tracks: TrackUi[] = live.map((t) => ({
      id: t.id,
      cls: t.cls,
      box: toPreview(this.transform, t.box),
      iff: this.iffById.get(t.id) ?? UNCHALLENGED,
      score: t.score,
    }));
    const lock = this._lock;
    if (lock && !live.some((t) => t.id === lock.boxId)) {
      tracks.push({
        id: lock.boxId,
        cls: 'DRONE',
        box: toPreview(this.transform, lock.rect),
        iff: this.iffById.get(lock.boxId) ?? { kind: 'CHALLENGED', until: lock.until, windowMs: this.windowMs },
        score: 0,
      });
    }

    const fps = this.lastTick > 0 && now > this.lastTick ? 1000 / (now - this.lastTick) : 0;
    this.lastTick = now;
    this.publish({
      tracks,
      events: this.events,
      status: { ...this.state.status, msPerFrame: Math.round(frame.msPerFrame ?? 0), fps, modelReady: true },
    });
  }

  /** Tap -> lock -> challenge -> answer -> re-check -> verdict. Returns false when the tap is refused. */
  challenge(boxId: number): boolean {
    const now = this.clock();
    if (this._lock && now < this._lock.until) return false;
    const track = this.tracker.tracks.find((t) => t.id === boxId && t.cls === 'DRONE');
    if (!track) return false;

    const until = now + this.windowMs;
    const lock: BoxLock = { boxId, rect: track.box, until };
    this._lock = lock;
    this.iffById.set(boxId, { kind: 'CHALLENGED', until, windowMs: this.windowMs });
    const previewBox = toPreview(this.transform, track.box);
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
      this.addEvent({ t, text, trackId: boxId, cls: 'DRONE', score: track.score, box: previewBox, verdict, reason, droneId, rssiDbm, roundTripMs: ms });
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
        reason = 'unknown drone id';
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
