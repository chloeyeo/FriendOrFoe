import { memo, useMemo, useRef, useState, type ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View, type GestureResponderEvent, type LayoutChangeEvent, type NativeTouchEvent } from 'react-native';

import { FofCameraView, type CameraErrorEvent, type CameraFrameEvent, type GrayEvent } from '../../modules/fof-native';
import { isIdentifiable, type StatusUi, type TrackUi } from '../core/types';
import { compactLabel, tagFor, targetAt } from './overlayStyle';
import { colors, mono, toneColor } from './theme';
import { useNow } from './format';

export interface CameraProps {
  detector: 'clikart' | 'http' | 'coreml' | 'none';
  /** Native model tags to run, from Settings → which classes to detect. */
  models: string[];
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
  onTapTarget: (id: number) => void;
  /**
   * `panel`: the web page's pane with its heading row. `immersive`: the phone layout — no heading, the camera takes
   * the full height and [overlay] (brand + status) floats on its top edge, the object summary on its bottom edge.
   */
  variant?: 'panel' | 'immersive';
  overlay?: ReactNode;
  /** Phone-sized: larger tags relative to the view and a bigger tap slop. */
  compact?: boolean;
  /** The camera zoomed by this factor (new / old), so the session can move its boxes with the picture. */
  onZoom?: (factor: number) => void;
  /** When the results panel is closed, a button on the camera opens it again, with the identification count. */
  panelOpen?: boolean;
  onOpenPanel?: () => void;
  resultCount?: number;
}

const ZOOM_STEP = 1.5;

