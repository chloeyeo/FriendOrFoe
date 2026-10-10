import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import {
  BackHandler,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  useWindowDimensions,
  View,
  type KeyboardTypeOptions,
} from 'react-native';

import { FofUsb } from '../../modules/fof-native';
import { availableDetectors, validate, type DetectorKind, type Settings, type SignalKindSetting } from '../app/settings';
import { colors, mono } from './theme';

type NumKey = 'threads' | 'intervalMs' | 'challengerId' | 'personThreshold' | 'droneThreshold' | 'rttBudgetMs' | 'rssiFloorDbm';
const NUM_KEYS: NumKey[] = ['threads', 'intervalMs', 'challengerId', 'personThreshold', 'droneThreshold', 'rttBudgetMs', 'rssiFloorDbm'];

/** The phone keyboard per field: digits only, digits with a decimal point, or (RSSI) digits with a minus sign. */
const KEYBOARD: Record<NumKey, KeyboardTypeOptions> = {
  threads: 'number-pad',
  intervalMs: 'number-pad',
  challengerId: 'number-pad',
  personThreshold: 'decimal-pad',
  droneThreshold: 'decimal-pad',
  rttBudgetMs: 'number-pad',
  rssiFloorDbm: Platform.OS === 'ios' ? 'numbers-and-punctuation' : 'numeric',
};

interface Props {
  settings: Settings;
  modelDirHint: string;
  onSave: (s: Settings) => void;
  onCancel: () => void;
  /** Phone-sized: tighter padding; fields stack in one column on a narrow window. */
  compact?: boolean;
}

