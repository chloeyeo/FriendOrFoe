/**
 * The three contracts of the native app (README section 3), ported field for field from
 * `app/src/main/java/com/chloeyeo/friendorfoe/{detect,signal,session}`. The native app's files on `main` stay the
 * source of truth; this file follows them so the dashboard, the gadget and the eval tools see the same shapes.
 */

/** Pixel rectangle, top-left origin; left/top inclusive, right/bottom exclusive (detect/BoxF.kt). */
export interface BoxF {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/** PERSON and DRONE as in the native app; BIRD is this app's addition, so a bird is shown but never challenged. */
export type ObjClass = 'PERSON' | 'DRONE' | 'BIRD';

/** What a tap can challenge: a drone, or a person carrying a responder (the same BLE beacon). Never a bird. */
export function isIdentifiable(cls: ObjClass): boolean {
  return cls === 'DRONE' || cls === 'PERSON';
}

export type VerdictKind = 'FRIEND' | 'FOE' | 'UNKNOWN';

/** One detected object after the class map: class, score (0..1), box in frame pixels. */
export interface Detection {
  cls: ObjClass;
  score: number;
  box: BoxF;
}

/** One detector row before the class map (detect/RawDetection.kt): raw COCO label id from RT-DETR v2. */
export interface RawDetection {
  labelId: number;
  score: number;
  box: BoxF;
  label?: string;
  /** The model that produced the row, when the detector runs several (Core ML on iPhone, dual ClikaRT on Android). */
  model?: string;
}

/** The IFF state drawn on a drone box (session/ObserverUiState.kt). */
export type IffState =
  | { kind: 'UNCHALLENGED' }
  | { kind: 'CHALLENGED'; until: number; windowMs: number }
  | {
      kind: 'VERDICT';
      verdict: VerdictKind;
      reason: string | null;
      droneId: string | null;
      rssiDbm: number | null;
      roundTripMs: number | null;
    };

export const DEFAULT_WINDOW_MS = 10_000;

export const UNCHALLENGED: IffState = { kind: 'UNCHALLENGED' };

/** One drawn box: tracker id (what a tap challenges), class, box in preview pixels, IFF state, latest score. */
export interface TrackUi {
  id: number;
  cls: ObjClass;
  box: BoxF;
  iff: IffState;
  score: number;
}

/** The status strip: detector and timing numbers, runtime and link health. */
export interface StatusUi {
  detectorName: string;
  msPerFrame: number;
  fps: number;
  modelReady: boolean;
  offline: boolean;
  gadgetConnected: boolean;
  rssiDbm: number | null;
  /** `model loading… 3 s`, `load 3.2 s · warm 1400 ms`, or a ClikaRT code name such as `LICENSE_FAILED`. */
  modelNote: string | null;
}

/** One row of the gadget panel: the gadget's latest answer after the phone's re-checks. */
export interface SignalUi {
  droneId: string | null;
  verdict: VerdictKind | null;
  reason: string | null;
  rssiDbm: number | null;
  roundTripMs: number | null;
  /** Wall-clock ms when the answer arrived (the native app stores an age; an absolute time ages correctly). */
  t: number;
}

/**
 * One event log row; [t] is wall-clock ms. A plain status line has only `t` and `text`; a detection fills
 * `trackId`/`cls`/`score`/`box`; a verdict also fills `verdict`, `reason`, `droneId`, `rssiDbm`, `roundTripMs`.
 */
export interface EventUi {
  t: number;
  text: string;
  trackId?: number | null;
  cls?: ObjClass | null;
  score?: number | null;
  box?: BoxF | null;
  verdict?: VerdictKind | null;
  reason?: string | null;
  droneId?: string | null;
  rssiDbm?: number | null;
  roundTripMs?: number | null;
}

/** The only thing the UI renders. */
export interface ObserverUiState {
  tracks: TrackUi[];
  status: StatusUi;
  /** Latest answers, newest first. */
  signal: SignalUi[];
  /** Event log rows, newest first. */
  events: EventUi[];
}

export const DEFAULT_STATUS: StatusUi = {
  detectorName: 'none',
  msPerFrame: 0,
  fps: 0,
  modelReady: false,
  offline: true,
  gadgetConnected: false,
  rssiDbm: null,
  modelNote: null,
};

export const EMPTY_STATE: ObserverUiState = { tracks: [], status: DEFAULT_STATUS, signal: [], events: [] };

/** One frame's worth of detector output, in the pixel space of a `width` x `height` upright frame. */
export interface FrameResult {
  width: number;
  height: number;
  detections: Detection[];
  /** Inference time of this frame, when the detector measured it. */
  msPerFrame?: number;
  /** When the frame was captured, on the gray frames' clock: a late detection is fast-forwarded from here. */
  timestampMs?: number;
}
