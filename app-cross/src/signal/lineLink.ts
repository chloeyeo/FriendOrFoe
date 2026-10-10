export interface LinkHandlers {
  onLine(line: string): void;
  /** The line path to the reader came up or went down; `error` says why it went down. */
  onState(connected: boolean, error: string | null): void;
}

/**
 * One text line in each direction between the phone and the reader (the reader's serial interface v2 is line based,
 * LF terminated). Two ways to reach it: the reader on the phone's own USB port (Android OTG, `src/app/usbLink.ts`),
 * or the reader on the laptop's USB port behind `tools/reader_bridge.py` ([BridgeLink]: any phone on the hotspot LAN,
 * including the iPhone, which has no USB serial).
 */
export interface LineLink {
  readonly name: string;
  /** Opens the path; resolves with a human name for the gadget panel. */
  open(): Promise<string>;
  write(line: string): Promise<void>;
  isOpen(): boolean;
  close(): void;
}

interface BridgeLines {
  bridge: string;
  seq: number;
  lines: string[];
  serial: { port: string | null; connected: boolean; error: string | null };
}

type Fetch = typeof fetch;

/**
 * The reader behind `tools/reader_bridge.py` on the laptop: `GET /lines?after=<seq>&wait=<ms>` long-polls the
 * reader's output, `POST /line` sends one command (the bridge only forwards QUERY, STATUS and HELP). The bridge
 * restarting shows up as a new `bridge` id, and the poll then starts over from its current line.
 */
export class BridgeLink implements LineLink {
  readonly name = 'bridge';
  private readonly base: string;
  private controller: AbortController | null = null;
  private seq: number | null = null;
  private bridgeId: string | null = null;
  private up = false;
  private why: string | null = null;

  constructor(
    url: string,
    private readonly h: LinkHandlers,
    private readonly http: Fetch = (...args) => fetch(...args),
    private readonly waitMs = 2000,
  ) {
    this.base = url.trim().replace(/\/+$/, '');
  }

  async open(): Promise<string> {
    this.close();
    const controller = new AbortController();
    this.controller = controller;
    const first = await this.poll(controller, null, 0);
    this.seq = first.seq;
    this.bridgeId = first.bridge;
    this.setUp(first.serial.connected, first.serial.error);
    void this.loop(controller);
    return `${this.base.replace(/^https?:\/\//, '')} · ${first.serial.port ?? 'no reader port'}`;
  }

  async write(line: string): Promise<void> {
    if (!this.controller) throw new Error('bridge not open');
    const res = await this.request(`${this.base}/line`, this.controller, 3000, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ line }),
    });
    if (!res.ok) throw new Error(`bridge refused the line (${res.status}: ${(await res.text()).slice(0, 80)})`);
  }

  isOpen(): boolean {
    return this.controller !== null && this.up;
  }

  close(): void {
    this.controller?.abort();
    this.controller = null;
    this.why = null;
    if (this.up) {
      this.up = false;
      this.h.onState(false, null);
    }
  }

  private async loop(controller: AbortController): Promise<void> {
    while (!controller.signal.aborted) {
      try {
        const page = await this.poll(controller, this.seq, this.waitMs);
        if (controller.signal.aborted) return;
        if (page.bridge !== this.bridgeId) {
          // A restarted bridge numbers lines from zero; its old lines are not ours.
          this.bridgeId = page.bridge;
          this.seq = page.seq;
        } else {
          this.seq = page.seq;
          page.lines.forEach((line) => this.h.onLine(line));
        }
        this.setUp(page.serial.connected, page.serial.error);
      } catch (e) {
        if (controller.signal.aborted) return;
        this.setUp(false, `bridge unreachable · ${(e as Error).message}`);
        await new Promise((r) => setTimeout(r, 1000));
      }
    }
  }

  private async poll(controller: AbortController, after: number | null, wait: number): Promise<BridgeLines> {
    const query = after === null ? '' : `?after=${after}&wait=${wait}`;
    const res = await this.request(`${this.base}/lines${query}`, controller, wait + 3000);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const body = (await res.json()) as BridgeLines;
    if (typeof body?.bridge !== 'string' || typeof body.seq !== 'number' || !Array.isArray(body.lines) || !body.serial) {
      throw new Error('not a reader bridge');
    }
    return body;
  }

  /** fetch with a deadline that also ends when the link closes. */
  private async request(url: string, link: AbortController, timeoutMs: number, init: RequestInit = {}): Promise<Response> {
    const controller = new AbortController();
    const abort = () => controller.abort();
    link.signal.addEventListener('abort', abort, { once: true });
    const timer = setTimeout(abort, timeoutMs);
    try {
      return await this.http(url, { ...init, signal: controller.signal });
    } finally {
      clearTimeout(timer);
      link.signal.removeEventListener('abort', abort);
    }
  }

  private setUp(connected: boolean, error: string | null): void {
    const why = connected ? null : (error ?? 'reader not on the laptop USB port');
    if (connected === this.up && why === this.why) return;
    this.up = connected;
    this.why = why;
    this.h.onState(connected, why);
  }
}
