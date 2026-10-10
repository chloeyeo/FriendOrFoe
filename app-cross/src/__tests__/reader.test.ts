import { BridgeLink, type LineLink, type LinkHandlers } from '../signal/lineLink';
import {
  answerFromResult,
  encodeQuery,
  encodeStatus,
  isRequestId,
  parseReaderLine,
  requestIdFor,
  type ReaderResult,
} from '../signal/readerProtocol';
import { ReaderSignalSource, type GadgetLinkState } from '../signal/readerSignalSource';
import type { SignalEvent } from '../signal/signal';

const NONCE = '00112233445566778899aabbccddeeff';
const BOOT = 'b1f8b86d5ad40779';

// The firmware README's example lines (firmware/README.md "Serial interface v2").
const RESULT_FRIEND =
  '{"event":"RESULT","request_id":"demo_1","query":1,"response_received":true,"verdict":"FRIEND","reason":"NONE","response_ms":337,"elapsed_ms":337,"replay":false}';

describe('reader protocol', () => {
  it('encodes QUERY and STATUS and refuses ids the reader would reject', () => {
    expect(encodeQuery('demo_1')).toBe('QUERY demo_1');
    expect(encodeStatus()).toBe('STATUS');
    expect(encodeStatus('s-1')).toBe('STATUS s-1');
    expect(() => encodeQuery('two words')).toThrow('bad request id');
    expect(() => encodeQuery('x'.repeat(33))).toThrow('bad request id');
  });

  it('makes a fresh, valid request id per challenge', () => {
    const req = { boxId: 12, challengerId: 65535, t: 1791600000 };
    const a = requestIdFor(req, 0);
    const b = requestIdFor(req, 1);
    expect(isRequestId(a) && isRequestId(b)).toBe(true);
    expect(a).not.toBe(b);
    expect(requestIdFor({ boxId: 99999999, challengerId: 65535, t: 1791600000 }, 0xffffffff).length).toBeLessThanOrEqual(32);
  });

  it('parses the README lines', () => {
    expect(parseReaderLine('{"event":"ACK","request_id":"demo_1","accepted":true,"reason":"ACCEPTED","state":"QUERYING"}')).toEqual({
      event: 'ACK',
      requestId: 'demo_1',
      accepted: true,
      reason: 'ACCEPTED',
      state: 'QUERYING',
    });
    expect(parseReaderLine(RESULT_FRIEND)).toEqual({
      event: 'RESULT',
      requestId: 'demo_1',
      query: 1,
      responseReceived: true,
      verdict: 'FRIEND',
      reason: 'NONE',
      responseMs: 337,
      elapsedMs: 337,
      replay: false,
    });
    expect(parseReaderLine(`{"event":"STATE","boot_id":"${BOOT}","request_id":"demo_1","state":"IDLE","reason":"CHANGED"}`)).toMatchObject({
      event: 'STATE',
      bootId: BOOT,
      state: 'IDLE',
    });
    expect(
      parseReaderLine(
        `{"event":"RX_RESPONSE","request_id":"demo_1","query":1,"at_ms":9,"elapsed_ms":337,"bytes":38,"version":1,"type":2,"reader_id":1,"drone_id":1,"nonce":"${NONCE.toUpperCase()}","tag":"ff","raw":"01","result":"FRIEND"}`,
      ),
    ).toEqual({ event: 'RX_RESPONSE', requestId: 'demo_1', query: 1, result: 'FRIEND', nonceHex: NONCE, droneId: 1 });
  });

  it('reads the last result out of a STATUS snapshot', () => {
    const status = parseReaderLine(
      `{"event":"STATUS","request_id":"s1","role":"reader","interface_version":2,"boot_id":"${BOOT}","manual_query":true,"response_timeout_ms":3000,"key_configured":true,"radio_ready":true,"state":"COOLDOWN","active_request_id":"demo_1","last_result":${RESULT_FRIEND.replace('"replay":false', '"replay":true')}}`,
    );
    expect(status).toMatchObject({ event: 'STATUS', role: 'reader', interfaceVersion: 2, keyConfigured: true, state: 'COOLDOWN' });
    expect(status?.event === 'STATUS' && status.lastResult).toMatchObject({ requestId: 'demo_1', verdict: 'FRIEND', replay: true });
  });

  it('ignores boot chatter and broken events, but notices download mode', () => {
    expect(parseReaderLine('')).toBeNull();
    expect(parseReaderLine('ESP-ROM:esp32c3-api1-20210207')).toBeNull();
    expect(parseReaderLine('{"event":"HELP","commands":[]}')).toBeNull();
    expect(parseReaderLine('{"event":"RESULT","request_id":"x","verdict":"MAYBE","reason":"NONE","query":1,"response_received":true}')).toBeNull();
    expect(parseReaderLine('{"event":"ACK",')).toBeNull();
    expect(parseReaderLine('waiting for download')).toEqual({ event: 'DOWNLOAD_MODE' });
  });

  it('turns the reader result into the phone answer', () => {
    const result = (patch: Partial<ReaderResult>): ReaderResult => ({ ...(parseReaderLine(RESULT_FRIEND) as ReaderResult), ...patch });
    const packet = { nonceHex: NONCE, droneId: 1 };
    expect(answerFromResult(result({}), packet)).toEqual({
      type: 'answer',
      verdict: 'FRIEND',
      reason: null,
      droneId: '1',
      nonceHex: NONCE,
      rssiDbm: null,
      responseMs: 337,
      source: 'USB_GADGET',
    });
    expect(answerFromResult(result({ verdict: 'UNKNOWN', reason: 'AUTH_FAILED' }), packet)).toMatchObject({ verdict: 'FOE', reason: 'bad tag' });
    expect(
      answerFromResult(result({ verdict: 'UNKNOWN', reason: 'NO_RESPONSE', responseReceived: false, responseMs: null, elapsedMs: 3001 }), {
        nonceHex: null,
        droneId: null,
      }),
    ).toMatchObject({ verdict: 'UNKNOWN', reason: 'no response', responseMs: 3001, droneId: null });
    expect(answerFromResult(result({ verdict: 'UNKNOWN', reason: 'RADIO_ERROR' }), packet)).toMatchObject({ verdict: 'UNKNOWN', reason: 'radio error' });
  });
});

