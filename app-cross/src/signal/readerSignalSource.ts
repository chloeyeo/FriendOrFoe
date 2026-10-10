import type { LineLink, LinkHandlers } from './lineLink';
import {
  READER_INTERFACE_VERSION,
  answerFromResult,
  encodeQuery,
  encodeStatus,
  parseReaderLine,
  refusalText,
  requestIdFor,
  type ReaderEvent,
  type ReaderResult,
  type ReaderState,
  type ReaderStatus,
} from './readerProtocol';
import type { ChallengeRequest, SignalEvent, SignalSource } from './signal';

export interface GadgetLinkState {
  connected: boolean;
  /** `reader v2 · key set · radio ready · IDLE` from the last STATUS, or why the reader is not usable. */
  note: string | null;
}

interface Pending {
  requestId: string;
  emit: (event: SignalEvent) => void;
  settle: (error?: Error) => void;
  /** QUERY written and not refused as BUSY. */
  sent: boolean;
  busy: number;
  nonceHex: string | null;
  droneId: number | null;
  timer: ReturnType<typeof setTimeout> | null;
}

/** Reader timeout (3 s) plus the serial path; then ask STATUS for the last result in case the RESULT line was lost. */
const RECOVER_AFTER_MS = 4500;
/** The reader refuses queries for 3 s from the last query start; a queued query waits this long for STATE IDLE. */
const COOLDOWN_WAIT_MS = 3500;
const HANDSHAKE_TRIES = 3;
const HANDSHAKE_EVERY_MS = 1500;

/**
 * The BLE IFF reader (Ihan's ESP32-C3, firmware/reader) as the phone's signal source, over either line link: USB on
 * the phone or the laptop bridge. One tap is one `QUERY <id>`; the reader broadcasts the challenge, waits up to 3 s
 * for the drone's HMAC response and prints a RESULT, which becomes the answer the coordinator re-checks.
 *
 * On connect it asks `STATUS` and accepts only a reader speaking interface v2. Between queries the reader holds a
 * 3 s cooldown; a tap inside it waits for the reader's STATE IDLE instead of being refused. A RESULT line lost on
 * the way is recovered from a later STATUS snapshot, the same way the laptop GUI does it.
 */
export class ReaderSignalSource implements SignalSource {
  readonly kind = 'USB_GADGET' as const;
  private readonly link: LineLink | null;
  private pending: Pending | null = null;
  private linkUp = false;
  private compatible = false;
  private reader: { bootId: string | null; state: ReaderState | null; keyConfigured: boolean; radioReady: boolean } = {
    bootId: null,
    state: null,
    keyConfigured: false,
    radioReady: false,
  };
  private problem: string | null = null;
  private statusSeen = false;
  private salt = Math.floor(Math.random() * 1679616);
  private handshake: ReturnType<typeof setTimeout> | null = null;
  private state: GadgetLinkState = { connected: false, note: null };

  constructor(
    makeLink: (h: LinkHandlers) => LineLink | null,
    private readonly onLinkState: (state: GadgetLinkState) => void = () => {},
  ) {
    this.link = makeLink({ onLine: (line) => this.onLine(line), onState: (up, error) => this.onLinkUp(up, error) });
    if (!this.link) this.problem = 'reader link not available in this build';
    this.publish();
  }

  get linkState(): GadgetLinkState {
    return this.state;
  }

  /** Opens the link and checks, with STATUS, that a v2 reader is on the other end. */
  async connect(): Promise<string> {
    if (!this.link) throw new Error('reader link not available in this build');
    this.compatible = false;
    this.statusSeen = false;
    this.problem = "waiting for the reader's STATUS";
    let name: string;
    try {
      name = await this.link.open();
    } catch (e) {
      this.problem = (e as Error).message;
      this.publish();
      throw e;
    }
    // The bridge can be up while the laptop has no reader on its USB port; its state already said why. The link's
    // own "up" usually started the STATUS check already.
    this.linkUp = this.link.isOpen();
    if (this.linkUp && !this.statusSeen && this.handshake === null) this.askStatus(HANDSHAKE_TRIES);
    this.publish();
    return name;
  }

