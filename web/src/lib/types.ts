// Shapes of GET /api/state (laptop/dashboard/app.py). Events and frames are what the phone posts
// (app/.../net/EventJson.kt): boxes are preview pixels, [left, top, right, bottom].

export type Verdict = 'FRIEND' | 'FOE' | 'UNKNOWN';
export type Tone = 'amber' | 'green' | 'red' | 'neutral';
export type Box = [number, number, number, number];

export interface DashEvent {
	/** Unique until the next reset, assigned by the server. */
	id: number;
	t: number;
	/** null on a plain status line ("challenge sent …"); then only `text` matters. */
	class: string | null;
	box: Box | null;
	iffStatus?: Verdict | null;
	/** Older posts used `verdict` instead of `iffStatus`. */
	verdict?: Verdict | null;
	droneId?: string | null;
	reason?: string | null;
	text?: string | null;
	trackId?: number | null;
	score?: number | null;
	rssiDbm?: number | null;
	roundTripMs?: number | null;
	receivedAt: number;
}

export interface FrameTrack {
	id: number;
	class: string;
	box: Box;
	tag: string;
	tone: Tone;
	detail: string | null;
}

export interface Frame {
	t: number;
	/** The preview the boxes are measured in. */
	width: number;
	height: number;
	tracks: FrameTrack[];
	receivedAt: number;
}

export interface Counts {
	drones: number;
	people: number;
	friend: number;
	foe: number;
	unknown: number;
	events: number;
}

export interface DashboardState {
	counts: Counts;
	events: DashEvent[];
	frame: Frame | null;
	thumbnail: { receivedAt: number; url: string } | null;
	received: number;
	/** Seconds (float) of the last event, 0 before any. */
	updatedAt: number;
	serverTime: number;
}
