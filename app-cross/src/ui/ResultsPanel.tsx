import { useRef, useState } from 'react';
import { FlatList, Pressable, StyleSheet, Text, View } from 'react-native';

import type { EventUi, SignalUi } from '../core/types';
import { FAKE_MODES, type FakeMode } from '../signal/fakeSignalSource';
import type { GadgetLinkState } from '../signal/usbGadgetSignalSource';
import { ageText, clock, reasonOf, targetOf, useNow } from './format';
import { dbm } from './overlayStyle';
import { colors, mono, verdictColor } from './theme';

type Tab = 'results' | 'activity' | 'gadget';

interface Props {
  events: EventUi[];
  signal: SignalUi[];
  gadget: GadgetLinkState;
  signalKind: 'usb' | 'fake';
  fakeMode: FakeMode;
  onFakeMode: (mode: FakeMode) => void;
  onConnectGadget: () => void;
  onOpenSettings: () => void;
  onReset: () => void;
  /** Fixed column width beside the camera; omitted in the stacked (portrait) layout, where it fills the rest. */
  width?: number;
  /** Phone-sized: no heading row, short tab labels. */
  compact?: boolean;
}

const FAKE_HINT: Record<FakeMode, string> = {
  HONEST: 'FRIEND',
  REPLAY: 'FOE · replayed nonce',
  RELAY: 'FOE · position mismatch',
  LATE: 'FOE · late response',
  SILENT: 'UNKNOWN · no response',
  IMPOSTOR: 'FOE · bad tag',
};

const RIPPLE = { color: 'rgba(210, 214, 219, 0.18)' };

/** Stable keys for log rows (they carry no id; the same object stays in the log until it scrolls out). */
const keys = new WeakMap<EventUi, string>();
let nextKey = 1;
function keyOf(e: EventUi): string {
  let k = keys.get(e);
  if (!k) keys.set(e, (k = String(nextKey++)));
  return k;
}

/** The right-hand column: the web dashboard's result list, plus the phone's gadget panel and its two actions. */
export function ResultsPanel(props: Props) {
  const [tab, setTab] = useState<Tab>('results');
  const now = useNow(1000);
  const visible = tab === 'results' ? props.events.filter((e) => e.verdict) : props.events;

  const compact = props.compact ?? false;
  return (
    <View style={[styles.panel, props.width != null ? { width: props.width } : styles.panelStacked]}>
      {compact ? null : (
        <View style={styles.heading}>
          <Text style={styles.h2}>{tab === 'gadget' ? 'Gadget' : 'Recent results'}</Text>
        </View>
      )}
      <View style={styles.filters} accessibilityRole="tablist">
        <FilterButton label={compact ? 'Results' : 'Identifications'} pressed={tab === 'results'} onPress={() => setTab('results')} />
        <FilterButton label={compact ? 'Activity' : 'All activity'} pressed={tab === 'activity'} onPress={() => setTab('activity')} />
        <FilterButton label="Gadget" pressed={tab === 'gadget'} onPress={() => setTab('gadget')} last />
      </View>
      {tab === 'gadget' ? (
        <GadgetView {...props} now={now} />
      ) : (
        <FlatList
          style={styles.list}
          data={visible}
          keyExtractor={keyOf}
          renderItem={({ item, index }) => (item.verdict ? <ResultRow event={item} now={now} even={index % 2 === 1} /> : <ActivityRow event={item} now={now} />)}
          ListEmptyComponent={
            <View style={styles.empty}>
              <Text style={styles.emptyTitle}>{tab === 'activity' ? 'Waiting for activity' : 'No identifications yet'}</Text>
              <Text style={styles.emptyText}>{tab === 'activity' ? 'Detections and updates will appear here.' : 'Tap a drone ring to run an identification check.'}</Text>
            </View>
          }
        />
      )}
      <View style={styles.actions}>
        <ActionButton label="Settings" onPress={props.onOpenSettings} />
        <ResetButton onReset={props.onReset} />
      </View>
    </View>
  );
}