  async challenge(req: ChallengeRequest, emit: (event: SignalEvent) => void, signal: AbortSignal): Promise<void> {
    if (!this.link || !this.linkUp) throw new Error('reader not connected');
    if (!this.compatible) throw new Error(this.problem ?? 'reader not ready');
    this.drop(this.pending);
    await new Promise<void>((resolve, reject) => {
      const p: Pending = {
        requestId: requestIdFor(req, this.salt++),
        emit,
        settle: (error) => {
          if (this.pending !== p) return;
          this.drop(p);
          signal.removeEventListener('abort', onAbort);
          if (error) reject(error);
          else resolve();
        },
        sent: false,
        busy: 0,
        nonceHex: null,
        droneId: null,
        timer: null,
      };
      const onAbort = () => p.settle();
      this.pending = p;
      signal.addEventListener('abort', onAbort, { once: true });
      if (this.reader.state === 'IDLE') this.send(p);
      else this.waitForIdle(p);
    });
  }

  close(): void {
    this.pending?.settle();
    this.clearHandshake();
    this.link?.close();
    this.linkUp = false;
    this.compatible = false;
    this.publish();
  }

  private send(p: Pending): void {
    if (this.pending !== p) return;
    p.sent = true;
    this.arm(p, RECOVER_AFTER_MS, () => this.recover(p));
    p.emit({ type: 'status', text: `QUERY ${p.requestId} → reader` });
    this.link!.write(encodeQuery(p.requestId)).catch((e: Error) => p.settle(e));
  }

  private waitForIdle(p: Pending): void {
    p.sent = false;
    p.emit({ type: 'status', text: `reader ${this.reader.state?.toLowerCase() ?? 'busy'}; query waits for it` });
    this.arm(p, COOLDOWN_WAIT_MS, () => this.send(p));
  }

  /** No RESULT in time: the STATUS snapshot carries the last result, which is ours if the line was lost. */
  private recover(p: Pending): void {
    if (this.pending !== p) return;
    p.emit({ type: 'status', text: 'no RESULT line yet; asking the reader for STATUS' });
    this.link!.write(encodeStatus(p.requestId)).catch(() => {});
  }

  private onLine(line: string): void {
    const e = parseReaderLine(line);
    if (e) this.onEvent(e);
  }

  private onEvent(e: ReaderEvent): void {
    const p = this.pending;
    switch (e.event) {
      case 'DOWNLOAD_MODE':
        this.compatible = false;
        this.problem = 'reader is in download mode: release BOOT, then press RESET';
        p?.settle(new Error(this.problem));
        break;
      case 'STATUS':
        this.onStatus(e);
        if (p && p.sent && e.lastResult?.requestId === p.requestId) this.onResult(p, e.lastResult);
        break;
      case 'STATE':
        if (this.reader.bootId !== null && e.bootId !== this.reader.bootId) {
          this.rebooted();
          return;
        }
        this.reader.state = e.state;
        if (e.state === 'IDLE' && p && !p.sent) this.send(p);
        break;
      case 'ACK':
        if (!p || e.requestId !== p.requestId) return;
        if (e.accepted) {
          p.emit({ type: 'status', text: e.reason === 'DUPLICATE' ? 'reader repeats its last result' : 'reader broadcasting the challenge' });
        } else if (e.reason === 'BUSY' && p.busy < 3) {
          p.busy++;
          this.waitForIdle(p);
        } else {
          p.settle(new Error(refusalText(e.reason)));
        }
        break;
      case 'TX_CHALLENGE':
      case 'RX_RESPONSE':
        if (!p || e.requestId !== p.requestId) return;
        if (e.event === 'TX_CHALLENGE' || e.result === 'FRIEND' || e.result === 'AUTH_FAILED') {
          p.nonceHex = e.nonceHex ?? p.nonceHex;
          p.droneId = e.droneId ?? p.droneId;
        }
        p.emit({ type: 'status', text: packetText(e.event, e.result, e.nonceHex) });
        break;
      case 'RESULT':
        if (p && p.sent && e.requestId === p.requestId) this.onResult(p, e);
        break;
    }
    this.publish();
  }

  private onResult(p: Pending, r: ReaderResult): void {
    p.emit(answerFromResult(r, p));
    p.settle();
  }

