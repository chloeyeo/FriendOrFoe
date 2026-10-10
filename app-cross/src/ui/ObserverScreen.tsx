import { useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { FofRuntime, hasNativeCamera } from '../../modules/fof-native';
import type { SignalUi } from '../core/types';
import { modelsFor, type Settings } from '../app/settings';
import { useSession } from '../app/useSession';
import { haptics } from './haptics';
import { useLayout } from './layout';
import { Brand, Masthead, StatusItems } from './Masthead';
import { ObservationPanel, type CameraProps } from './ObservationPanel';
import { ResultsPanel } from './ResultsPanel';
import { SettingsScreen } from './SettingsScreen';
import { colors } from './theme';

/**
 * The one screen: the observation pane and the results column, Settings on top when open. The arrangement follows
 * the window (see `layout.ts`): on phone A held landscape the camera gets the full height with the status floating on
 * it; upright it stacks; on a tablet or desktop it is the web page's masthead + two columns.
 */
export function ObserverScreen({ settings, onSettings }: { settings: Settings; onSettings: (s: Settings) => void }) {
  const session = useSession(settings);
  const [settingsOpen, setSettingsOpen] = useState(false);
  // The results column can be closed so the camera takes the whole screen; a button on the camera reopens it.
  const [panelOpen, setPanelOpen] = useState(true);
  const insets = useSafeAreaInsets();
  const layout = useLayout();
  const { state } = session;
  const resultCount = state.events.filter((e) => e.verdict).length;
  const gadgetLabel = settings.signal === 'fake' ? 'Sim gadget' : 'Reader';

  const camera: CameraProps | null =
    hasNativeCamera && settings.detector !== 'fake' && session.cameraGranted
      ? {
          detector: settings.detector,
          models: modelsFor(settings),
          serveUrl: settings.serveUrl,
          intervalMs: settings.intervalMs,
          thumbnailUrl: settings.dashboardUrl.trim(),
          onFrame: session.onFrame,
          onGray: session.onGray,
          onCameraError: session.onCameraError,
        }
      : null;

  const onTapTarget = useCallback(
    (id: number) => {
      if (session.challenge(id)) haptics.challenge();
      else haptics.refused();
    },
    [session],
  );

  // Feel the verdict: the newest gadget-panel row is the answer that just landed.
  const lastAnswer = useRef<SignalUi | undefined>(state.signal[0]);
  useEffect(() => {
    const latest = state.signal[0];
    if (latest && latest !== lastAnswer.current && latest.verdict) haptics.verdict(latest.verdict);
    lastAnswer.current = latest;
  }, [state.signal]);

  const observation = (variant: 'panel' | 'immersive') => (
    <ObservationPanel
      variant={variant}
      compact={layout.compact}
      tracks={state.tracks}
      status={state.status}
      camera={camera}
      onPreviewLayout={session.onPreviewLayout}
      onTapTarget={onTapTarget}
      onZoom={session.zoomBy}
      panelOpen={panelOpen}
      onOpenPanel={() => setPanelOpen(true)}
      resultCount={resultCount}
      overlay={
        variant === 'immersive' ? (
          <>
            <View style={styles.brandChip}>
              <Brand size={15} />
            </View>
            <StatusItems status={state.status} gadgetLabel={gadgetLabel} compact overlay />
          </>
        ) : undefined
      }
    />
  );

  const results = (width?: number) => (
    <ResultsPanel
      width={width}
      compact={layout.compact}
      events={state.events}
      signal={state.signal}
      gadget={session.gadget}
      signalKind={settings.signal}
      fakeMode={settings.fakeMode}
      onFakeMode={(fakeMode) => onSettings({ ...settings, fakeMode })}
      onConnectGadget={session.connectGadget}
      onOpenSettings={() => setSettingsOpen(true)}
      onReset={session.reset}
      onClose={() => setPanelOpen(false)}
    />
  );

  const padding = { paddingLeft: insets.left, paddingRight: insets.right, paddingTop: insets.top, paddingBottom: insets.bottom };

  return (
    <View style={[styles.root, padding]}>
      {layout.mode === 'phone' ? (
        <View style={styles.row}>
          {observation('immersive')}
          {panelOpen ? results(layout.resultsWidth) : null}
        </View>
      ) : layout.mode === 'portrait' ? (
        <>
          <Masthead status={state.status} gadgetLabel={gadgetLabel} compact />
          <View style={panelOpen ? { height: Math.round(Math.max(220, Math.min(layout.height * 0.45, layout.width * 0.8))) } : styles.fill}>
            {observation('panel')}
          </View>
          {panelOpen ? results() : null}
        </>
      ) : (
        <>
          <Masthead status={state.status} gadgetLabel={gadgetLabel} />
          <View style={styles.row}>
            {observation('panel')}
            {panelOpen ? results(layout.resultsWidth) : null}
          </View>
        </>
      )}
      {settingsOpen ? (
        <View style={[StyleSheet.absoluteFill, padding, styles.sheet]}>
          <SettingsScreen
            settings={settings}
            modelDirHints={FofRuntime?.defaultModelDirs() ?? { drone: 'models/rtdetr_v2_drone_rahul_320', person: 'models/dfine_small_coco_320' }}
            compact={layout.compact}
            onCancel={() => setSettingsOpen(false)}
            onSave={(s) => {
              onSettings(s);
              setSettingsOpen(false);
            }}
          />
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.surface0 },
  row: { flex: 1, flexDirection: 'row', minHeight: 0 },
  fill: { flex: 1 },
  brandChip: { backgroundColor: colors.scrim, paddingHorizontal: 8, paddingVertical: 3 },
  sheet: { backgroundColor: colors.surface0 },
});
