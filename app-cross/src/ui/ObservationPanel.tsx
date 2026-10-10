import { memo, useMemo, useState, type ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View, type GestureResponderEvent, type LayoutChangeEvent } from 'react-native';

import { FofCameraView, type CameraErrorEvent, type CameraFrameEvent, type GrayEvent } from '../../modules/fof-native';
import type { StatusUi, TrackUi } from '../core/types';
import { compactLabel, droneAt, tagFor } from './overlayStyle';
import { colors, mono, toneColor } from './theme';
import { useNow } from './format';

export interface CameraProps {
  detector: 'clikart' | 'http' | 'coreml' | 'none';
  serveUrl: string;
  intervalMs: number;
  thumbnailUrl: string;
  onFrame: (e: CameraFrameEvent) => void;
  onGray: (e: GrayEvent) => void;
  onCameraError: (e: CameraErrorEvent) => void;
}

interface Props {
  tracks: TrackUi[];
  status: StatusUi;
  /** Null shows the simulated backdrop (no camera: web, iOS, the simulated detector, or no permission yet). */
  camera: CameraProps | null;
  onPreviewLayout: (width: number, height: number) => void;
  onTapDrone: (id: number) => void;
  /**
   * `panel`: the web page's pane with its heading row. `immersive`: the phone layout — no heading, the camera takes
   * the full height and [overlay] (brand + status) floats on its top edge, the object summary on its bottom edge.
   */
  variant?: 'panel' | 'immersive';
  overlay?: ReactNode;
  /** Phone-sized: larger tags relative to the view and a bigger tap slop. */
  compact?: boolean;
}

