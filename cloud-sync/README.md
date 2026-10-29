# Private encrypted lesson journal

Frameworkless Vercel Functions plus a private Vercel Blob store. This service stores opaque encrypted revisions; it cannot decrypt lessons and never proxies AI requests. Both devices call their own AI provider directly.

Create a separate Vercel project, link this directory, and create a **private** Blob store connected to production. Install dependencies with the committed pnpm lockfile. Use Node 24. Set `SYNC_TOKEN_SHA256` to the hexadecimal SHA-256 of a cryptographically random 32-byte client token (represented as 64 hexadecimal characters). Vercel's connected Blob credentials stay server-side.

Each device receives the same private configuration:

```json
{"version":1,"url":"https://YOUR-PROJECT.vercel.app","token":"64-hex-character-token","key":"base64-encoded-random-32-byte-AES-key"}
```

The setup-code format is `lh-sync-v1:` followed by Base64 of that JSON. Never commit the real configuration. On desktop, a one-time `cloud-sync-bootstrap.json` in Electron userData is imported into safeStorage and removed. On Android, `files/sync-bootstrap.json` is imported into a Keystore-encrypted preference and removed. Credentials must not be transferred between accounts or sent to this service.

Deploy this directory with `vercel deploy --prod`. Confirm the production alias accepts app requests; previews can retain Vercel Authentication. The sync endpoint itself always requires the private Bearer token and rejects browser Origin headers. Do not upgrade a hosting plan without reviewing its costs.

## Protocol

`POST /api/sync` accepts `list`, `get`, and `put`. Only 64-character hexadecimal revision IDs are accepted. Each stored blob contains `{version:1,iv,data}`; no plaintext title or user/account identifier is stored server-side. AES-GCM authenticates the revision ID as additional data. Writes are immutable and idempotent. The public GET reports only service/version metadata.

Client logic is in `mobile-bridge/portable/sync.ts`, shared by Android and Electron. It captures local changes against previously observed parents before fetching remote revisions. A durable local outbox survives network failures. Conflicting chats create deterministic copies; graph ledgers merge their assertion lines. A local fingerprint guard prevents a PC download from overwriting a newly edited session.

Individual encrypted revisions are limited to 3.9 MB, with a 40 MB expanded client limit. A larger session remains saved locally and reports a sync error. Historical revisions currently have no automatic cleanup. The deployment does not add accounts, analytics, public sharing, AI billing, or background job execution.

Run `node --test test/*.test.mjs` for request-boundary tests. Use synthetic data for production checks. Local PC replacement backups and the phone's app-private library remain the recovery paths if import fails.
