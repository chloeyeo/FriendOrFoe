import * as Network from 'expo-network';
import { Platform } from 'react-native';

/**
 * The OFFLINE badge rule (app/OfflineRule.kt, D-12): airplane mode on AND no validated network. Wi-Fi re-enabled
 * under airplane mode for the hotspot LAN has no internet, so the badge stays OFFLINE. Where the platform cannot
 * report airplane mode (web, iOS) the badge follows internet reachability alone.
 */
export function isOffline(airplaneOn: boolean | null, internetReachable: boolean | null): boolean {
  if (airplaneOn === null) return internetReachable === false;
  return airplaneOn && internetReachable !== true;
}

export async function probeOffline(): Promise<boolean> {
  let airplane: boolean | null = null;
  if (Platform.OS === 'android') {
    try {
      airplane = await Network.isAirplaneModeEnabledAsync();
    } catch {
      airplane = null;
    }
  }
  let reachable: boolean | null = null;
  try {
    const state = await Network.getNetworkStateAsync();
    reachable = state.isInternetReachable ?? null;
  } catch {
    reachable = null;
  }
  return isOffline(airplane, reachable);
}
