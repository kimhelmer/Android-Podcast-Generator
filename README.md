# Podcast Studio

A small personal Android app that turns a topic into a two-person podcast episode.

1. **Research and script** with OpenAI (Responses API, web search, strict JSON output). Sources are taken only from the API's citations.
2. **Speech** with ElevenLabs (one voice for the host, one for the guest).
3. **Playback** with Media3, including with the screen locked.

Everything runs on the phone. There is no backend, and no API keys are stored in this repository: you enter them in the app, where they are encrypted with an Android Keystore key and excluded from backups.

## Cost safety

- OpenAI and ElevenLabs are separate switches in Settings, both **off** by default (the app then simulates everything and sends nothing).
- Any paid run asks for approval first, and shows usage as an *estimate*. Actual usage (OpenAI tokens, ElevenLabs characters) is shown afterwards on the script page. No money amounts are invented.
- Only HTTP 429 is retried automatically. If speech fails part-way, **Retry audio** resumes from the saved script and reuses every clip already generated.
- A character limit per episode is enforced before any paid call.

## Build

Requires JDK 17 and the Android SDK (platform 35).

```
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Create a `local.properties` file with `sdk.dir=<path to your Android SDK>` (use forward slashes; the file is git-ignored).

## Tests

All tests are JVM/Robolectric tests (about 270). External services are replaced by local fake servers; no real API is ever called. Behaviour on a physical phone (lock-screen playback, notifications, real voices) is **not** covered by them.

## Layout

```
app/src/main/java/com/podcaststudio/app/
  domain/      models, contracts, validation, state machine
  generation/  coordinator, foreground service, mock/live switching
  data/remote/ OpenAI and ElevenLabs clients and providers
  data/local/  Room database (v3) and settings
  audio/       PCM/WAV assembly, playback service, mock providers
  security/    Keystore-backed secret store
  ui/          Compose screens: create, generation, library, player, script, settings
```