/** The reader's side of the serial line: answers STATUS by itself, everything else is scripted per test. */
class FakeReader implements LineLink {
  readonly name = 'fake';
  h!: LinkHandlers;
  written: string[] = [];
  up = false;
  role = 'reader';
  key = true;
  state = 'IDLE';
  last: string | null = null;

  async open(): Promise<string> {
    this.up = true;
    this.h.onState(true, null);
    return 'fake reader';
  }

  async write(line: string): Promise<void> {
    this.written.push(line);
    if (line.startsWith('STATUS')) this.status(line.slice(7));
  }

  isOpen(): boolean {
    return this.up;
  }

  close(): void {
    this.up = false;
  }

  send(line: string | object): void {
    this.h.onLine(typeof line === 'string' ? line : JSON.stringify(line));
  }

  status(id: string): void {
    this.send(
      `{"event":"STATUS","request_id":"${id}","role":"${this.role}","interface_version":2,"boot_id":"${BOOT}","manual_query":true,"response_timeout_ms":3000,"key_configured":${this.key},"radio_ready":true,"state":"${this.state}","active_request_id":"","last_result":${this.last ?? 'null'}}`,
    );
  }

  lastQueryId(): string {
    const q = [...this.written].reverse().find((l) => l.startsWith('QUERY '));
    if (!q) throw new Error('no QUERY written');
    return q.slice(6);
  }

  /** What the firmware prints for one query that the drone answers (reader.ino beginChallenge/acceptResponse/finish). */
  answer(id: string, reason = 'NONE'): void {
    this.send({ event: 'ACK', request_id: id, accepted: true, reason: 'ACCEPTED', state: 'QUERYING' });
    this.send({ event: 'STATE', boot_id: BOOT, request_id: id, state: 'QUERYING', reason: 'CHANGED' });
    this.send({ event: 'TX_CHALLENGE', request_id: id, query: 1, bytes: 38, drone_id: 1, nonce: NONCE, result: 'BROADCAST_STARTED' });
    const friend = reason === 'NONE';
    this.send({ event: 'RX_RESPONSE', request_id: id, query: 1, bytes: 38, drone_id: 1, nonce: NONCE, result: friend ? 'FRIEND' : reason });
    this.send(this.result(id, reason));
    this.state = 'COOLDOWN';
    this.send({ event: 'STATE', boot_id: BOOT, request_id: id, state: 'COOLDOWN', reason: 'CHANGED' });
  }

  result(id: string, reason = 'NONE'): string {
    const friend = reason === 'NONE';
    return JSON.stringify({
      event: 'RESULT',
      request_id: id,
      query: 1,
      response_received: true,
      verdict: friend ? 'FRIEND' : 'UNKNOWN',
      reason,
      response_ms: 337,
      elapsed_ms: friend ? 337 : 3000,
      replay: false,
    });
  }

