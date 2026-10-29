# Desktop phone bridge

Opt-in Electron-owned bridge for the native Android client. Follow [Android setup](../android/README.md).

The bridge starts through **Ctrl+Shift+M** or the desktop's **Connect Android phone…** menu. It binds only to `127.0.0.1:47832`. Its unique pairing token is separate from the per-launch desktop API capability. Only the Electron process holds both. Tailscale Serve provides a private HTTPS endpoint; the Android app never receives the desktop API secret, provider keys or OAuth credentials.

`src/projection.ts` reuses the original pure core's transcript projection, pending-question selection, deterministic option shuffle and answer grading. The bridge validates the question's current ID before answering. It does not write the data files itself; the desktop's normal APIs remain the single writers.

## Development

Requires Node 24 and pnpm 11.25.0. Run `pnpm install --frozen-lockfile --ignore-scripts`, `pnpm build`, `pnpm test` in this folder. Installation scripts are unnecessary: pnpm installs esbuild's platform binary as an optional dependency. Commit the generated `electron/mobile-bridge.cjs` so desktop packaging requires no extra runtime dependencies. The bundled TypeBox MIT license must accompany distributions; original LearnHarness logic retains the root AGPL license.

Tests exercise authorization, CORS rejection, route restrictions, pending-question integrity, answer confidentiality, deterministic grading, and duplicate submission IDs against a mock backend. An additional local integration check was performed against the installed desktop 0.4.0 server in an isolated data directory, with no configured model.

## Package an existing Windows install

```sh
node package-desktop-update.mjs /path/to/resources/app.asar /new/staging/directory /output/directory
```

Only desktop 0.4.0 and the expected integration anchors are accepted. The packager reads the installed archive and creates a new archive. The installer checks the exact SHA-256 of both old and new archives, requires the app to be closed, and keeps a backup. It does not edit learning data. Restore using `Restore-PC-App.ps1`.

The generated `update-manifest.json` belongs to a specific build of the installed desktop, so the local update ZIP is not a universal installer. For a different version, build the fork's Electron distribution or review and regenerate the patch against that version.

## API and failure behavior

- `GET /v1/status`, `/v1/sessions`, `/v1/sessions/:id`, `/v1/graph`
- `POST /v1/sessions` with a topic and mode
- `POST /v1/sessions/:id/turn` with a text, answer, next, continue or retry event

Every endpoint requires the pairing token. Requests carrying Origin are refused. No browser CORS grants, arbitrary proxy target, keys/settings routes, or delete endpoints exist. Redirects from the local upstream are refused. POSTs require a request ID and are deduplicated for the lifetime of the bridge, up to 10,000 operations. A bridge restart clears this in-memory deduplication ledger: clients must refresh after an uncertain network outcome, never automatically replay a POST.

The desktop turn is drained and its persisted record fetched after completion. Token streaming and desktop-generated visual artifacts are not rendered on Android in this version. The phone refreshes foreground views and handles the desktop's busy/error state. Closing the phone does not cancel a turn already running on the PC; the completed record can be resumed from either device.

This is an initial version. No real phone or remote Tailscale connection was available during development. End-to-end pairing and a live provider conversation must be checked on the user's devices before relying on this build for regular use.