const clamp = (lo: number, v: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** "1 drone · 1 person", the web dashboard's panel heading. */
function objectSummary(tracks: TrackUi[]): string {
  if (!tracks.length) return 'No targets';
  const drones = tracks.filter((t) => t.cls === 'DRONE').length;
  const people = tracks.filter((t) => t.cls === 'PERSON').length;
  const birds = tracks.filter((t) => t.cls === 'BIRD').length;
  return [
    drones ? `${drones} drone${drones === 1 ? '' : 's'}` : '',
    people ? `${people} ${people === 1 ? 'person' : 'people'}` : '',
    birds ? `${birds} bird${birds === 1 ? '' : 's'}` : '',
  ]
    .filter(Boolean)
    .join(' · ');
}

/**
 * The observer pane (web FrameView): camera preview or the grid backdrop, a ring per track with its tag above and
 * its evidence line below, and the tap that challenges a drone. Measurements stay off the object, as on the web
 * page; they are in the result details.
 */
export function ObservationPanel({ tracks, status, camera, onPreviewLayout, onTapDrone, variant = 'panel', overlay, compact = false }: Props) {
  const immersive = variant === 'immersive';
  const [size, setSize] = useState({ width: 0, height: 0 });
  const challenging = tracks.some((t) => t.iff.kind === 'CHALLENGED');
  const now = useNow(challenging ? 250 : 1000);

  const onLayout = (e: LayoutChangeEvent) => {
    const { width, height } = e.nativeEvent.layout;
    setSize({ width, height });
    onPreviewLayout(width, height);
  };

  const onPress = (e: GestureResponderEvent) => {
    const ne = e.nativeEvent as GestureResponderEvent['nativeEvent'] & { offsetX?: number; offsetY?: number };
    const x = ne.locationX ?? ne.offsetX ?? 0;
    const y = ne.locationY ?? ne.offsetY ?? 0;
    // Inference runs every few seconds on phone A, so the drone may have moved since its box was drawn: be generous.
    const hit = droneAt(tracks, x, y, compact ? 36 : 24);
    if (hit) onTapDrone(hit.id);
  };

  const hint = tracks.some((t) => t.cls === 'DRONE' && t.iff.kind === 'UNCHALLENGED') ? ' · tap a drone to identify' : '';
  const note = status.modelNote ?? (camera ? null : 'Simulated detector · no camera');

  return (
    <View style={[styles.panel, immersive && styles.panelImmersive]}>
      {immersive ? null : (
        <View style={styles.heading}>
          <Text style={styles.h2}>Observation</Text>
          <Text style={styles.headingNote} numberOfLines={1}>
            {objectSummary(tracks)}
            {hint}
          </Text>
        </View>
      )}
      <View style={styles.viewport} onLayout={onLayout}>
        <Grid width={size.width} height={size.height} />
        {camera ? (
          <FofCameraView
            style={StyleSheet.absoluteFill}
            active
            detector={camera.detector}
            serveUrl={camera.serveUrl}
            intervalMs={camera.intervalMs}
            thumbnailUrl={camera.thumbnailUrl}
            onFrame={(e) => camera.onFrame(e.nativeEvent)}
            onGray={(e) => camera.onGray(e.nativeEvent)}
            onCameraError={(e) => camera.onCameraError(e.nativeEvent)}
          />
        ) : null}
        <Pressable style={StyleSheet.absoluteFill} onPress={onPress} accessibilityLabel="Observation. Tap a drone ring to identify it.">
          {drawOrder(tracks).map((t) => (
            <Ring key={t.id} track={t} now={now} viewWidth={size.width} viewHeight={size.height} compact={compact} />
          ))}
          {!tracks.length && !camera ? (
            <View style={styles.standby} pointerEvents="none">
              <View style={styles.reticle}>
                <View style={styles.reticleH} />
                <View style={styles.reticleV} />
              </View>
              <Text style={styles.standbyTitle}>Waiting for targets</Text>
            </View>
          ) : null}
        </Pressable>
        {overlay ? (
          <View style={styles.overlayTop} pointerEvents="none">
            {overlay}
          </View>
        ) : null}
        <View style={styles.bottom} pointerEvents="none">
          {immersive ? (
            <Text style={styles.summary} numberOfLines={1}>
              {objectSummary(tracks)}
              {hint}
            </Text>
          ) : null}
          {note ? (
            <Text style={[styles.note, !status.modelReady && styles.noteWarn]} numberOfLines={1}>
              {note}
            </Text>
          ) : null}
        </View>
      </View>
    </View>
  );
}

/** Drones last: a drone's tag is what the observer reads, so it is drawn on top. */
function drawOrder(tracks: TrackUi[]): TrackUi[] {
  return [...tracks.filter((t) => t.cls !== 'DRONE'), ...tracks.filter((t) => t.cls === 'DRONE')];
}

const Grid = memo(function Grid({ width, height }: { width: number; height: number }) {
  const lines = useMemo(() => {
    const out: { key: string; style: object }[] = [];
    for (let x = 48; x < width; x += 48) out.push({ key: `v${x}`, style: { left: x, top: 0, bottom: 0, width: 1 } });
    for (let y = 48; y < height; y += 48) out.push({ key: `h${y}`, style: { top: y, left: 0, right: 0, height: 1 } });
    return out;
  }, [width, height]);
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none">
      {lines.map((l) => (
        <View key={l.key} style={[styles.gridLine, l.style]} />
      ))}
    </View>
  );
});

