import fs from 'node:fs';
import path from 'node:path';

import type { ExpoConfig } from 'expo/config';

/**
 * Machine-specific inputs, read the way the native app reads them so one setup serves both apps:
 * the repo's gitignored `local.properties` (`clikaRtMavenRepo`, `clika.runtimeLicenseFile`), overridden by the env
 * vars `CLIKA_RT_MAVEN_REPO` / `CLIKA_RT_LICENSE_FILE`. The Maven repo default is the integration lead's laptop.
 */
function localProperties(): Record<string, string> {
  const file = path.resolve(__dirname, '..', 'local.properties');
  if (!fs.existsSync(file)) return {};
  const out: Record<string, string> = {};
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = /^\s*([^#!=:\s][^=:]*?)\s*[=:]\s*(.*?)\s*$/.exec(line);
    if (m) out[m[1]] = m[2].replace(/\\:/g, ':').replace(/\\\\/g, '\\');
  }
  return out;
}

const props = localProperties();
const mavenRepo = (process.env.CLIKA_RT_MAVEN_REPO || props.clikaRtMavenRepo || 'C:/Users/C/clika').trim();
const licenseFile = (process.env.CLIKA_RT_LICENSE_FILE || props['clika.runtimeLicenseFile'] || '').trim();

/** `C:/Users/C/clika` -> `file:///C:/Users/C/clika`; URLs pass through. */
function fileUrl(p: string): string {
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(p)) return p;
  const forward = p.replace(/\\/g, '/');
  return forward.startsWith('/') ? `file://${forward}` : `file:///${forward}`;
}

const config: ExpoConfig = {
  name: 'FriendOrFoe X',
  slug: 'friendorfoe-cross',
  version: '0.1.0',
  orientation: 'landscape',
  icon: './assets/icon.png',
  userInterfaceStyle: 'dark',
  backgroundColor: '#101113',
  android: {
    // Its own id, so it installs next to the native app on phone A.
    package: 'com.chloeyeo.friendorfoe.cross',
    adaptiveIcon: {
      backgroundColor: '#101113',
      foregroundImage: './assets/android-icon-foreground.png',
      backgroundImage: './assets/android-icon-background.png',
      monochromeImage: './assets/android-icon-monochrome.png',
    },
    permissions: ['android.permission.CAMERA', 'android.permission.ACCESS_NETWORK_STATE'],
    predictiveBackGestureEnabled: false,
  },
  ios: {
    supportsTablet: true,
    bundleIdentifier: 'com.chloeyeo.friendorfoe.cross',
    // Signing team for `expo run:ios --device` (the developer's own, so it comes from the environment, never the repo).
    appleTeamId: process.env.APPLE_TEAM_ID || undefined,
    infoPlist: {
      NSCameraUsageDescription: 'The observer camera finds people, drones and birds on the phone, offline.',
      // The BLE IFF reader bridge and the dashboard sit on the laptop on the same hotspot LAN.
      NSLocalNetworkUsageDescription: 'Reaches the IFF reader bridge and the dashboard on the laptop over the local network.',
    },
  },
  web: { favicon: './assets/favicon.png' },
  plugins: [
    [
      'expo-build-properties',
      {
        android: {
          // The ClikaRT AAR: minSdk 28, arm64-v8a only, compiled against kotlin-stdlib 2.3.0 (a 2.1 compiler cannot
          // read that metadata; 2.2.10 is what the native app builds with).
          minSdkVersion: 28,
          kotlinVersion: '2.2.10',
          buildArchs: ['arm64-v8a'],
          // Every LAN link (laptop serve, dashboard) is plain http:// to a hotspot IP.
          usesCleartextTraffic: true,
          extraMavenRepos: [fileUrl(mavenRepo), 'https://jitpack.io'],
        },
        // The iPhone camera pane keeps frames level with AVCaptureDevice.RotationCoordinator (iOS 17).
        ios: { deploymentTarget: '17.0' },
      },
    ],
    ['./plugins/withFofAndroid', { licenseFile }],
  ],
};

export default config;