function FilterButton({ label, pressed, onPress, last }: { label: string; pressed: boolean; onPress: () => void; last?: boolean }) {
  return (
    <Pressable
      accessibilityRole="tab"
      accessibilityState={{ selected: pressed }}
      onPress={onPress}
      android_ripple={RIPPLE}
      style={({ pressed: down }) => [styles.filter, !last && styles.filterDivider, (pressed || down) && styles.filterOn]}
    >
      <Text style={[styles.filterText, pressed && styles.filterTextOn]}>{label}</Text>
    </Pressable>
  );
}

function ResultRow({ event, now, even }: { event: EventUi; now: number; even: boolean }) {
  const [open, setOpen] = useState(false);
  const tone = verdictColor(event.verdict!);
  return (
    <View style={[styles.result, { borderLeftColor: tone }, even && styles.resultEven]}>
      <Pressable onPress={() => setOpen(!open)} android_ripple={RIPPLE} accessibilityRole="button" accessibilityState={{ expanded: open }} style={styles.summary}>
        <View style={styles.resultTop}>
          <Text style={[styles.chip, { backgroundColor: tone }]}>{event.verdict}</Text>
          <Text style={styles.target}>{targetOf(event)}</Text>
          <Text style={styles.age}>{ageText(now, event.t)}</Text>
        </View>
        <View style={styles.resultBottom}>
          <Text style={styles.reason}>{reasonOf(event)}</Text>
          <View style={[styles.expand, open && styles.expandOpen]} />
        </View>
      </Pressable>
      {open ? (
        <View style={styles.dl}>
          <Detail term="Time" value={clock(event.t)} />
          {event.trackId != null ? <Detail term="Track" value={`#${event.trackId}`} /> : null}
          {event.rssiDbm != null ? <Detail term="Signal" value={dbm(event.rssiDbm)} /> : null}
          {event.roundTripMs != null ? <Detail term="Response time" value={`${event.roundTripMs} ms`} /> : null}
          {event.score != null ? <Detail term="Detection confidence" value={`${Math.round(event.score * 100)}%`} /> : null}
        </View>
      ) : null}
    </View>
  );
}

function Detail({ term, value }: { term: string; value: string }) {
  return (
    <View style={styles.dlRow}>
      <Text style={styles.dt}>{term}</Text>
      <Text style={styles.dd}>{value}</Text>
    </View>
  );
}

function ActivityRow({ event, now }: { event: EventUi; now: number }) {
  const title = event.cls ? `${event.cls.charAt(0)}${event.cls.slice(1).toLowerCase()} detected` : event.text;
  return (
    <View style={styles.activity}>
      <View style={styles.activityTop}>
        <Text style={styles.activityText}>{title}</Text>
        <Text style={styles.age}>{ageText(now, event.t)}</Text>
      </View>
      {event.trackId != null ? (
        <Text style={styles.activitySmall}>
          Track #{event.trackId}
          {event.score != null ? ` · ${Math.round(event.score * 100)}% confidence` : ''}
        </Text>
      ) : null}
    </View>
  );
}

