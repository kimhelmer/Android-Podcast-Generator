# Podcast Studio

An Android app that turns a topic into a short two-person podcast episode, entirely from your phone.

You type what the episode should be about, pick a language, a length and two voices. The app researches the topic on the web, writes a conversation between a **host** and a **guest**, speaks it with realistic voices, and lets you listen to it, read the script and see the sources.

> **Status:** an early personal project. It is covered by about 270 automated tests that use fake servers, and it has been run on a real phone by its author, but not across many devices or Android versions. Expect rough edges. Some things, such as lock-screen playback with real ElevenLabs audio, have had limited real-device testing.
>
> **Not affiliated:** this project is independent. It is not affiliated with, endorsed by, or sponsored by OpenAI or ElevenLabs. Their names and trademarks belong to their owners.

## Contents

- [What it does](#what-it-does)
- [How OpenAI and ElevenLabs are used](#how-openai-and-elevenlabs-are-used)
- [Try it without any API keys](#try-it-without-any-api-keys)
- [Requirements](#requirements)
- [Build the app](#build-the-app)
- [Install the APK on your phone](#install-the-apk-on-your-phone)
- [Get and enter your API keys](#get-and-enter-your-api-keys)
- [Turn on live generation](#turn-on-live-generation)
- [Using the app](#using-the-app)
- [Costs](#costs)
- [Privacy and security](#privacy-and-security)
- [Limitations](#limitations)
- [Troubleshooting](#troubleshooting)
- [Developing](#developing)
- [License](#license)

## What it does

1. **Create:** describe a topic, choose Danish or English, choose 5, 10 or 15 minutes, and choose a host voice and a guest voice.
2. **Generate:** the app researches the topic, writes the script, speaks every line and joins the lines into one audio file. A progress screen shows each stage and can be cancelled.
3. **Listen:** play the episode in the app. It keeps playing when the screen is off or you switch apps, and remembers where you stopped.
4. **Inspect:** read the full script, see which sources the research used, and see how much was used.
5. **Manage:** episodes are listed in a Library. If audio generation fails part-way, **Retry audio** continues without redoing the research or script. Episodes can be deleted.

There is no server and no account: everything is stored on your phone.

## How OpenAI and ElevenLabs are used

| Step | Service | What happens |
|---|---|---|
| Research | OpenAI | One request using OpenAI's built-in **web search** tool gathers facts. Notes are labelled verified or unverified. The list of sources comes only from the citations OpenAI returns, never from text the model wrote. |
| Script | OpenAI | A second request writes a conversation between HOST and GUEST as structured data. The app then checks it: speakers must alternate starting with HOST, spoken text must be plain (no web addresses or formatting), it must be in the chosen language and roughly the right length. If it fails these checks it is rewritten **once** (one extra call, recorded in the script's warnings). |
| Voices | ElevenLabs | The app lists the voices in your ElevenLabs account for you to pick from, and can play each voice's sample. |
| Speech | ElevenLabs | Each line of dialogue is sent as its own request with the host's or guest's voice. The audio is checked, trimmed of dead air, joined with short pauses and saved as a WAV file. |

Which models are used is set in **Settings** (see below). Model names change over time, so they are editable.

## Try it without any API keys

Both services are **off by default**. In that simulated mode nothing is sent anywhere and nothing costs money: the app writes a short placeholder script and uses a bundled 24-second tone recording as the "episode". It is a good way to see the screens and the flow before you add any keys.

## Requirements

**To run the app:** a phone running **Android 8.0 (API 26) or newer**. Live generation needs an internet connection.

**For live generation:** an [OpenAI](https://platform.openai.com) API account and an [ElevenLabs](https://elevenlabs.io) account with an API key. You pay those services directly (see [Costs](#costs)).

**To build the app yourself:**

- **JDK 17** (this is what the project is built and tested with).
- The **Android SDK** with **platform 35**. The easiest way to get both the SDK and a JDK is to install [Android Studio](https://developer.android.com/studio).
- An internet connection for the first build, which downloads Gradle 8.9 and the libraries.

## Build the app

Get the code:

```
git clone https://github.com/kimhelmer/Android-Podcast-Generator.git
cd Android-Podcast-Generator
```

**1. Tell Gradle where the Android SDK is.** Either create a file called `local.properties` in the project folder containing one line:

| System | Example line |
|---|---|
| Windows | `sdk.dir=C:/Users/YourName/AppData/Local/Android/Sdk` |
| macOS | `sdk.dir=/Users/YourName/Library/Android/sdk` |
| Linux | `sdk.dir=/home/yourname/Android/Sdk` |

(Use forward slashes, even on Windows. The file is git-ignored and stays on your computer.) Or set the `ANDROID_HOME` environment variable to the same folder. Opening the project in Android Studio creates `local.properties` for you.

**2. Accept the Android SDK licences** once, so Gradle can download any missing SDK parts automatically. With the command-line tools installed:

```
sdkmanager --licenses
```

**3. Build the debug app:**

| System | Command |
|---|---|
| Windows (PowerShell or Command Prompt) | `.\gradlew.bat assembleDebug` |
| Linux or macOS | `./gradlew assembleDebug` |

The first build takes several minutes. Gradle downloads itself (the download is checked against a published checksum) and then the libraries. When it finishes, the app is here:

```
app/build/outputs/apk/debug/app-debug.apk
```

Prefer a graphical tool? In Android Studio choose **File → Open**, select the project folder, wait for the sync, then press **Run** with a phone or emulator connected.

If Gradle says it cannot find Java 17, install JDK 17 and set `JAVA_HOME` to its folder.

This produces a **debug** build. There is no release or Play Store build.

## Install the APK on your phone

**Option A: copy the file (no cable tools needed)**

1. Copy `app-debug.apk` to your phone (USB cable in File Transfer mode, a cloud drive, email to yourself, and so on).
2. On the phone, open it from a file manager (for example **My Files → Downloads**).
3. If Android asks, allow **Install unknown apps** for the app you opened the file from, then go back and tap the file again.
4. Tap **Install**. Google Play Protect may warn that the app is unverified. That is expected for a debug build you made yourself; choose **Install anyway**.

**Option B: adb (developers)**

Turn on **Developer options → USB debugging** on the phone, connect it, then run:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Updating:** installing a newer build over the old one keeps your episodes and settings, but only if both were built on the same computer. A build from a different computer has a different signature, and Android will refuse to update it; uninstall first (this deletes your episodes).

On first start, on Android 13 or newer, the app asks to **show notifications**. Allow it if you want the generation progress notification and the playback controls on the lock screen. The app works without it.

## Get and enter your API keys

You need a key from each service you want to use. Keys are secret passwords for your account: never post them in a public place, and never put them in the code.

**OpenAI key**

1. Sign in to the [OpenAI Platform](https://platform.openai.com).
2. Open the **API keys** page (usually at <https://platform.openai.com/api-keys>) and create a new secret key.
3. Copy it straight away. OpenAI shows the full key only once.
4. You may need to add credits or a payment method on your OpenAI account before requests work.

**ElevenLabs key**

1. Sign in to ElevenLabs and open <https://elevenlabs.io/app/api/api-keys>.
2. Create an API key and copy it.
3. If you restrict the key's permissions, it must allow text-to-speech and listing voices; otherwise the app shows a permission error.

**Enter them in the app**

1. Open the **Settings** tab (bottom right).
2. Under **OpenAI API key**, tap the field marked **Enter key**, paste the key, and tap **Save**. The text appears as dots, and the status changes to *A key is stored (hidden)*.
3. Do the same under **ElevenLabs API key**.

To replace a key, paste a new one and **Save**. To delete it, tap **Remove**. The app never shows a stored key again.

## Turn on live generation

Saving a key does **not** switch anything on. In **Settings**, scroll down to the two sections:

- **Script generation → Use OpenAI for research and scripts:** switch on for real research and a real script.
- **Speech generation → Use ElevenLabs for audio:** switch on for real voices.

The two switches are independent:

| OpenAI | ElevenLabs | Result |
|---|---|---|
| off | off | Everything simulated. Nothing is sent. Costs nothing. |
| on | off | Real research and script. The audio is the bundled sample, and the Library marks the episode *Placeholder audio (ElevenLabs was off)*. |
| off | on | A short placeholder script spoken by real voices. A cheap way to try voices. |
| on | on | The full experience. |

If a switch is on but its key is missing, the app tells you ("Live mode needs an OpenAI API key above.").

Other settings in the same sections (change a value, then tap **Save** in that section):

| Setting | Default | Notes |
|---|---|---|
| Research model | `gpt-6.1-sol` | Must support OpenAI's web search tool. |
| Script model | `gpt-6.1-sol` | Must support structured (JSON schema) output. |
| Speaking rate | 150 words per minute | 100 to 220. Used to aim for the episode length. |
| Speech model | `eleven_multilingual_v2` | The text under the field says whether the model is recognised. |
| Character limit per episode | 20,000 | 1,000 to 100,000. A safety cap on speech characters. |

The defaults are a starting point. If OpenAI or ElevenLabs reports that a model does not exist for your account, type one that your account can use.

## Using the app

### Generate an episode

1. Open the **Create** tab.
2. Describe the topic (10 to 2,000 characters).
3. Choose a **language** (Dansk or English) and a **duration** (5, 10 or 15 min).
4. Choose a **host voice** and a **guest voice**; they must be different. With ElevenLabs switched on the list shows the voices in your account, and a play button next to a voice previews it where a sample exists. In simulated mode the voices are Anna, Mads, Sofie (Danish only) and James (English only).
5. Tap **Generate**.
6. If any paid service is switched on, the app first asks **Start paid generation?**, explaining what will be used. Tap **Start** to go ahead or **Cancel** to change your mind. Nothing is sent before you approve.
7. A progress screen shows the stage: *Researching topic*, *Writing script*, *Generating voices (3/14)*, *Assembling episode*. A real episode can take several minutes. **Cancel** stops it. Generation runs in a foreground service so that it is designed to continue if you lock the phone or switch apps, and a notification shows progress.

### Listen

When it finishes, tap **Play episode**, or open the **Library** tab and tap the episode. The player has play/pause, a seek bar and the elapsed and total time. Playback is designed to continue with the screen off or in the background, and the app remembers your position. While it plays, the app provides an Android media notification so you can control it from the lock screen (this has had limited testing on real phones).

### Inspect the script and sources

In the player, tap **View script and sources**. You will see the title and summary, the language, word count and estimated length, when it was researched and which models were used, any warnings, the actual usage, the whole conversation grouped by section (HOST and GUEST), and the list of **Sources**. Tap a source to open it in your browser.

### Retry audio after a failure

If speech generation stops part-way (for example the connection drops, or your ElevenLabs credits run out), the progress screen shows what went wrong and a **Retry audio** button. Your script and every successfully generated line are kept.

- Tapping **Retry audio** first shows what will happen: how many lines are already done and will be reused for free, and how many characters will be sent. Tap **Retry audio** again to confirm.
- OpenAI research and the script are **not** repeated.
- If you closed the app, the **Create** tab shows an **Unfinished episode** card with **Retry audio** and **Discard** buttons. Unfinished episodes are kept for 7 days.

### Delete an episode

In the **Library**, tap the trash icon on an episode and confirm **Delete**. This removes its audio, script and sources from your phone. The bundled sample episode cannot be deleted.

## Costs

OpenAI and ElevenLabs charge **you**, directly, for what the app sends. This app does not know their prices and does not show money amounts.

- **OpenAI** charges by tokens. An episode makes one research request (using web search) and one script request, plus one more if the script fails its checks and is rewritten. Prices depend on the model; check OpenAI's pricing page.
- **ElevenLabs** counts the characters of text it speaks. The app estimates about **4,900, 9,800 or 14,600 characters** for a 5, 10 or 15 minute episode at the default speaking rate; the exact number is known once the script exists. How many credits a character costs depends on your plan and the model; check your ElevenLabs plan.
- **Estimate versus actual:** screens before you start say "estimate". After a run, the script page shows the **actual** usage: tokens as reported by OpenAI and characters sent to ElevenLabs.

Built-in protections:

- Live services are off by default, and every paid run needs your approval.
- The character limit is checked before anything is spent and again once the script is written, counting only characters that still need sending.
- The app retries automatically **only** when a service says "too many requests" (HTTP 429), because that request was refused before any work was done. Timeouts, server errors and cut-off downloads are never retried automatically, since the work may already have been billed. You decide with **Retry audio**.

These help but are not a guarantee: a failed run can still have used some credit (for example the research step before a later failure, or a speech request that was cancelled mid-way). Also set spending limits in your OpenAI and ElevenLabs dashboards.

## Privacy and security

- **Your keys** are encrypted on the phone with a key held in the Android Keystore. They are excluded from Android backups, are never written to logs, and the app never displays a stored key. They are sent only to the service they belong to, over HTTPS.
- **What leaves your phone** in live mode: your topic and language, and the research notes, go to OpenAI; the script text goes to ElevenLabs (with the voice you chose). Voice previews are streamed from ElevenLabs. The app asks OpenAI not to store the responses; read both companies' data policies to see how they handle data.
- **What stays on your phone:** episodes (audio, scripts, sources) live in the app's private storage. A 5 minute episode is a WAV file of roughly 14 MB. Uninstalling the app deletes everything.
- There is no backend, no account, no analytics and no advertising in this app. Only HTTPS connections are allowed.
- The app is open source so you can check all of this. If you fork it, never commit keys; the repository's `.gitignore` excludes the usual secret files.

## Limitations

- Danish and English only. Episodes are 5, 10 or 15 minutes, with exactly two speakers. There is no script editing.
- **AI makes mistakes.** Scripts can contain errors, and web research can be wrong or out of date. Check the sources before you rely on or publish anything.
- Check the terms of OpenAI and ElevenLabs (including the rights that come with the voices you choose) before publishing generated audio.
- Debug build only. Aggressive battery savers on some phones may stop background work; **Retry audio** recovers from that.
- Only one episode can be generated at a time. Only the first 1,000 voices of an ElevenLabs account are listed.
- If you cancel while a speech request is in flight, that one line may still be billed but is lost.
- The bundled sample is a synthetic tone recording, not speech.

## Troubleshooting

| Message | What to do |
|---|---|
| "Live OpenAI mode is on but no API key is stored" (or the same for ElevenLabs) / "Add your ... API key in Settings first." | Enter the key in **Settings** and tap **Save**. |
| "OpenAI rejected the API key" / "ElevenLabs rejected the API key" | The key is wrong, revoked or typed with extra characters. Create a new one and save it again. |
| "OpenAI could not find that model for your account" | Change the model name in **Settings** to one your account supports. |
| "Your OpenAI account is out of credit or over its quota" / "Your ElevenLabs account is out of credits or over its quota" | Add credit or change plan, then tap **Retry audio** (for ElevenLabs). |
| "This episode ... characters of speech but your limit is ..." | Choose a shorter episode, or raise **Character limit per episode** in **Settings**. |
| "The generated script failed validation" | The model's script broke a rule even after one rewrite. Try again, or use a different script model. |
| No voices listed | Live speech needs a stored ElevenLabs key. Check **Settings** and the switch. |

## Developing

Source layout:

```
app/src/main/java/com/podcaststudio/app/
  domain/      models, contracts, validation, state machine
  generation/  coordinator, foreground service, simulated/live switching
  data/remote/ OpenAI and ElevenLabs clients and providers
  data/local/  Room database (version 3) and settings storage
  audio/       PCM/WAV assembly, playback service, simulated providers
  security/    Keystore-backed secret store
  ui/          Compose screens: create, generation, library, player, script, settings
```

Run the checks (use `.\gradlew.bat` on Windows):

```
./gradlew lintDebug testDebugUnitTest assembleDebug
```

The tests are JVM and Robolectric tests. OpenAI and ElevenLabs are replaced by local fake servers, so **no test ever calls a real API or spends money**. They cannot check behaviour on a physical phone, such as lock-screen playback, notifications or real voices.

Built with Kotlin, Jetpack Compose, Room, Media3, Retrofit/OkHttp and kotlinx.serialization.

## License

[MIT](LICENSE). Copyright (c) 2026 Kim Private.