function spread(touches: NativeTouchEvent[]): number {
  return Math.hypot(touches[0].pageX - touches[1].pageX, touches[0].pageY - touches[1].pageY);
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
export function ObservationPanel({
  tracks,
  status,
  camera,
  onPreviewLayout,
  onTapTarget,
  variant = 'panel',
  overlay,
  compact = false,
  onZoom,
  panelOpen = true,
  onOpenPanel,
  resultCount = 0,
}: Props) {
  const immersive = variant === 'immersive';
  const [zoom, setZoom] = useState(1);
  const [zoomRange, setZoomRange] = useState({ min: 1, max: 1 });
  const zoomRef = useRef(1);
  const gesture = useRef({ pinch: false, moved: false, startSpread: 0, startZoom: 1, x0: 0, y0: 0 });

  /** Digital zoom of the capture (pinch or the +/- buttons); the session moves its boxes by the same factor. */
  const zoomTo = (target: number) => {
    const next = clamp(zoomRange.min, target, zoomRange.max);
    const factor = next / zoomRef.current;
    if (Math.abs(factor - 1) < 0.005) return;
    zoomRef.current = next;
    setZoom(next);
    onZoom?.(factor);
  };
  const [size, setSize] = useState({ width: 0, height: 0 });
  const challenging = tracks.some((t) => t.iff.kind === 'CHALLENGED');
  const now = useNow(challenging ? 250 : 1000);

  const onLayout = (e: LayoutChangeEvent) => {
    const { width, height } = e.nativeEvent.layout;
    setSize({ width, height });
    onPreviewLayout(width, height);
  };

  const point = (e: GestureResponderEvent) => {
    const ne = e.nativeEvent as GestureResponderEvent['nativeEvent'] & { offsetX?: number; offsetY?: number };
    return { x: ne.locationX ?? ne.offsetX ?? 0, y: ne.locationY ?? ne.offsetY ?? 0 };
  };

  // One responder for both gestures: a still single touch is a tap (challenge the drone under it), two fingers pinch
  // the camera zoom.
  const beginPinch = (touches: NativeTouchEvent[]) => {
    const g = gesture.current;
    g.pinch = true;
    g.startSpread = spread(touches);
    g.startZoom = zoomRef.current;
  };
  const responder = {
    onStartShouldSetResponder: () => true,
    onMoveShouldSetResponder: () => true,
    onResponderTerminationRequest: () => false,
    onResponderGrant: (e: GestureResponderEvent) => {
      const g = gesture.current;
      const p = point(e);
      g.pinch = false;
      g.moved = false;
      g.x0 = p.x;
      g.y0 = p.y;
      if (camera && e.nativeEvent.touches.length >= 2) beginPinch(e.nativeEvent.touches);
    },
    onResponderMove: (e: GestureResponderEvent) => {
      const g = gesture.current;
      const touches = e.nativeEvent.touches;
      if (camera && touches.length >= 2) {
        if (!g.pinch) beginPinch(touches);
        if (g.startSpread > 0) zoomTo((g.startZoom * spread(touches)) / g.startSpread);
        g.moved = true;
        return;
      }
      const p = point(e);
      if (Math.abs(p.x - g.x0) > 12 || Math.abs(p.y - g.y0) > 12) g.moved = true;
    },
    onResponderRelease: () => {
      const g = gesture.current;
      if (!g.pinch && !g.moved) {
        // Inference runs every few seconds on phone A, so the target may have moved since its box was drawn: be generous.
        const hit = targetAt(tracks, g.x0, g.y0, compact ? 36 : 24);
        if (hit) onTapTarget(hit.id);
      }
      g.pinch = false;
    },
  };

  const hint = tracks.some((t) => isIdentifiable(t.cls) && t.iff.kind === 'UNCHALLENGED') ? ' · tap to identify' : '';
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
            models={camera.models}
            zoom={zoom}
            onCameraReady={(e) => setZoomRange({ min: e.nativeEvent.minZoom, max: Math.max(e.nativeEvent.minZoom, e.nativeEvent.maxZoom) })}
            onFrame={(e) => camera.onFrame(e.nativeEvent)}
            onGray={(e) => camera.onGray(e.nativeEvent)}
            onCameraError={(e) => camera.onCameraError(e.nativeEvent)}
          />
        ) : null}
        <View style={StyleSheet.absoluteFill} {...responder} accessible accessibilityLabel="Observation. Tap a drone or a person to identify it; pinch to zoom.">
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
        </View>
        {overlay ? (
          <View style={styles.overlayTop} pointerEvents="none">
            {overlay}
          </View>
        ) : null}
        {camera && zoomRange.max > zoomRange.min ? (
          <View style={styles.zoom}>
            <Pressable style={styles.zoomButton} onPress={() => zoomTo(zoom * ZOOM_STEP)} accessibilityRole="button" accessibilityLabel="Zoom in">
              <Text style={styles.zoomGlyph}>+</Text>
            </Pressable>
            <Pressable style={styles.zoomButton} onPress={() => zoomTo(zoomRange.min)} accessibilityRole="button" accessibilityLabel={`Zoom ${zoom.toFixed(1)} times. Reset`}>
              <Text style={styles.zoomLabel}>{zoom.toFixed(1)}×</Text>
            </Pressable>
            <Pressable style={styles.zoomButton} onPress={() => zoomTo(zoom / ZOOM_STEP)} accessibilityRole="button" accessibilityLabel="Zoom out">
              <Text style={styles.zoomGlyph}>−</Text>
            </Pressable>
          </View>
        ) : null}
        {!panelOpen && onOpenPanel ? (
          <Pressable style={styles.openPanel} onPress={onOpenPanel} accessibilityRole="button" accessibilityLabel="Show results panel">
            <Text style={styles.openPanelText}>« Results{resultCount ? ` · ${resultCount}` : ''}</Text>
          </Pressable>
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

/**
 * Drawn bottom to top: people and birds, then unasked drones, then whatever was challenged or answered (drone or
 * person). The tag the observer reads is on top: the answer they asked for, then the drones still to ask about.
 */
function drawOrder(tracks: TrackUi[]): TrackUi[] {
  const rank = (t: TrackUi) => (t.iff.kind !== 'UNCHALLENGED' ? 2 : t.cls === 'DRONE' ? 1 : 0);
  return [0, 1, 2].flatMap((r) => tracks.filter((t) => rank(t) === r));
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
      style={[styles.ring, { left, top, width: right - left, height: bottom - top, borderColor: color, borderWidth: track.cls === 'DRONE' || track.iff.kind !== 'UNCHALLENGED' ? 2 : 1 }]}
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
  zoom: { position: 'absolute', right: 8, top: '50%', marginTop: -70, borderWidth: 1, borderColor: colors.line },
  zoomButton: { width: 48, height: 46, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.scrim },
  zoomGlyph: { fontFamily: mono, fontSize: 22, color: colors.text },
  zoomLabel: { fontFamily: mono, fontSize: 12, fontWeight: '700', color: colors.text },
  openPanel: { position: 'absolute', top: 8, right: 8, minHeight: 40, justifyContent: 'center', paddingHorizontal: 12, backgroundColor: colors.accent },
  openPanelText: { fontFamily: mono, fontSize: 12, fontWeight: '700', color: colors.ink },
});