function GadgetView({ gadget, signal, signalKind, fakeMode, onFakeMode, onConnectGadget, now }: Props & { now: number }) {
  return (
    <FlatList
      style={styles.list}
      data={signal}
      keyExtractor={(s) => String(s.t)}
      ListHeaderComponent={
        <View>
          <View style={styles.gadgetHead}>
            <View style={[styles.indicator, { backgroundColor: gadget.connected ? colors.green : colors.amber }]} />
            <Text style={styles.gadgetTitle}>{signalKind === 'fake' ? 'Simulated gadget' : gadget.connected ? 'Reader gadget connected' : 'Reader gadget not connected'}</Text>
          </View>
          {gadget.note ? <Text style={styles.gadgetNote}>{gadget.note}</Text> : null}
          {signalKind === 'usb' && !gadget.connected ? (
            <View style={styles.gadgetPad}>
              <ActionButton label="Connect gadget" onPress={onConnectGadget} />
            </View>
          ) : null}
          {signalKind === 'fake' ? (
            <View>
              <Text style={styles.sectionLabel}>Drone-side mode</Text>
              <View style={styles.modes}>
                {FAKE_MODES.map((m) => (
                  <Pressable key={m} onPress={() => onFakeMode(m)} android_ripple={RIPPLE} style={[styles.mode, m === fakeMode && styles.modeOn]} accessibilityState={{ selected: m === fakeMode }}>
                    <Text style={[styles.modeName, m === fakeMode && styles.modeNameOn]}>{m}</Text>
                    <Text style={[styles.modeHint, m === fakeMode && styles.modeNameOn]}>{FAKE_HINT[m]}</Text>
                  </Pressable>
                ))}
              </View>
            </View>
          ) : null}
          <Text style={styles.sectionLabel}>Answers after the phone's re-checks</Text>
        </View>
      }
      renderItem={({ item }) => (
        <View style={[styles.signalRow, { borderLeftColor: item.verdict ? verdictColor(item.verdict) : colors.line }]}>
          <View style={styles.activityTop}>
            <Text style={styles.activityText}>
              {item.verdict ?? '—'} · {item.droneId ?? 'no id'}
              {item.reason ? ` · ${item.reason}` : ''}
            </Text>
            <Text style={styles.age}>{ageText(now, item.t)}</Text>
          </View>
          <Text style={styles.activitySmall}>
            {item.rssiDbm != null ? dbm(item.rssiDbm) : 'no RSSI'} · {item.roundTripMs != null ? `${item.roundTripMs} ms` : 'no answer'}
          </Text>
        </View>
      )}
      ListEmptyComponent={<Text style={styles.gadgetNote}>No answers yet.</Text>}
    />
  );
}

function ActionButton({ label, onPress, danger }: { label: string; onPress: () => void; danger?: boolean }) {
  return (
    <Pressable onPress={onPress} android_ripple={RIPPLE} accessibilityRole="button" style={({ pressed }) => [styles.action, pressed && styles.actionDown, danger && styles.actionDanger]}>
      <Text style={[styles.actionText, danger && styles.actionTextDanger]}>{label}</Text>
    </Pressable>
  );
}

/** Reset needs a second tap within 3 s, so a rehearsal is never wiped by accident. */
function ResetButton({ onReset }: { onReset: () => void }) {
  const [armed, setArmed] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const press = () => {
    if (timer.current) clearTimeout(timer.current);
    if (armed) {
      setArmed(false);
      onReset();
      return;
    }
    setArmed(true);
    timer.current = setTimeout(() => setArmed(false), 3000);
  };
  return <ActionButton label={armed ? 'Tap again to reset' : 'Reset'} onPress={press} danger={armed} />;
}

