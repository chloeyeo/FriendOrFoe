import { encodeChallenge, encodeStatusRequest, parseLine } from '../signal/gadgetLineCodec';
import { ReChecks } from '../signal/reChecks';
import type { SignalAnswer } from '../signal/signal';

const NONCE = '00112233445566778899aabbccddeeff';

const answer = (patch: Partial<SignalAnswer> = {}): SignalAnswer => ({
  type: 'answer',
  verdict: 'FRIEND',
  reason: null,
  droneId: 'RQ-07',
  nonceHex: NONCE,
  rssiDbm: -58,
  responseMs: 42,
  source: 'USB_GADGET',
  ...patch,
});

describe('GadgetLineCodec', () => {
  it('encodes the two phone commands as one JSON object each', () => {
    expect(encodeChallenge({ boxId: 3, challengerId: 7, t: 1760000000 })).toBe(
      '{"cmd":"challenge","boxId":3,"challengerId":7,"t":1760000000}',
    );
    expect(encodeStatusRequest()).toBe('{"cmd":"status"}');
  });

  it('parses the README answer lines', () => {
    expect(
      parseLine(`{"type":"answer","verdict":"FRIEND","reason":null,"droneId":"RQ-07","nonce":"${NONCE.toUpperCase()}","rssiDbm":-58,"responseMs":42,"airtimeMs":1.2}`),
    ).toEqual(answer());
    expect(parseLine(`{"type":"answer","verdict":"UNKNOWN","reason":"no response","nonce":"${NONCE}","responseMs":10000}`)).toEqual(
      answer({ verdict: 'UNKNOWN', reason: 'no response', droneId: null, rssiDbm: null, responseMs: 10000 }),
    );
  });

  it('turns a status line into readable text', () => {
    expect(parseLine('{"type":"status","fw":"0.1","battery":3.9,"mode":"HONEST"}')).toEqual({
      type: 'status',
      text: 'fw 0.1 · battery 3.9 V · mode HONEST',
    });
    expect(parseLine('{"type":"status"}')).toEqual({ type: 'status', text: 'status' });
  });

  it('skips blank lines and foreign types, fails loudly on broken answers', () => {
    expect(parseLine('   ')).toBeNull();
    expect(parseLine('{"type":"debug","x":1}')).toBeNull();
    expect(() => parseLine('not json')).toThrow('not a JSON object');
    expect(() => parseLine(`{"type":"answer","verdict":"MAYBE","nonce":"${NONCE}","responseMs":1}`)).toThrow('unknown verdict');
    expect(() => parseLine(`{"type":"answer","verdict":"FOE","nonce":"${NONCE}"}`)).toThrow('responseMs');
    expect(() => parseLine('{"type":"answer","verdict":"FOE","nonce":"abc","responseMs":1}')).toThrow('bad nonce');
  });
});

describe('ReChecks', () => {
  it('passes an honest answer', () => {
    expect(new ReChecks().check(answer(), 120)).toMatchObject({ verdict: 'FRIEND', reason: null });
  });

  it('turns a nonce seen before into FOE replayed nonce', () => {
    const r = new ReChecks();
    r.check(answer(), 100);
    expect(r.check(answer(), 100)).toMatchObject({ verdict: 'FOE', reason: 'replayed nonce' });
  });

  it('flags a late answer from either clock', () => {
    expect(new ReChecks().check(answer(), 2600)).toMatchObject({ verdict: 'FOE', reason: 'late response' });
    expect(new ReChecks().check(answer({ responseMs: 3450 }), 100)).toMatchObject({ verdict: 'FOE', reason: 'late response' });
  });

  it('checks the RSSI floor, skipping a missing RSSI unless strict', () => {
    expect(new ReChecks().check(answer({ rssiDbm: -80 }), 100)).toMatchObject({ verdict: 'FOE', reason: 'signal mismatch' });
    expect(new ReChecks().check(answer({ rssiDbm: null }), 100).verdict).toBe('FRIEND');
    expect(new ReChecks({ rttBudgetMs: 2500, rssiFloorDbm: -70, strict: true }).check(answer({ rssiDbm: null }), 100)).toMatchObject({
      verdict: 'FOE',
      reason: 'signal mismatch',
    });
  });

  it("keeps the gadget's FOE reason and notes the phone's finding", () => {
    const r = new ReChecks().check(answer({ verdict: 'FOE', reason: 'bad tag', rssiDbm: -90 }), 100);
    expect(r).toMatchObject({ verdict: 'FOE', reason: 'bad tag' });
    expect(r.notes).toContain('phone also: signal mismatch');
  });

  it('passes UNKNOWN through but still records the nonce', () => {
    const r = new ReChecks();
    expect(r.check(answer({ verdict: 'UNKNOWN', reason: 'no response' }), 10000)).toMatchObject({ verdict: 'UNKNOWN' });
    expect(r.check(answer(), 100)).toMatchObject({ verdict: 'FOE', reason: 'replayed nonce' });
  });
});
