import * as Haptics from 'expo-haptics';
import { Platform } from 'react-native';

import type { VerdictKind } from '../core/types';

const enabled = Platform.OS !== 'web';
const quiet = () => {};

/**
 * The operator looks at the drone, not at the screen: a tap that starts a challenge, a refused tap and the verdict
 * are felt as well as drawn. Failures are ignored (no vibrator, haptics off in system settings).
 */
export const haptics = {
  challenge(): void {
    if (enabled) Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium).catch(quiet);
  },
  refused(): void {
    if (enabled) Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning).catch(quiet);
  },
  verdict(kind: VerdictKind): void {
    if (!enabled) return;
    const type = kind === 'FRIEND' ? Haptics.NotificationFeedbackType.Success : Haptics.NotificationFeedbackType.Error;
    Haptics.notificationAsync(type).catch(quiet);
  },
};
