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

  it('skips the replay check when the reader lost the nonce line', () => {
    const r = new ReChecks().check(answer({ nonceHex: null }), 100);
    expect(r.verdict).toBe('FRIEND');
    expect(r.notes).toContain('nonce not reported; replay check skipped');
  });

  it('passes UNKNOWN through but still records the nonce', () => {
    const r = new ReChecks();
    expect(r.check(answer({ verdict: 'UNKNOWN', reason: 'no response' }), 10000)).toMatchObject({ verdict: 'UNKNOWN' });
    expect(r.check(answer(), 100)).toMatchObject({ verdict: 'FOE', reason: 'replayed nonce' });
  });
});
