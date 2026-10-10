// Config plugin for the Android build:
// 1. hands the ClikaRT license *file path* (never its content) to the native module's Gradle build, the same
//    `clika.runtimeLicenseFile` key the native app reads from the repo's local.properties;
// 2. signs with the same key as the native app, so both apps present the same ClikaRT hardware identity.
//    On Android, ClikaRT identifies the hardware by Settings.Secure.ANDROID_ID, which is scoped to the app-signing
//    key (docs.clika.io, platform/concepts/runtime-licenses). The team's offline license has a single hardware
//    seat; an app signed with another key would be another hardware unit to it, even on the same phone.
//    The native app is built by Android Studio with ~/.android/debug.keystore; Expo's template would use its own
//    android/app/debug.keystore. FOF_SIGNING_KEYSTORE overrides the path.
const { withAppBuildGradle, withGradleProperties } = require('expo/config-plugins');

const TEMPLATE_STORE = "storeFile file('debug.keystore')";
const SHARED_STORE = [
  'storeFile file(System.getenv("FOF_SIGNING_KEYSTORE") ?: {',
  '                // FriendOrFoe: the key the native app is signed with (Android Studio default), see withFofAndroid.js',
  '                def shared = new File(System.getProperty("user.home"), ".android/debug.keystore")',
  '                shared.exists() ? shared.path : "debug.keystore"',
  '            }())',
].join('\n');

/** @param {import('expo/config').ExpoConfig} config @param {{ licenseFile?: string }} props */
module.exports = function withFofAndroid(config, props = {}) {
  config = withGradleProperties(config, (cfg) => {
    const items = cfg.modResults.filter((item) => !(item.type === 'property' && item.key === 'clikaRtLicenseFile'));
    if (props.licenseFile) items.push({ type: 'property', key: 'clikaRtLicenseFile', value: props.licenseFile });
    cfg.modResults = items;
    return cfg;
  });
  return withAppBuildGradle(config, (cfg) => {
    const gradle = cfg.modResults.contents;
    if (gradle.includes('FOF_SIGNING_KEYSTORE')) return cfg;
    if (!gradle.includes(TEMPLATE_STORE)) {
      throw new Error('withFofAndroid: the debug signingConfig changed in the Expo template; update the shared-key patch');
    }
    cfg.modResults.contents = gradle.replace(TEMPLATE_STORE, SHARED_STORE);
    return cfg;
  });
};
