/**
 * Fire-and-forget delivery to the laptop dashboard (net/EventClient.kt): every call returns at once; events queue
 * (64, oldest dropped), frames keep only the latest, one request in flight per queue, 500 ms timeout. Failures are
 * counted, never thrown or retried: the dashboard is a side-car, not the demo. Android needs cleartext HTTP for the
 * plain `http://` LAN target (app.config.ts sets `usesCleartextTraffic`).
 */
export class EventClient {
  sent = 0;
  failed = 0;
  private events: { path: string; body: string }[] = [];
  private frame: string | null = null;
  private sendingEvents = false;
  private sendingFrame = false;
  private closed = false;

  constructor(private readonly baseUrl: string, private readonly timeoutMs = 500) {}

  post(body: unknown): void {
    this.enqueue('/api/events', JSON.stringify(body));
  }

  /** Clears the dashboard alongside the phone's own reset; queued behind earlier events. */
  reset(): void {
    this.enqueue('/api/reset', '{}');
  }

  postFrame(body: unknown): void {
    if (this.closed) return;
    this.frame = JSON.stringify(body);
    if (!this.sendingFrame) void this.drainFrame();
  }

  close(): void {
    this.closed = true;
    this.events = [];
    this.frame = null;
  }

  private enqueue(path: string, body: string): void {
    if (this.closed) return;
    this.events.push({ path, body });
    if (this.events.length > 64) this.events.shift();
    if (!this.sendingEvents) void this.drainEvents();
  }

  private async drainEvents(): Promise<void> {
    this.sendingEvents = true;
    while (!this.closed && this.events.length) {
      const next = this.events.shift()!;
      await this.send(next.path, next.body);
    }
    this.sendingEvents = false;
  }

  private async drainFrame(): Promise<void> {
    this.sendingFrame = true;
    while (!this.closed && this.frame !== null) {
      const body = this.frame;
      this.frame = null;
      await this.send('/api/frame', body);
    }
    this.sendingFrame = false;
  }

  private async send(path: string, body: string): Promise<void> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const r = await fetch(this.baseUrl.replace(/\/+$/, '') + path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
        signal: controller.signal,
      });
      if (r.ok) this.sent++;
      else this.failed++;
    } catch {
      this.failed++;
    } finally {
      clearTimeout(timer);
    }
  }
}