  private onStatus(s: ReaderStatus): void {
    this.statusSeen = true;
    if (s.role !== 'reader') {
      this.compatible = false;
      this.problem = s.role === 'drone' ? 'this port is the drone responder, not the reader' : `not a reader (role '${s.role}')`;
      this.clearHandshake();
      return;
    }
    if (s.interfaceVersion !== READER_INTERFACE_VERSION || !s.manualQuery) {
      this.compatible = false;
      this.problem = `reader speaks interface v${s.interfaceVersion ?? '?'}; this app needs v${READER_INTERFACE_VERSION}`;
      this.clearHandshake();
      return;
    }
    if (this.reader.bootId !== null && s.bootId !== null && s.bootId !== this.reader.bootId) this.pending?.settle(new Error('reader rebooted'));
    this.reader = { bootId: s.bootId, state: s.state, keyConfigured: s.keyConfigured, radioReady: s.radioReady };
    this.compatible = true;
    this.problem = null;
    this.clearHandshake();
  }

  private rebooted(): void {
    this.pending?.settle(new Error('reader rebooted'));
    this.compatible = false;
    this.reader.bootId = null;
    this.problem = 'reader rebooted; checking it again';
    this.askStatus(HANDSHAKE_TRIES);
    this.publish();
  }

  private askStatus(tries: number): void {
    this.clearHandshake();
    if (!this.link || !this.linkUp) return;
    if (tries <= 0) {
      this.problem = 'no STATUS from the reader: is this the reader board with interface v2 firmware?';
      this.publish();
      return;
    }
    // Armed before the write: the STATUS reply can arrive inside it, and its handler clears this timer.
    this.handshake = setTimeout(() => this.askStatus(tries - 1), HANDSHAKE_EVERY_MS);
    this.link.write(encodeStatus(`hs-${(this.salt++).toString(36)}`)).catch(() => {});
  }

  private clearHandshake(): void {
    if (this.handshake) clearTimeout(this.handshake);
    this.handshake = null;
  }

  private onLinkUp(up: boolean, error: string | null): void {
    const wasUp = this.linkUp;
    this.linkUp = up;
    if (!up) {
      this.compatible = false;
      this.problem = error ?? 'reader link closed';
      this.pending?.settle(new Error(`reader link lost${error ? ` · ${error}` : ''}`));
      this.clearHandshake();
    } else if (!wasUp || !this.compatible) {
      // The bridge saw the reader come back on the laptop: check it again.
      this.askStatus(HANDSHAKE_TRIES);
    }
    this.publish();
  }

  private arm(p: Pending, ms: number, run: () => void): void {
    if (p.timer) clearTimeout(p.timer);
    p.timer = setTimeout(run, ms);
  }

  private drop(p: Pending | null): void {
    if (!p) return;
    if (p.timer) clearTimeout(p.timer);
    p.timer = null;
    if (this.pending === p) this.pending = null;
  }

  private publish(): void {
    const connected = this.linkUp && this.compatible;
    const note = connected ? readerText(this.reader) : this.problem;
    if (connected === this.state.connected && note === this.state.note) return;
    this.state = { connected, note };
    this.onLinkState(this.state);
  }
}

function readerText(r: { bootId: string | null; state: ReaderState | null; keyConfigured: boolean; radioReady: boolean }): string {
  return [
    `reader v${READER_INTERFACE_VERSION}`,
    r.keyConfigured ? 'key set' : 'NO KEY',
    r.radioReady ? 'radio ready' : 'RADIO DOWN',
    r.state ?? 'state ?',
    r.bootId ? `boot ${r.bootId.slice(0, 6)}` : null,
  ]
    .filter(Boolean)
    .join(' · ');
}

function packetText(event: 'TX_CHALLENGE' | 'RX_RESPONSE', result: string, nonceHex: string | null): string {
  const nonce = nonceHex ? ` · nonce ${nonceHex.slice(0, 8)}…` : '';
  if (event === 'TX_CHALLENGE') return result === 'BROADCAST_STARTED' ? `challenge on air${nonce}` : `challenge not sent · ${result}`;
  switch (result) {
    case 'FRIEND':
      return `response verified${nonce}`;
    case 'AUTH_FAILED':
      return `response with a bad tag${nonce}`;
    case 'IGNORED_NONCE_MISMATCH':
      return `ignored a response to another nonce${nonce} (replay?)`;
    case 'IGNORED_LATE_RESPONSE':
      return `ignored a late response${nonce}`;
    default:
      return `response · ${result.toLowerCase()}`;
  }
}