/** Settings over the stored values (the native app's SettingsScreen), validated before Save. */
export function SettingsScreen({ settings, modelDirHint, onSave, onCancel, compact = false }: Props) {
  const { width } = useWindowDimensions();
  const oneColumn = width < 560;

  // The Android back button closes Settings instead of leaving the app.
  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      onCancel();
      return true;
    });
    return () => sub.remove();
  }, [onCancel]);

  const [draft, setDraft] = useState<Settings>(settings);
  const [numbers, setNumbers] = useState<Record<NumKey, string>>(
    () => Object.fromEntries(NUM_KEYS.map((k) => [k, String(settings[k])])) as Record<NumKey, string>,
  );
  const parsed: Settings = { ...draft, ...Object.fromEntries(NUM_KEYS.map((k) => [k, Number(numbers[k].replace('−', '-'))])) };
  const errors = validate(parsed);
  const valid = Object.keys(errors).length === 0;
  const set = <K extends keyof Settings>(key: K, value: Settings[K]) => setDraft((d) => ({ ...d, [key]: value }));

  const DETECTOR_LABELS: Record<DetectorKind, string> = {
    clikart: 'On-device ClikaRT',
    http: 'Laptop serve',
    coreml: 'On-device Core ML',
    fake: 'Simulated',
  };
  const detectors: { value: DetectorKind; label: string; enabled: boolean }[] = availableDetectors().map((value) => ({
    value,
    label: DETECTOR_LABELS[value],
    enabled: true,
  }));
  const signals: { value: SignalKindSetting; label: string; enabled: boolean }[] = [
    { value: 'usb', label: 'Reader gadget (USB)', enabled: FofUsb !== null },
    { value: 'fake', label: 'Simulated gadget', enabled: true },
  ];

  const num = (key: NumKey, label: string, hint?: string) => (
    <Field label={label} error={errors[key]} hint={hint}>
      <TextInput
        style={[styles.input, errors[key] && styles.inputError]}
        value={numbers[key]}
        onChangeText={(t) => setNumbers((n) => ({ ...n, [key]: t }))}
        keyboardType={KEYBOARD[key]}
        returnKeyType="done"
        selectTextOnFocus
        autoCorrect={false}
      />
    </Field>
  );

  return (
    <KeyboardAvoidingView style={styles.screen} behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
      <View style={styles.bar}>
        <Text style={styles.title}>Settings</Text>
        <View style={styles.barActions}>
          <Button label="Cancel" onPress={onCancel} />
          <Button label="Save" primary disabled={!valid} onPress={() => valid && onSave(parsed)} />
        </View>
      </View>
      <ScrollView contentContainerStyle={[styles.body, compact && styles.bodyCompact]} keyboardShouldPersistTaps="handled">
        <Section title="Detection">
          <Field label="Detector">
            <Segmented options={detectors} value={draft.detector} onChange={(v) => set('detector', v)} />
          </Field>
          {draft.detector === 'clikart' ? (
            <Field label="Model directory" hint={`blank = ${modelDirHint}`}>
              <TextInput style={styles.input} value={draft.modelDir} onChangeText={(t) => set('modelDir', t)} autoCapitalize="none" autoCorrect={false} placeholder={modelDirHint} placeholderTextColor={colors.muted} />
            </Field>
          ) : null}
          {draft.detector === 'http' ? (
            <Field label="Serve URL" error={errors.serveUrl} hint="clikart-cli serve --offline on the laptop">
              <TextInput style={[styles.input, errors.serveUrl && styles.inputError]} value={draft.serveUrl} onChangeText={(t) => set('serveUrl', t)} autoCapitalize="none" autoCorrect={false} />
            </Field>
          ) : null}
          <Row stacked={oneColumn}>
            {num('threads', 'ClikaRT threads')}
            {num('intervalMs', 'Frame interval (ms)')}
          </Row>
          <Row stacked={oneColumn}>
            {num('personThreshold', 'Person threshold')}
            {num('droneThreshold', 'Drone threshold')}
          </Row>
        </Section>
        <Section title="Identification">
          <Field label="Signal source">
            <Segmented options={signals} value={draft.signal} onChange={(v) => set('signal', v)} />
          </Field>
          <Row stacked={oneColumn}>
            {num('challengerId', 'Challenger id')}
            <Field label="Accepted drone ids" hint="comma-separated; blank accepts any">
              <TextInput style={styles.input} value={draft.acceptedDroneIds} onChangeText={(t) => set('acceptedDroneIds', t)} autoCapitalize="characters" autoCorrect={false} />
            </Field>
          </Row>
          <Row stacked={oneColumn}>
            {num('rttBudgetMs', 'Round-trip budget (ms)')}
            {num('rssiFloorDbm', 'RSSI floor (dBm)')}
          </Row>
          <View style={styles.switchRow}>
            <View style={styles.switchText}>
              <Text style={styles.label}>Strict physical checks</Text>
              <Text style={styles.hint}>An answer without RSSI fails instead of being skipped.</Text>
            </View>
            <Switch value={draft.strictPhysical} onValueChange={(v) => set('strictPhysical', v)} trackColor={{ true: colors.accent, false: colors.line }} thumbColor={colors.text} />
          </View>
        </Section>
        <Section title="Laptop">
          <Field label="Dashboard URL" error={errors.dashboardUrl} hint="blank disables posting · e.g. http://192.168.43.100:8081">
            <TextInput style={[styles.input, errors.dashboardUrl && styles.inputError]} value={draft.dashboardUrl} onChangeText={(t) => set('dashboardUrl', t)} autoCapitalize="none" autoCorrect={false} />
          </Field>
        </Section>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

/** Fields share a row equally; on a narrow window the row stacks and each field takes the full width. */
const InRow = createContext(false);

function Row({ stacked, children }: { stacked: boolean; children: ReactNode }) {
  return (
    <View style={[styles.row, stacked && styles.rowStacked]}>
      <InRow.Provider value={!stacked}>{children}</InRow.Provider>
    </View>
  );
}

function Field({ label, hint, error, children }: { label: string; hint?: string; error?: string; children: ReactNode }) {
  const inRow = useContext(InRow);
  return (
    <View style={[styles.field, inRow && styles.fieldInRow]}>
      <Text style={styles.label}>{label}</Text>
      {children}
      {error ? <Text style={styles.error}>{error}</Text> : hint ? <Text style={styles.hint}>{hint}</Text> : null}
    </View>
  );
}

function Segmented<T extends string>({ options, value, onChange }: { options: { value: T; label: string; enabled: boolean }[]; value: T; onChange: (v: T) => void }) {
  return (
    <View style={styles.segmented}>
      {options.map((o, i) => (
        <Pressable
          key={o.value}
          disabled={!o.enabled}
          onPress={() => onChange(o.value)}
          android_ripple={{ color: 'rgba(210, 214, 219, 0.18)' }}
          accessibilityState={{ selected: o.value === value, disabled: !o.enabled }}
          style={[styles.segment, i < options.length - 1 && styles.segmentDivider, o.value === value && styles.segmentOn]}
        >
          <Text style={[styles.segmentText, o.value === value && styles.segmentTextOn, !o.enabled && styles.segmentTextOff]}>{o.label}</Text>
        </Pressable>
      ))}
    </View>
  );
}

function Button({ label, onPress, primary, disabled }: { label: string; onPress: () => void; primary?: boolean; disabled?: boolean }) {
  return (
    <Pressable onPress={onPress} disabled={disabled} accessibilityRole="button" style={[styles.button, primary && styles.buttonPrimary, disabled && styles.buttonDisabled]}>
      <Text style={[styles.buttonText, primary && styles.buttonTextPrimary]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.surface0 },
  bar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: 48, paddingLeft: 14, backgroundColor: colors.surface1, borderBottomWidth: 1, borderBottomColor: colors.line },
  title: { fontFamily: mono, fontSize: 16, fontWeight: '700', color: colors.text },
  barActions: { flexDirection: 'row', alignSelf: 'stretch' },
  button: { minWidth: 96, minHeight: 48, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 14, borderLeftWidth: 1, borderLeftColor: colors.line },
  buttonPrimary: { backgroundColor: colors.accent },
  buttonDisabled: { opacity: 0.4 },
  buttonText: { fontFamily: mono, fontSize: 13, color: colors.text },
  buttonTextPrimary: { color: colors.ink, fontWeight: '700' },
  body: { padding: 14, gap: 14, maxWidth: 900, width: '100%', alignSelf: 'center' },
  bodyCompact: { padding: 10, gap: 10 },
  section: { borderWidth: 1, borderColor: colors.line, backgroundColor: colors.surface1, padding: 12, gap: 12 },
  sectionTitle: { fontFamily: mono, fontSize: 11, color: colors.muted, textTransform: 'uppercase', letterSpacing: 0.5 },
  row: { flexDirection: 'row', gap: 12 },
  rowStacked: { flexDirection: 'column' },
  field: { gap: 6 },
  fieldInRow: { flex: 1 },
  label: { fontFamily: mono, fontSize: 12, fontWeight: '600', color: colors.text },
  hint: { fontFamily: mono, fontSize: 11, color: colors.muted },
  error: { fontFamily: mono, fontSize: 11, color: colors.red },
  input: { minHeight: 44, paddingHorizontal: 10, paddingVertical: 8, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.surface0, color: colors.text, fontFamily: mono, fontSize: 13 },
  inputError: { borderColor: colors.red },
  segmented: { flexDirection: 'row', borderWidth: 1, borderColor: colors.line },
  segment: { flex: 1, minHeight: 44, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 6, backgroundColor: colors.surface0 },
  segmentDivider: { borderRightWidth: 1, borderRightColor: colors.line },
  segmentOn: { backgroundColor: colors.accent },
  segmentText: { fontFamily: mono, fontSize: 12, color: colors.muted, textAlign: 'center' },
  segmentTextOn: { color: colors.ink, fontWeight: '700' },
  segmentTextOff: { opacity: 0.4 },
  switchRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  switchText: { flex: 1, gap: 4 },
});