function Ring({ track, now, viewWidth, viewHeight, compact }: { track: TrackUi; now: number; viewWidth: number; viewHeight: number; compact: boolean }) {
  const tag = tagFor(track, now);
  const color = toneColor(tag.tone);
  const { left, top, right, bottom } = track.box;
  const vw = viewWidth || 1;
  const vh = viewHeight || 1;
  // Under the floating status row on the phone, a tag above the box would be covered: put it inside instead.
  const inside = top / vh < (compact ? 0.2 : 0.12);
  const detailAbove = bottom / vh > 0.85;
  // Read at arm's length on a 6.7" phone: never below 13 dp there.
  const tagSize = compact ? clamp(13, vw * 0.022, 18) : clamp(11, vw * 0.016, 18);
  const detailSize = compact ? clamp(11, vw * 0.016, 14) : clamp(10, vw * 0.012, 13);
  const label = compactLabel(tag.text);
  // A tag that would run off the right edge is anchored to the box's right edge instead, and wraps within the room
  // left on that side rather than being clipped (monospace: about 0.62 em per character plus the padding).
  const estimate = label.length * tagSize * 0.62 + 14;
  const roomRight = vw - left;
  const roomLeft = right;
  const alignRight = estimate > roomRight ? roomLeft > roomRight : left / vw > 0.65;
  const maxWidth = Math.max(60, Math.min(300, vw * 0.65, alignRight ? roomLeft : roomRight));
  const detail = tag.detail ? compactLabel(tag.detail) : '';
  const side = alignRight ? { right: -2, justifyContent: 'flex-end' as const } : { left: -2, justifyContent: 'flex-start' as const };
  const insideSide = alignRight ? { top: 2, right: 2, justifyContent: 'flex-end' as const } : { top: 2, left: 2 };

  return (
    <View
      pointerEvents="none"
      accessibilityLabel={tag.text}
      style={[styles.ring, { left, top, width: right - left, height: bottom - top, borderColor: color, borderWidth: track.cls === 'DRONE' ? 2 : 1 }]}
    >
      <View style={[styles.labelRow, { width: maxWidth }, inside ? insideSide : { bottom: '100%', marginBottom: 3, ...side }]}>
        <Text style={[styles.tag, { backgroundColor: color, fontSize: tagSize }]}>{label}</Text>
      </View>
      {detail ? (
        <View style={[styles.labelRow, { width: maxWidth }, detailAbove ? { bottom: 2 } : { top: '100%', marginTop: 3 }, side]}>
          <Text style={[styles.detail, { color, fontSize: detailSize }]}>{detail}</Text>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  panel: { flex: 1, minWidth: 0, borderRightWidth: 1, borderRightColor: colors.line },
  heading: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 10,
    minHeight: 38,
    paddingHorizontal: 14,
    paddingVertical: 8,
    backgroundColor: colors.surface2,
    borderBottomWidth: 1,
    borderBottomColor: colors.line,
  },
  h2: { fontFamily: mono, fontSize: 13, fontWeight: '600', color: colors.text },
  headingNote: { flexShrink: 1, fontFamily: mono, fontSize: 12, color: colors.muted, textAlign: 'right' },
  viewport: { flex: 1, overflow: 'hidden', backgroundColor: colors.viewport },
  gridLine: { position: 'absolute', backgroundColor: colors.grid },
  ring: { position: 'absolute' },
  labelRow: { position: 'absolute', flexDirection: 'row' },
  tag: { fontFamily: mono, fontWeight: '700', color: colors.ink, paddingHorizontal: 6, paddingVertical: 4, lineHeight: undefined },
  detail: { fontFamily: mono, fontWeight: '500', backgroundColor: colors.scrim, paddingHorizontal: 6, paddingVertical: 4 },
  standby: { position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, alignItems: 'center', justifyContent: 'center', padding: 14 },
  reticle: { width: 38, height: 38, borderWidth: 1, borderColor: colors.line, marginBottom: 18 },
  reticleH: { position: 'absolute', width: 18, height: 1, top: 18, left: 9, backgroundColor: colors.accent },
  reticleV: { position: 'absolute', height: 18, width: 1, top: 9, left: 18, backgroundColor: colors.accent },
  standbyTitle: { fontFamily: mono, fontSize: 16, fontWeight: '500', color: colors.text },
  panelImmersive: { borderRightWidth: 1 },
  overlayTop: { position: 'absolute', left: 0, right: 0, top: 0, flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 6, padding: 8 },
  bottom: { position: 'absolute', left: 0, right: 0, bottom: 0, alignItems: 'flex-start' },
  summary: { margin: 8, paddingHorizontal: 8, paddingVertical: 6, backgroundColor: colors.scrim, fontFamily: mono, fontSize: 12, color: colors.text },
  note: { alignSelf: 'stretch', paddingHorizontal: 12, paddingVertical: 8, backgroundColor: colors.scrim, fontFamily: mono, fontSize: 11, color: colors.muted },
  noteWarn: { color: colors.amber },
});