const styles = StyleSheet.create({
  panel: { minWidth: 0, backgroundColor: colors.surface0 },
  panelStacked: { flex: 1, borderTopWidth: 1, borderTopColor: colors.line },
  heading: { minHeight: 38, paddingHorizontal: 14, paddingVertical: 8, justifyContent: 'center', backgroundColor: colors.surface2, borderBottomWidth: 1, borderBottomColor: colors.line },
  h2: { fontFamily: mono, fontSize: 13, fontWeight: '600', color: colors.text },
  filters: { flexDirection: 'row', borderBottomWidth: 1, borderBottomColor: colors.line },
  filter: { flex: 1, minHeight: 44, paddingHorizontal: 6, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.surface1 },
  filterDivider: { borderRightWidth: 1, borderRightColor: colors.line },
  filterOn: { backgroundColor: colors.accent },
  filterText: { fontFamily: mono, fontSize: 12, color: colors.muted },
  filterTextOn: { color: colors.ink, fontWeight: '700' },
  list: { flex: 1 },
  result: { borderBottomWidth: 1, borderBottomColor: colors.line, borderLeftWidth: 3, backgroundColor: colors.surface1 },
  resultEven: { backgroundColor: colors.surface0 },
  summary: { paddingHorizontal: 12, paddingVertical: 12 },
  resultTop: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  chip: { fontFamily: mono, fontSize: 12, fontWeight: '800', color: colors.ink, paddingHorizontal: 5, paddingVertical: 3 },
  target: { flexShrink: 1, fontFamily: mono, fontSize: 14, fontWeight: '600', color: colors.text },
  age: { marginLeft: 'auto', fontFamily: mono, fontSize: 11, color: colors.muted },
  resultBottom: { marginTop: 8, flexDirection: 'row', alignItems: 'center', gap: 10 },
  reason: { flexShrink: 1, fontFamily: mono, fontSize: 13, lineHeight: 19, color: colors.text },
  expand: { marginLeft: 'auto', width: 7, height: 7, borderRightWidth: 1, borderBottomWidth: 1, borderColor: colors.muted, transform: [{ rotate: '45deg' }] },
  expandOpen: { transform: [{ rotate: '225deg' }] },
  dl: { paddingHorizontal: 12, paddingVertical: 8, borderTopWidth: 1, borderTopColor: colors.line, backgroundColor: colors.surface0 },
  dlRow: { flexDirection: 'row', justifyContent: 'space-between', gap: 10, paddingVertical: 4 },
  dt: { fontFamily: mono, fontSize: 12, color: colors.muted },
  dd: { fontFamily: mono, fontSize: 12, color: colors.text, textAlign: 'right' },
  activity: { paddingHorizontal: 15, paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: colors.line },
  activityTop: { flexDirection: 'row', alignItems: 'baseline', gap: 10 },
  activityText: { flexShrink: 1, fontFamily: mono, fontSize: 12, lineHeight: 18, color: colors.muted },
  activitySmall: { marginTop: 4, fontFamily: mono, fontSize: 11, color: colors.muted },
  empty: { paddingHorizontal: 16, paddingVertical: 24 },
  emptyTitle: { fontFamily: mono, fontSize: 14, fontWeight: '600', color: colors.text },
  emptyText: { marginTop: 8, fontFamily: mono, fontSize: 12, lineHeight: 19, color: colors.muted },
  gadgetHead: { flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 14, paddingTop: 12 },
  indicator: { width: 6, height: 6 },
  gadgetTitle: { fontFamily: mono, fontSize: 13, fontWeight: '600', color: colors.text },
  gadgetNote: { paddingHorizontal: 14, paddingTop: 6, fontFamily: mono, fontSize: 12, color: colors.muted },
  gadgetPad: { paddingHorizontal: 14, paddingTop: 10, flexDirection: 'row' },
  sectionLabel: { paddingHorizontal: 14, paddingTop: 14, paddingBottom: 6, fontFamily: mono, fontSize: 11, color: colors.muted, textTransform: 'uppercase', letterSpacing: 0.5 },
  modes: { flexDirection: 'row', flexWrap: 'wrap', borderTopWidth: 1, borderLeftWidth: 1, borderColor: colors.line, marginHorizontal: 14 },
  mode: { width: '50%', minHeight: 48, justifyContent: 'center', paddingHorizontal: 8, paddingVertical: 8, borderRightWidth: 1, borderBottomWidth: 1, borderColor: colors.line, backgroundColor: colors.surface1 },
  modeOn: { backgroundColor: colors.accent },
  modeName: { fontFamily: mono, fontSize: 12, fontWeight: '700', color: colors.text },
  modeHint: { marginTop: 2, fontFamily: mono, fontSize: 10, color: colors.muted },
  modeNameOn: { color: colors.ink },
  signalRow: { paddingHorizontal: 12, paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: colors.line, borderLeftWidth: 3 },
  actions: { flexDirection: 'row', borderTopWidth: 1, borderTopColor: colors.line },
  action: { flex: 1, minHeight: 48, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 10, backgroundColor: colors.surface1, borderRightWidth: 1, borderRightColor: colors.line },
  actionDown: { backgroundColor: colors.surface2 },
  actionDanger: { backgroundColor: colors.red },
  actionText: { fontFamily: mono, fontSize: 12, color: colors.text },
  actionTextDanger: { color: colors.ink, fontWeight: '700' },
});
