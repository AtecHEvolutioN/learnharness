# LearnHarness for Android

Native Kotlin / Jetpack Compose Android app, adapted from [LearnHarness](https://github.com/amosblomqvist/learnharness), AGPL-3.0-only. Android 8.0+ (API 26). This is an initial native port, not desktop feature parity.

## Two ways to learn

- **My PC:** use your desktop's real sessions, teacher, practice agent, model configuration and knowledge graph. The Android app submits events through a restricted, paired bridge. Provider credentials remain on the PC. Foreground views refresh every 10 seconds (2.5 seconds while the selected session is working). No background polling or automatic AI requests.
- **On this phone:** independent, locally stored lessons with an OpenAI-compatible chat-completions provider and a model supporting JSON responses. Includes an offline sample. These lessons use a compact mobile tutor protocol rather than the desktop's full agent runtime. They are not merged into the PC library.

The last opened PC session is cached for offline reading. New PC turns require a live connection. No silent offline write queue: after an interrupted request, refresh to see whether the PC saved it before retrying.

## Connect your installed PC app, including away from home

The original desktop 0.4.0 deliberately accepts API requests only from its own window. A companion script cannot simply connect to its port. The supplied desktop shell update adds an explicit **Connect Android phone…** menu action while keeping the original API authorization intact.

1. Close LearnHarness. Extract the supplied PC update ZIP and run `Install-PC-Bridge.ps1` in PowerShell. It verifies both archive hashes, backs up `app.asar`, and updates only the shell. It refuses a different installed version. The data directory and provider files are untouched.
2. Start LearnHarness. Press **Alt** if the app menu is hidden, then choose **LearnHarness → Connect Android phone…**. This starts a bridge bound only to `127.0.0.1:47832`.
3. Install [Tailscale](https://tailscale.com/download) on PC and Android, sign into the same account, and connect both devices. Enable HTTPS when Tailscale requests it.
4. On PC run `tailscale serve --bg http://127.0.0.1:47832`. Use **Serve**, which stays inside your private tailnet. Do not use public Funnel.
5. Copy the HTTPS address Tailscale prints. In the desktop connection dialog choose **Copy pairing token**.
6. In Android **Settings**, enter that HTTPS address and token, then tap **Connect and test**. Choose **My PC** on the Learn or Knowledge screen.

The PC must stay powered on and awake with LearnHarness running. Open **Connect Android phone…** after each desktop restart; the saved token remains valid until **Stop and revoke** is used. Tailscale supplies the private network and trusted HTTPS certificate. See [Tailscale Serve documentation](https://tailscale.com/docs/features/tailscale-serve).

To revoke a phone, choose **Stop and revoke** on PC. A future connection generates a new token. `tailscale serve --https=443 off` stops the default Serve endpoint. `Restore-PC-App.ps1` restores the exact archived shell after verifying its hash. An upstream desktop auto-update may replace the bridge; install a reviewed bridge update for that new version rather than applying the old patch.

## Supported native screens

- Create/resume learning and practice sessions; readable transcript and learning plan.
- PC quizzes, probes, free-text questions, explanations, confidence, reasoning, and practice problem submissions. Answers use the original core's deterministic grading logic on the bridge.
- Knowledge graph overview, connected claims and search.
- Keystore-encrypted pairing token and provider key, with backups disabled.
- Local JSON export/import, with credential exclusion, validation and non-destructive merge by session ID.

Desktop-generated demos, rich mathematical typesetting, background research artifacts, dictation, editing provider subscriptions, graph editing and full desktop UI controls remain desktop features. Native transcript text is rendered as text; embedded HTML/JS is never executed. The Android app does not transfer desktop API keys or subscription credentials.

## Build and test

Open this `android/` folder in Android Studio, or install JDK 17 and Android SDK 35, set `ANDROID_HOME`, and run:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. The debug APK is installable for testing and is not a signed Play Store release. Keep a stable private release signing key before distributing future production updates.

Bridge, from `mobile-bridge/`:

```sh
pnpm install --frozen-lockfile
pnpm build
pnpm test
```

This bundles original pure core helpers into `electron/mobile-bridge.cjs`. Rebuild the bridge when changing those helpers. No Node runtime or npm installation is required by the packaged desktop patch; it uses Electron's existing runtime. Package a version-checked update using `mobile-bridge/package-desktop-update.mjs`.

## Data and trust boundaries

PC routes are an explicit allowlist: session list/read/create, learning input, and graph read. No file-system, API-key, provider-settings, deletion, or arbitrary proxy routes. Incoming browser Origin headers are rejected. A 256-bit token is required for all requests; the desktop stores it using Electron safeStorage. The token permits AI spending through the PC's configured models, so pair only your own trusted phone.

The phone requires HTTPS and rejects redirects. It stores local records in app-private atomic JSON files. Model context is sent only when you explicitly start or continue an independent phone lesson. Import/export does not include credentials. For connected mode, your PC's configured providers receive the same session inputs they would receive from the desktop UI.

Attribution: original LearnHarness architecture, learning method, question contracts and core projection/grading logic by Amos Blomqvist and contributors. Original repository license and notices are retained at the repository root. Native port and bridge changes are provided under the same AGPL-3.0-only license.