  idle(): void {
    this.state = 'IDLE';
    this.send({ event: 'STATE', boot_id: BOOT, request_id: '', state: 'IDLE', reason: 'CHANGED' });
  }
}

const REQ = { boxId: 3, challengerId: 7, t: 1791600000 };

async function connected(): Promise<{ reader: FakeReader; source: ReaderSignalSource; states: GadgetLinkState[] }> {
  const reader = new FakeReader();
  const states: GadgetLinkState[] = [];
  const source = new ReaderSignalSource(
    (h) => {
      reader.h = h;
      return reader;
    },
    (s) => states.push(s),
  );
  await source.connect();
  return { reader, source, states };
}

function run(source: ReaderSignalSource, signal = new AbortController().signal) {
  const events: SignalEvent[] = [];
  const done = source.challenge(REQ, (e) => events.push(e), signal);
  return { events, done, answer: () => events.find((e) => e.type === 'answer') };
}

describe('ReaderSignalSource', () => {
  afterEach(() => jest.useRealTimers());

  it('checks the reader with STATUS on connect', async () => {
    const { reader, source } = await connected();
    expect(reader.written[0]).toMatch(/^STATUS hs-/);
    expect(source.linkState).toEqual({ connected: true, note: 'reader v2 · key set · radio ready · IDLE · boot b1f8b8' });
  });

  it('refuses the drone responder plugged in by mistake', async () => {
    const reader = new FakeReader();
    reader.role = 'drone';
    const source = new ReaderSignalSource((h) => ((reader.h = h), reader));
    await source.connect();
    expect(source.linkState).toEqual({ connected: false, note: 'this port is the drone responder, not the reader' });
    await expect(source.challenge(REQ, () => {}, new AbortController().signal)).rejects.toThrow('drone responder');
  });

  it('runs one QUERY to a FRIEND answer with the nonce and drone id from the packet lines', async () => {
    const { reader, source } = await connected();
    const r = run(source);
    const id = reader.lastQueryId();
    reader.answer(id);
    await r.done;
    expect(r.answer()).toEqual({
      type: 'answer',
      verdict: 'FRIEND',
      reason: null,
      droneId: '1',
      nonceHex: NONCE,
      rssiDbm: null,
      responseMs: 337,
      source: 'USB_GADGET',
    });
    expect(r.events.filter((e) => e.type === 'status').map((e) => (e.type === 'status' ? e.text : ''))).toEqual([
      `QUERY ${id} → reader`,
      'reader broadcasting the challenge',
      'challenge on air · nonce 00112233…',
      'response verified · nonce 00112233…',
    ]);
  });

  it('reports a bad tag as FOE', async () => {
    const { reader, source } = await connected();
    const r = run(source);
    reader.answer(reader.lastQueryId(), 'AUTH_FAILED');
    await r.done;
    expect(r.answer()).toMatchObject({ verdict: 'FOE', reason: 'bad tag' });
  });

  it('holds a tap made during the cooldown until the reader is IDLE', async () => {
    const { reader, source } = await connected();
    const first = run(source);
    reader.answer(reader.lastQueryId());
    await first.done;
    const queries = () => reader.written.filter((l) => l.startsWith('QUERY')).length;
    const second = run(source);
    expect(queries()).toBe(1);
    expect(second.events[0]).toEqual({ type: 'status', text: 'reader cooldown; query waits for it' });
    reader.idle();
    expect(queries()).toBe(2);
    reader.answer(reader.lastQueryId());
    await second.done;
    expect(second.answer()).toMatchObject({ verdict: 'FRIEND' });
  });

  it('re-sends the same id after a BUSY refusal', async () => {
    const { reader, source } = await connected();
    const r = run(source);
    const id = reader.lastQueryId();
    reader.send({ event: 'ACK', request_id: id, accepted: false, reason: 'BUSY', state: 'COOLDOWN' });
    reader.idle();
    expect(reader.written.filter((l) => l === `QUERY ${id}`)).toHaveLength(2);
    reader.answer(id);
    await r.done;
    expect(r.answer()).toMatchObject({ verdict: 'FRIEND' });
  });

  it('fails the challenge when the reader has no key', async () => {
    const { reader, source } = await connected();
    const r = run(source);
    reader.send({ event: 'ACK', request_id: reader.lastQueryId(), accepted: false, reason: 'KEY_NOT_CONFIGURED', state: 'IDLE' });
    await expect(r.done).rejects.toThrow('reader has no key');
  });

  it('recovers a lost RESULT line from the STATUS snapshot', async () => {
    jest.useFakeTimers();
    const { reader, source } = await connected();
    const r = run(source);
    const id = reader.lastQueryId();
    reader.send({ event: 'ACK', request_id: id, accepted: true, reason: 'ACCEPTED', state: 'QUERYING' });
    reader.last = reader.result(id); // the RESULT line itself never arrives
    jest.advanceTimersByTime(4500);
    await r.done;
    expect(reader.written).toContain(`STATUS ${id}`);
    expect(r.answer()).toMatchObject({ verdict: 'FRIEND', nonceHex: null, droneId: null });
  });

  it('fails the challenge when the link drops, and stops listening on abort', async () => {
    const { reader, source } = await connected();
    const r = run(source);
    reader.h.onState(false, 'cable out');
    await expect(r.done).rejects.toThrow('reader link lost · cable out');
    expect(source.linkState).toEqual({ connected: false, note: 'cable out' });

    const again = await connected();
    const controller = new AbortController();
    const aborted = run(again.source, controller.signal);
    controller.abort();
    await aborted.done;
    again.reader.answer(again.reader.lastQueryId());
    expect(aborted.answer()).toBeUndefined();
  });
});

