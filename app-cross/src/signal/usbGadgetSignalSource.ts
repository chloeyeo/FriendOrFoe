import { FofUsb } from '../../modules/fof-native';
import { encodeChallenge, encodeStatusRequest, parseLine } from './gadgetLineCodec';
import type { ChallengeRequest, SignalEvent, SignalSource } from './signal';

export interface GadgetLinkState {
  connected: boolean;
  /** `fw 0.1 · battery 3.9 V · mode HONEST` from the last status line, or the last error. */
  note: string | null;
}

/**
 * The reader gadget over USB (signal/UsbGadgetSignalSource.kt plan): writes `{"cmd":"challenge",...}` and reports
 * every line the gadget sends back until the answer. Status lines that arrive outside a challenge (the reply to
 * `{"cmd":"status"}` on connect) go to [onLinkState] for the gadget panel. A line the codec rejects is reported as a
 * status so a firmware/phone disagreement is visible instead of silently becoming `no response`.
 */
export class UsbGadgetSignalSource implements SignalSource {
  readonly kind = 'USB_GADGET' as const;
  private current: ((event: SignalEvent) => void) | null = null;
  private subscriptions: { remove(): void }[] = [];
  private state: GadgetLinkState = { connected: false, note: null };

  constructor(private readonly onLinkState: (state: GadgetLinkState) => void = () => {}) {
    if (!FofUsb) return;
    this.subscriptions.push(
      FofUsb.addListener('onLine', ({ line }) => this.onLine(line)),
      FofUsb.addListener('onState', ({ connected, error }) => this.setState({ connected, note: error ?? this.state.note })),
    );
  }

  get linkState(): GadgetLinkState {
    return this.state;
  }

  /** Opens the first gadget on the USB port (asks for permission once) and requests its status line. */
  async connect(): Promise<string> {
    if (!FofUsb) throw new Error('USB link not available in this build');
    const name = await FofUsb.open(null);
    this.setState({ connected: true, note: name });
    FofUsb.write(encodeStatusRequest());
    return name;
  }

  async challenge(req: ChallengeRequest, emit: (event: SignalEvent) => void, signal: AbortSignal): Promise<void> {
    if (!FofUsb || !FofUsb.isOpen()) throw new Error('gadget not connected');
    await new Promise<void>((resolve, reject) => {
      const done = () => {
        this.current = null;
        signal.removeEventListener('abort', done);
        resolve();
      };
      this.current = (event) => {
        emit(event);
        if (event.type === 'answer') done();
      };
      signal.addEventListener('abort', done, { once: true });
      if (!FofUsb!.write(encodeChallenge(req))) {
        this.current = null;
        reject(new Error('write to gadget failed'));
      }
    });
  }

  close(): void {
    this.current = null;
    this.subscriptions.forEach((s) => s.remove());
    this.subscriptions = [];
    FofUsb?.close();
  }

  private onLine(line: string): void {
    let event: SignalEvent | null;
    try {
      event = parseLine(line);
    } catch (e) {
      event = { type: 'status', text: `bad gadget line · ${(e as Error).message.slice(0, 80)}` };
    }
    if (!event) return;
    if (this.current) {
      this.current(event);
    } else if (event.type === 'status') {
      this.setState({ ...this.state, note: event.text });
    }
  }

  private setState(state: GadgetLinkState): void {
    this.state = state;
    this.onLinkState(state);
  }
}
