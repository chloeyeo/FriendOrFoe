This is the React Native / Expo (SDK 57) fallback build of the FriendOrFoe observer app. Read `README.md` here and the
repo root `AGENTS.md` first; the native app in `../app` is the source of truth for every contract and rule ported here.

## Expo has changed — do not trust your training data

Before writing code against an Expo or React Native API, read the installed package (`node_modules/<pkg>/build/*.d.ts`,
`node_modules/expo-modules-core/android/...`) or the versioned docs at `https://docs.expo.dev/versions/v57.0.0/`.

## Commands

```bash
npx expo install <package>   # never npm add for Expo/RN packages: it resolves SDK-compatible versions
npm test                     # jest (pure logic in src/, no device)
npm run typecheck
npx expo-doctor
npx expo start --web --port 8090
npx expo run:android --variant release   # on a machine with JDK 17 + Android SDK + the ClikaRT Maven repo
```

Run the tests and the typecheck before declaring a task done.

## Rules

- `android/` and `ios/` are generated (Continuous Native Generation) and gitignored. Configure native behaviour in
  `app.config.ts`, `plugins/` and the local module `modules/fof-native`, never by hand.
- Navigation is one screen plus the Settings overlay (`src/ui/ObserverScreen.tsx`); no router.
- Pure logic lives in `src/core`, `src/detect`, `src/track`, `src/signal`, `src/session`, `src/net` and carries no React
  or native imports, so it stays unit-testable. Keep it in step with the Kotlin files it ports (named in each file).
- The UI follows `../web/src/lib` (palette in `src/ui/theme.ts`, square edges, monospace, result list wording).
- Never log, display or commit the ClikaRT license; only its file path reaches Gradle.