describe('BridgeLink', () => {
  type Reply = { status?: number; body: unknown };
  /** GET and POST answered from separate queues; a GET with nothing queued waits like a long poll, until aborted. */
  function fakeFetch(gets: Reply[], posts: Reply[] = []) {
    const calls: { url: string; init?: RequestInit }[] = [];
    const http = jest.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      const next = (init?.method === 'POST' ? posts : gets).shift();
      if (!next) {
        return new Promise<Response>((_, reject) => init?.signal?.addEventListener('abort', () => reject(new Error('aborted'))));
      }
      const status = next.status ?? 200;
      return { ok: status < 300, status, json: async () => next.body, text: async () => JSON.stringify(next.body) } as Response;
    });
    return { http: http as unknown as typeof fetch, calls };
  }
  const page = (seq: number, lines: string[], bridge = 'b1') => ({ body: { bridge, seq, lines, serial: { port: '/dev/cu.usbmodem1', connected: true, error: null } } });
  const flush = () => new Promise((r) => setTimeout(r, 0));

  it('starts at the current line, then long-polls and delivers new lines', async () => {
    const { http, calls } = fakeFetch([page(41, []), page(43, ['a', 'b'])]);
    const lines: string[] = [];
    const states: [boolean, string | null][] = [];
    const link = new BridgeLink('http://10.0.0.2:8766/', { onLine: (l) => lines.push(l), onState: (u, e) => states.push([u, e]) }, http);
    expect(await link.open()).toBe('10.0.0.2:8766 · /dev/cu.usbmodem1');
    await flush();
    expect(calls.map((c) => c.url)).toEqual(['http://10.0.0.2:8766/lines', 'http://10.0.0.2:8766/lines?after=41&wait=2000', 'http://10.0.0.2:8766/lines?after=43&wait=2000']);
    expect(lines).toEqual(['a', 'b']);
    expect(states).toEqual([[true, null]]);
    expect(link.isOpen()).toBe(true);
    link.close();
  });

  it('drops lines from a restarted bridge and reports the laptop losing the reader', async () => {
    const down = { body: { bridge: 'b2', seq: 5, lines: [], serial: { port: null, connected: false, error: 'no reader on USB' } } };
    const { http } = fakeFetch([page(10, []), page(3, ['old'], 'b2'), down]);
    const lines: string[] = [];
    const states: [boolean, string | null][] = [];
    const link = new BridgeLink('http://h:1', { onLine: (l) => lines.push(l), onState: (u, e) => states.push([u, e]) }, http);
    await link.open();
    await flush();
    expect(lines).toEqual([]);
    expect(states).toEqual([
      [true, null],
      [false, 'no reader on USB'],
    ]);
    link.close();
  });

  it('posts one command line and surfaces a refusal', async () => {
    const { http, calls } = fakeFetch([page(0, [])], [{ status: 202, body: { ok: true } }]);
    const link = new BridgeLink('http://h:1', { onLine: () => {}, onState: () => {} }, http, 2000);
    await link.open();
    await link.write('QUERY q1');
    const post = calls.find((c) => c.init?.method === 'POST');
    expect(post?.url).toBe('http://h:1/line');
    expect(post?.init?.body).toBe('{"line":"QUERY q1"}');
    link.close();

    const refused = fakeFetch([page(0, [])], [{ status: 403, body: { error: 'command not allowed' } }]);
    const link2 = new BridgeLink('http://h:1', { onLine: () => {}, onState: () => {} }, refused.http);
    await link2.open();
    await expect(link2.write('KEY 00')).rejects.toThrow('bridge refused the line (403');
    link2.close();
  });
});
