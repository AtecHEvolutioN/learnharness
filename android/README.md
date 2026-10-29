# LearnHarness Android 0.2

Native Kotlin / Compose app adapted from [LearnHarness](https://github.com/amosblomqvist/learnharness), AGPL-3.0-only. Android 8+ with an up-to-date Android System WebView.

## Independent learning

In Settings, choose **Continue with ChatGPT**, complete sign-in in the system browser, return to the app, and select an available model. This phone has its own OAuth registration and credentials. It calls OpenAI directly with your authorized ChatGPT plan; the PC can be switched off. There is no automatic API-key billing fallback.

Return to LearnHarness as soon as the browser says sign-in was received: the credential exchange waits for the app to be visible. If approval hangs with Android Power saving enabled, turn Power saving off temporarily for sign-in. The Galaxy S23 setup required this. New registrations are labeled LearnHarness Android; an existing registration retains its previously approved name.

The visible interface is native Compose: a dark session drawer, learning plans, question and practice cards, Markdown/LaTeX, and searchable knowledge. A packaged, network-disabled JavaScript engine shares the desktop transcript, question and grading contracts. Kotlin performs network requests and app-private persistence. No remote website or script runs in the engine.

Teaching includes assessment, prerequisite plans, quizzes/free recall, feedback, practice problems with committed solutions, and saved propositions. Research jobs, PDF imports, generated demos/visuals, dictation, desktop graph editing and long-context compaction remain desktop features. Synced transcripts remain readable, but associated artifact files are not copied. This is not full desktop feature parity.

## Private cloud sync

The optional [private sync service](../cloud-sync/README.md) lets each device upload and download independently. They do not need to be online together. PC sync runs while the desktop app is open. Phone sync runs while the app is in the foreground, after completed actions, and through **Sync lessons**. It is not an Android background service. Unsynced changes remain local until a successful retry.

The PC **Private cloud sync…** menu (**Ctrl+Shift+Y**) shows status and copies the phone setup code. Paste it into Android Settings → Private cloud sync. This code contains the vault key; share it only with your own app. The old Tailscale address/pairing token is a separate connection, not a cloud setup code.

Sessions and four graph ledgers use an immutable revision journal, compressed and encrypted on-device with AES-256-GCM. Vercel Blob is private. The server holds an access-token hash, not the decryption key or ChatGPT credentials. Concurrent session edits retain a separate conflict copy. An edit concurrent with deletion is retained. PC imports wait for active work to finish and compare the local fingerprint before replacement. Replaced local records are backed up.

When both devices change the same lesson, the phone follows the conflict copy containing its own history and displays a notice. Separate lessons can be used concurrently without branching. Markdown tables render natively with wrapping cells; wide tables use equal-width columns rather than the desktop's horizontal scrolling layout.

Keep a secure copy of the setup code: losing both devices and the vault key makes cloud ciphertext unrecoverable. Changing vaults is deliberately refused until the existing library is dealt with explicitly. Historical revisions currently have no automatic purge.

The local sync cache keeps complete bodies only for current revisions and pending uploads; older uploaded revisions retain ancestry metadata locally and encrypted bodies in cloud storage. This prevents each saved turn from duplicating the complete lesson history in Android's bridge. Failed or oversized bridge operations return an error; sync has a two-minute timeout and keeps local work for retry.

## Existing data and backups

The 0.1 phone library stays intact. Messages and plans are copied into the shared format, with the complete original record retained inside each migrated session. Migration is tracked locally so deleting a migrated session does not recreate it from the old library.

**Export all lessons** saves the shared library, knowledge and original phone records. Credentials and vault keys are excluded. Import validates sessions and keeps distinct incoming versions as copies; old 0.1 backup files remain accepted. App records use atomic JSON writes. Android system backup is disabled for credential safety.

## Optional direct PC connection

The paired bridge remains in Settings → Earlier phone lessons and live PC connection. It uses the PC agent while that PC is running. Enable it with **Ctrl+Shift+M**, then use Tailscale Serve HTTPS and the bridge pairing token. Do not use public Funnel. See [bridge details](../mobile-bridge/README.md).

Tailscale is not needed for independent ChatGPT teaching or cloud sync. The last direct-PC session remains cached for reading. A direct-PC request is never silently replayed after an uncertain network failure.

## Build and verification

Install root dependencies, then build the shared bundles:

```sh
cd mobile-bridge
pnpm install --frozen-lockfile --ignore-scripts
pnpm build
pnpm test
cd ../android
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Use JDK 17 and Android SDK 35. APK: `app/build/outputs/apk/debug/app-debug.apk`. Updates must use the same signing key; install with `adb install -r`, never uninstall to update. This is a development APK, not a Play Store release.

The device instrumentation test checks the real bundled engine, authenticated encryption, and fresh-device sync using an in-memory cloud without reading the user's account or library. Unit tests cover migration, grading, failed requests, conflicts, deletion, outbox recovery, store validation and native UI.

Original LearnHarness architecture, learning method, prompts and core contracts: Amos Blomqvist and contributors. Original license/notices remain at the repository root. Native port and sync changes use the same AGPL-3.0-only license. Bundled TypeBox's MIT notice is included in Android assets.
