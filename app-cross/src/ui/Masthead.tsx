import { StyleSheet, Text, View } from 'react-native';

import type { StatusUi } from '../core/types';
import { dbm } from './overlayStyle';
import { colors, mono } from './theme';

function detectorLabel(name: string, compact: boolean): string {
  if (name.startsWith('clikart')) return compact ? 'ClikaRT' : 'On-device ClikaRT';
  if (name.startsWith('http')) return compact ? 'Laptop' : 'Laptop serve';
  if (name === 'fake') return 'Simulated';
  return name;
}

interface StatusProps {
  status: StatusUi;
  gadgetLabel: string;
  compact?: boolean;
  /** Floating on the camera: each item gets its own dark backing so it reads over any picture. */
  overlay?: boolean;
}

/**
 * The phone's status strip in the web masthead's style (indicator square + text): detector and timing, the
 * OFFLINE badge, the gadget link. The normal text colour means fine, amber means look at this.
 */
export function StatusItems({ status, gadgetLabel, compact = false, overlay = false }: StatusProps) {
  const timing = status.modelReady && status.msPerFrame > 0 ? ` · ${status.msPerFrame} ms · ${status.fps.toFixed(1)} fps` : '';
  const detector = `${detectorLabel(status.detectorName, compact)}${status.modelReady ? timing : ' · not ready'}`;
  const gadget = status.gadgetConnected ? `${gadgetLabel}${status.rssiDbm != null ? ` ${dbm(status.rssiDbm)}` : ''}` : `${gadgetLabel} off`;
  return (
    <>
      <Status ok={status.offline} text={status.offline ? 'OFFLINE' : 'ONLINE'} overlay={overlay} />
      <Status ok={status.modelReady} text={detector} overlay={overlay} shrink />
      <Status ok={status.gadgetConnected} text={gadget} overlay={overlay} />
    </>
  );
}

function Status({ ok, text, overlay, shrink }: { ok: boolean; text: string; overlay: boolean; shrink?: boolean }) {
  return (
    <View style={[styles.status, overlay && styles.statusOverlay, shrink && styles.shrink]} accessibilityRole="text" accessibilityLabel={text}>
      <View style={[styles.indicator, { backgroundColor: ok ? colors.text : colors.amber }]} />
      <Text style={[styles.statusText, !ok && styles.waiting]} numberOfLines={1}>
        {text}
      </Text>
    </View>
  );
}

export function Brand({ size = 22 }: { size?: number }) {
  return (
    <Text style={[styles.h1, { fontSize: size }]} accessibilityRole="header">
      FRIEND<Text style={styles.slash}>/</Text>FOE
    </Text>
  );
}

/** The web dashboard's masthead (title + status), used where vertical space allows it. */
export function Masthead({ status, gadgetLabel, compact = false }: { status: StatusUi; gadgetLabel: string; compact?: boolean }) {
  return (
    <View style={[styles.masthead, compact && styles.mastheadCompact]}>
      <Brand size={compact ? 18 : 22} />
      <View style={[styles.cluster, compact && styles.clusterCompact]}>
        <StatusItems status={status} gadgetLabel={gadgetLabel} compact={compact} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  masthead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    minHeight: 48,
    paddingHorizontal: 14,
    backgroundColor: colors.surface1,
    borderBottomWidth: 1,
    borderBottomColor: colors.line,
  },
  mastheadCompact: { flexDirection: 'column', alignItems: 'flex-start', gap: 6, paddingVertical: 8, paddingHorizontal: 12 },
  h1: { fontFamily: mono, fontWeight: '800', letterSpacing: -1, color: colors.text },
  slash: { color: colors.muted },
  cluster: { flexDirection: 'row', alignItems: 'center', gap: 16, flexShrink: 1 },
  clusterCompact: { gap: 12, flexWrap: 'wrap', alignSelf: 'stretch' },
  status: { flexDirection: 'row', alignItems: 'center', gap: 7 },
  statusOverlay: { backgroundColor: colors.scrim, paddingHorizontal: 8, paddingVertical: 6 },
  shrink: { flexShrink: 1 },
  indicator: { width: 6, height: 6 },
  statusText: { flexShrink: 1, fontFamily: mono, fontSize: 12, color: colors.text },
  waiting: { color: colors.amber },
});
