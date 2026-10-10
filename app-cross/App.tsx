import * as ScreenOrientation from 'expo-screen-orientation';
import { useKeepAwake } from 'expo-keep-awake';
import { NavigationBar } from 'expo-navigation-bar';
import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useState } from 'react';
import { Platform, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { loadSettings, saveSettings, type Settings } from './src/app/settings';
import { ObserverScreen } from './src/ui/ObserverScreen';
import { colors } from './src/ui/theme';

/** FriendOrFoe observer (React Native fallback of the native app): landscape, full screen, screen always on, dark. */
export default function App() {
  useKeepAwake();
  const [settings, setSettings] = useState<Settings | null>(null);

  useEffect(() => {
    void loadSettings().then(setSettings);
    if (Platform.OS !== 'web') ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.LANDSCAPE).catch(() => {});
  }, []);

  const update = useCallback((s: Settings) => {
    setSettings(s);
    void saveSettings(s);
  }, []);

  return (
    <SafeAreaProvider>
      <StatusBar hidden style="light" />
      {/* Immersive on phone A: the bars come back with a swipe from the edge. */}
      {Platform.OS === 'android' ? <NavigationBar hidden style="light" /> : null}
      {settings ? <ObserverScreen settings={settings} onSettings={update} /> : <View style={{ flex: 1, backgroundColor: colors.surface0 }} />}
    </SafeAreaProvider>
  );
}
